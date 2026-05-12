package com.market.service;

import com.market.model.BitcoinSignal;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Sends WhatsApp alerts via CallMeBot (free, no Business API needed).
 *
 * Setup (one-time, takes ~2 min):
 *   1. Add +34 644 59 30 06 to your WhatsApp contacts (name it "CallMeBot")
 *   2. Send this message to that contact:
 *        I allow callmebot to send me messages
 *   3. You'll receive your API key by reply
 *   4. Set in application.properties:
 *        market.whatsapp.phone=33612345678   (country code, no +)
 *        market.whatsapp.apikey=YOUR_KEY
 *
 * Alert policy:
 *   - Only fires on LONG or SHORT signals (not WAIT)
 *   - Only fires on direction *transitions* (LONG→WAIT→SHORT counts as new)
 *   - Only fires when confidence ≥ configured threshold (default 70)
 *   - Minimum 5 minutes between two alerts (cooldown)
 */
@ApplicationScoped
public class WhatsAppAlertService {

    private static final Logger LOG = Logger.getLogger(WhatsAppAlertService.class);
    private static final String CALLMEBOT_URL = "https://api.callmebot.com/whatsapp.php";
    private static final long   COOLDOWN_MS   = 5 * 60 * 1000L;

    @ConfigProperty(name = "market.whatsapp.phone",     defaultValue = "")
    Optional<String> phone;

    @ConfigProperty(name = "market.whatsapp.apikey",    defaultValue = "")
    Optional<String> apiKey;

    @ConfigProperty(name = "market.whatsapp.min-confidence", defaultValue = "70")
    int minConfidence;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Last direction that triggered an alert (to detect transitions). */
    private String  lastAlertedDirection = "WAIT";
    private long    lastAlertAt          = 0;

    /**
     * Called after every fresh signal computation.
     * Sends an alert only if conditions are met (conviction + transition + cooldown).
     */
    public void notifyIfNeeded(BitcoinSignal signal) {
        if (!isEnabled()) return;
        if (signal == null || signal.error != null) return;

        String dir = signal.direction;
        if (!"LONG".equals(dir) && !"SHORT".equals(dir)) {
            // Signal went back to WAIT — reset so next LONG/SHORT fires again
            lastAlertedDirection = "WAIT";
            return;
        }

        if (signal.confidence < minConfidence) {
            LOG.debugf("WhatsApp alert skipped — confidence %d < %d", signal.confidence, minConfidence);
            return;
        }

        boolean sameDirection = dir.equals(lastAlertedDirection);
        boolean inCooldown    = (System.currentTimeMillis() - lastAlertAt) < COOLDOWN_MS;

        if (sameDirection && inCooldown) {
            LOG.debugf("WhatsApp alert skipped — same direction (%s) within cooldown", dir);
            return;
        }

        send(buildMessage(signal));
        lastAlertedDirection = dir;
        lastAlertAt          = System.currentTimeMillis();
    }

    public boolean isEnabled() {
        return phone.isPresent()  && !phone.get().isBlank()
            && apiKey.isPresent() && !apiKey.get().isBlank();
    }

    /** Force-sends a test alert, bypassing direction/cooldown filters. */
    public void sendTest(BitcoinSignal signal) {
        send("🧪 *TEST* " + buildMessage(signal));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String buildMessage(BitcoinSignal s) {
        String emoji = "LONG".equals(s.direction) ? "🟢📈" : "🔴📉";
        String dir   = s.direction;
        return String.format(
            "%s *SIGNAL BTC %s* — Conviction %d%%\n" +
            "Prix : $%,.0f\n" +
            "Entrée : $%,.0f | Levier : x%d\n" +
            "SL : $%,.0f | TP1 : $%,.0f | TP2 : $%,.0f | TP3 : $%,.0f\n" +
            "Raisons : %s",
            emoji, dir, s.confidence,
            s.currentPrice,
            s.entryPrice, s.leverage,
            s.stopLoss, s.tp1, s.tp2, s.tp3,
            s.reasoning != null ? s.reasoning : "—"
        );
    }

    private void send(String text) {
        if (!isEnabled()) return;
        try {
            String encoded = URLEncoder.encode(text, StandardCharsets.UTF_8);
            String url = CALLMEBOT_URL
                    + "?phone="  + phone.get()
                    + "&text="   + encoded
                    + "&apikey=" + apiKey.get();

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                LOG.infof("WhatsApp alert sent (%s)", text.substring(0, Math.min(60, text.length())));
            } else {
                LOG.warnf("CallMeBot HTTP %d: %s", resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            LOG.errorf("Failed to send WhatsApp alert: %s", e.getMessage());
        }
    }
}
