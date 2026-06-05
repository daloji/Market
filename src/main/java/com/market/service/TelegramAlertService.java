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

    /** Offset for getUpdates — tracks the last processed update_id to avoid duplicates. */
    private volatile long lastUpdateId = -1;

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

    /**
     * Sends a trade placement notification (called immediately when a trade is placed).
     * Always fires — no cooldown/direction filter.
     */
    public void sendTradeAlert(String dir, int conf, double price, double sl,
                               double tp1, double tp2, double tp3,
                               double slPct, double tpPct, int leverage, double amountUsdt) {
        if (!isEnabled()) return;
        String emoji = "LONG".equals(dir) ? "🟢📈 LONG" : "🔴📉 SHORT";
        String text = String.format(java.util.Locale.US,
            "%s *BTC/USDT — Trade ouvert*\n" +
            "Entrée : $%,.2f | Levier : x%d | Mise : $%.0f\n" +
            "SL : $%,.2f (%.1f%%)\n" +
            "TP1 : $%,.2f (%.1f%%) · TP2 : $%,.2f · TP3 : $%,.2f\n" +
            "Conviction : %d%%",
            emoji, price, leverage, amountUsdt,
            sl, slPct,
            tp1, tpPct, tp2, tp3,
            conf
        );
        send(text);
    }

    /**
     * Sends a trade close notification (SL/TP hit or manual close).
     */
    public void sendCloseAlert(String dir, String reason, double price, double pnl) {
        if (!isEnabled()) return;
        String emoji = pnl >= 0 ? "✅" : "❌";
        String text = String.format(java.util.Locale.US,
            "%s *BTC/USDT — Position fermée*\n" +
            "Direction : %s | Raison : %s\n" +
            "Prix de clôture : $%,.2f\n" +
            "P&L estimé : %s$%.2f",
            emoji, dir, reason, price,
            pnl >= 0 ? "+" : "", pnl
        );
        send(text);
    }

    /**
     * Sends a scalping trade open notification (1m trades).
     * tp2=0 means single-TP mode (position too small to split).
     */
    public void sendScalpingAlert(String dir, double price, double tp1, double tp2, double sl,
                                  int conf, int leverage, double amount) {
        if (!isEnabled()) return;
        String emoji = "LONG".equals(dir) ? "⚡🟢 SCALP LONG" : "⚡🔴 SCALP SHORT";
        String tp2Text = tp2 > 0
            ? String.format(java.util.Locale.US, " · TP2 : $%,.1f", tp2)
            : "";
        String text = String.format(java.util.Locale.US,
            "%s *BTC/USDT [1m]*\n" +
            "Entrée : $%,.2f | Conf : %d%% | x%d — $%.0f\n" +
            "TP1 : $%,.1f%s · SL : $%,.1f",
            emoji, price, conf, leverage, amount,
            tp1, tp2Text, sl
        );
        send(text);
    }

    /** Sends a raw pre-formatted message (e.g. trade summary). */
    public void sendMessage(String text) {
        if (!isEnabled()) return;
        send(text);
    }

    /**
     * Polls Telegram for new messages from the authorized chat only.
     * Returns command texts as-is (trimmed). Caller decides what to do with them.
     * Updates {@code lastUpdateId} so each message is returned at most once.
     */
    public java.util.List<String> pollCommands() {
        if (!isEnabled()) return java.util.Collections.emptyList();
        try {
            String url = API_BASE + botToken.get() + "/getUpdates?offset=" + (lastUpdateId + 1)
                       + "&timeout=0&limit=20&allowed_updates=%5B%22message%22%5D";
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return java.util.Collections.emptyList();

            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = om.readTree(resp.body());
            if (!root.path("ok").asBoolean()) return java.util.Collections.emptyList();

            java.util.List<String> commands = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode update : root.path("result")) {
                long updateId = update.path("update_id").asLong();
                if (updateId > lastUpdateId) lastUpdateId = updateId;

                // Ignore messages from other chats (security filter)
                String msgChatId = update.path("message").path("chat").path("id").asText("");
                if (!chatId.get().equals(msgChatId)) continue;

                String text = update.path("message").path("text").asText("").trim();
                if (!text.isEmpty()) commands.add(text);
            }
            return commands;
        } catch (Exception e) {
            LOG.debugf("[Telegram] pollCommands error: %s", e.getMessage());
            return java.util.Collections.emptyList();
        }
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
            // Build JSON safely using Jackson to avoid manual escaping issues
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode body = om.createObjectNode();
            body.put("chat_id", chatId.get());
            body.put("text", text);
            body.put("parse_mode", "Markdown");

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + botToken.get() + "/sendMessage"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(body)))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                LOG.infof("[Telegram] Alert sent: %s", text.substring(0, Math.min(50, text.length())));
            } else {
                LOG.warnf("[Telegram] API HTTP %d: %s", resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            LOG.errorf("[Telegram] Failed to send alert: %s", e.getMessage());
        }
    }
}
