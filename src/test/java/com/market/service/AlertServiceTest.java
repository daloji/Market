package com.market.service;

import com.market.model.StockRecommendation;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AlertService with no email configured (default test config).
 * In test environment market.alert.email is empty → alerts are silently skipped.
 */
@QuarkusTest
class AlertServiceTest {

    @Inject AlertService alertService;
    @Inject MockMailbox  mailbox;

    @BeforeEach
    void clearMailbox() {
        mailbox.clear();
    }

    // ── No-email guard ────────────────────────────────────────────────────────

    @Test
    void sendBuyAlert_noEmailConfigured_noMailSent() {
        StockRecommendation rec = recommendation("AAPL", 80, 195.50);
        alertService.sendBuyAlert(rec);

        assertTrue(mailbox.getTotalMessagesSent() == 0,
                "No mail should be sent when alert email is not configured");
    }

    @Test
    void sendBuyAlert_noEmailConfigured_doesNotThrow() {
        StockRecommendation rec = recommendation("MC.PA", 75, 820.0);
        assertDoesNotThrow(() -> alertService.sendBuyAlert(rec));
    }

    @Test
    void sendBuyAlert_recWithNullMacd_noMailSent() {
        StockRecommendation rec = recommendation("TSLA", 70, 250.0);
        rec.macdHistogram = null;  // optional field — must not NPE
        assertDoesNotThrow(() -> alertService.sendBuyAlert(rec));
        assertEquals(0, mailbox.getTotalMessagesSent());
    }

    @Test
    void sendBuyAlert_calledMultipleTimes_noMailSent() {
        for (int i = 0; i < 5; i++) {
            alertService.sendBuyAlert(recommendation("AAPL", 80 + i, 100.0 + i));
        }
        assertEquals(0, mailbox.getTotalMessagesSent());
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private StockRecommendation recommendation(String symbol, int score, double price) {
        StockRecommendation r = new StockRecommendation();
        r.symbol       = symbol;
        r.score        = score;
        r.currentPrice = price;
        r.rsi          = 28.5;
        r.sma20        = price * 0.98;
        r.sma50        = price * 0.95;
        r.reasons      = "RSI oversold; Uptrend";
        return r;
    }
}
