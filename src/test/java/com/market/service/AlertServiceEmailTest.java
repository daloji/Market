package com.market.service;

import com.market.model.StockRecommendation;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AlertService with a configured email address.
 * Uses a TestProfile that sets market.alert.email so the mail-sending path
 * is actually exercised (Quarkus mailer is in mock mode → no real SMTP).
 */
@QuarkusTest
@TestProfile(AlertServiceEmailTest.EmailProfile.class)
class AlertServiceEmailTest {

    public static class EmailProfile implements io.quarkus.test.junit.QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("market.alert.email", "test@example.com");
        }
    }

    @Inject AlertService alertService;
    @Inject MockMailbox  mailbox;

    @BeforeEach
    void clearMailbox() {
        mailbox.clear();
    }

    // ── Email is actually sent ─────────────────────────────────────────────────

    @Test
    void sendBuyAlert_emailConfigured_mailIsSent() {
        alertService.sendBuyAlert(recommendation("AAPL", 85, 195.50));
        assertEquals(1, mailbox.getTotalMessagesSent(),
                "One email should be sent when alert email is configured");
    }

    @Test
    void sendBuyAlert_emailConfigured_recipientIsCorrect() {
        alertService.sendBuyAlert(recommendation("MSFT", 78, 420.0));
        var mails = mailbox.getMailsSentTo("test@example.com");
        assertEquals(1, mails.size(), "Mail should be sent to the configured address");
    }

    @Test
    void sendBuyAlert_emailConfigured_subjectContainsSymbolAndScore() {
        alertService.sendBuyAlert(recommendation("TSLA", 90, 250.0));
        var mail = mailbox.getMailsSentTo("test@example.com").get(0);
        assertTrue(mail.getSubject().contains("TSLA"), "Subject must contain the symbol");
        assertTrue(mail.getSubject().contains("90"),   "Subject must contain the score");
    }

    @Test
    void sendBuyAlert_emailConfigured_bodyContainsKeyFields() {
        StockRecommendation rec = recommendation("MC.PA", 75, 820.0);
        alertService.sendBuyAlert(rec);
        var mail = mailbox.getMailsSentTo("test@example.com").get(0);
        String body = mail.getText();
        assertNotNull(body);
        assertTrue(body.contains("MC.PA"),  "Body must contain the symbol");
        assertTrue(body.contains("820"),    "Body must contain the price");
        assertTrue(body.contains("75"),     "Body must contain the score");
    }

    @Test
    void sendBuyAlert_emailConfigured_bodyContainsMacdWhenPresent() {
        StockRecommendation rec = recommendation("ASML.AS", 80, 750.0);
        rec.macdHistogram = 1.2345;
        alertService.sendBuyAlert(rec);
        var mail = mailbox.getMailsSentTo("test@example.com").get(0);
        assertTrue(mail.getText().contains("MACD"), "Body must contain MACD when histogram is set");
    }

    @Test
    void sendBuyAlert_emailConfigured_noMacdWhenNull() {
        StockRecommendation rec = recommendation("SAP.DE", 70, 200.0);
        rec.macdHistogram = null;
        assertDoesNotThrow(() -> alertService.sendBuyAlert(rec));
        assertEquals(1, mailbox.getTotalMessagesSent());
    }

    @Test
    void sendBuyAlert_emailConfigured_multipleCalls_allMailsSent() {
        alertService.sendBuyAlert(recommendation("AAPL", 80, 195.0));
        alertService.sendBuyAlert(recommendation("MSFT", 82, 420.0));
        alertService.sendBuyAlert(recommendation("TSLA", 79, 250.0));
        assertEquals(3, mailbox.getTotalMessagesSent());
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private StockRecommendation recommendation(String symbol, int score, double price) {
        StockRecommendation r = new StockRecommendation();
        r.symbol       = symbol;
        r.score        = score;
        r.currentPrice = price;
        r.rsi          = 27.5;
        r.sma20        = price * 0.98;
        r.sma50        = price * 0.95;
        r.reasons      = "RSI oversold; Golden cross; High volume";
        r.timestamp    = LocalDateTime.now();
        return r;
    }
}
