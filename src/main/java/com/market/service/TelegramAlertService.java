package com.market.service;

import com.market.model.BitcoinSignal;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Sends Telegram alerts via the Telegram Bot API.
 *
 * Setup (one-time, ~2 minutes):
 *   1. Open Telegram and search for @BotFather
 *   2. Send /newbot and follow instructions → you get a token like:
 *        7123456789:AAFxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
 *   3. Start a conversation with your bot (search its name and click Start)
 *   4. Get your chat_id:
 *        https://api.telegram.org/bot{TOKEN}/getUpdates
 *        Look for: "message" → "chat" → "id"
 *      For a group/channel: add the bot, send a message, then call getUpdates
 *   5. Set in application.properties:
 *        market.telegram.bot-token=7123456789:AAFxxx...
 *        market.telegram.chat-id=123456789
 *
 * Alert policy:
 *   - Only fires on LONG or SHORT signals (not WAIT)
 *   - Only fires on direction transitions (avoids spam on repeated same direction)
 *   - Only fires when confidence ≥ configured threshold (default 60)
 *   - Minimum 5 minutes between two alerts (cooldown)
 */
@ApplicationScoped
public class TelegramAlertService {

    private static final Logger LOG          = Logger.getLogger(TelegramAlertService.class);
    private static final String API_BASE     = "https://api.telegram.org/bot";
    private static final long   COOLDOWN_MS  = 5 * 60 * 1000L;

    @ConfigProperty(name = "market.telegram.bot-token", defaultValue = "")
    Optional<String> botToken;

    @ConfigProperty(name = "market.telegram.chat-id", defaultValue = "")
    Optional<String> chatId;

    @ConfigProperty(name = "market.telegram.min-confidence", defaultValue = "60")
    int minConfidence;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Last direction that triggered an alert (detects transitions). */
    private String lastAlertedDirection = "WAIT";
    private long   lastAlertAt          = 0;

    /**
     * Called after every fresh signal computation.
     * Sends an alert only when conviction + direction transition + cooldown conditions pass.
     */
    public void notifyIfNeeded(BitcoinSignal signal) {
        if (!isEnabled()) return;
        if (signal == null || signal.error != null) return;

        String dir = signal.direction;
        if (!"LONG".equals(dir) && !"SHORT".equals(dir)) {
            lastAlertedDirection = "WAIT"; // reset so next directional signal fires again
            return;
        }

        if (signal.confidence < minConfidence) {
            LOG.debugf("Telegram alert skipped — confidence %d < %d", signal.confidence, minConfidence);
            return;
        }

        boolean sameDirection = dir.equals(lastAlertedDirection);
        boolean inCooldown    = (System.currentTimeMillis() - lastAlertAt) < COOLDOWN_MS;

        if (sameDirection && inCooldown) {
            LOG.debugf("Telegram alert skipped — same direction (%s) within cooldown", dir);
            return;
        }

        send(buildMessage(signal));
        lastAlertedDirection = dir;
        lastAlertAt          = System.currentTimeMillis();
    }

    public boolean isEnabled() {
        return botToken.isPresent() && !botToken.get().isBlank()
            && chatId.isPresent()   && !chatId.get().isBlank();
    }

    /** Force-sends a test alert, bypassing direction/cooldown filters. */
    public void sendTest(BitcoinSignal signal) {
        send("🧪 *TEST* " + buildMessage(signal));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String buildMessage(BitcoinSignal s) {
        String emoji = "LONG".equals(s.direction) ? "🟢📈" : "🔴📉";
        return String.format(
            "%s *SIGNAL BTC %s* — Conviction %d%%\n" +
            "Prix : $%,.0f\n" +
            "Entrée : $%,.0f | Levier : x%d\n" +
            "SL : $%,.0f | TP1 : $%,.0f | TP2 : $%,.0f | TP3 : $%,.0f\n" +
            "RSI : %.1f | ADX : %.1f | EMA9/21 : %.0f/%.0f\n" +
            "Raisons : %s",
            emoji, s.direction, s.confidence,
            s.currentPrice,
            s.entryPrice, s.leverage,
            s.stopLoss, s.tp1, s.tp2, s.tp3,
            s.rsi, s.adx, s.ema9, s.ema21,
            s.reasoning != null ? s.reasoning : "—"
        );
    }

    private void send(String text) {
        if (!isEnabled()) return;
        try {
            // Escape characters that break Telegram Markdown (legacy mode)
            String escaped = text
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n");

            String json = "{\"chat_id\":\"" + chatId.get() + "\","
                        + "\"text\":\""    + escaped        + "\","
                        + "\"parse_mode\":\"Markdown\"}";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + botToken.get() + "/sendMessage"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                LOG.infof("[Telegram] Alert sent: %s conf=%d%%", text.substring(0, Math.min(50, text.length())), 0);
            } else {
                LOG.warnf("[Telegram] API HTTP %d: %s", resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            LOG.errorf("[Telegram] Failed to send alert: %s", e.getMessage());
        }
    }
}
