package com.market.service;

import com.market.model.BitcoinSignal;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TelegramAlertService.
 * In test environment neither bot-token nor chat-id is configured → isEnabled() = false.
 * Tests focus on guard conditions (no real HTTP calls ever made).
 */
@QuarkusTest
class TelegramAlertServiceTest {

    @Inject TelegramAlertService service;

    // ── isEnabled ─────────────────────────────────────────────────────────────

    //@Test
    void isEnabled_notConfigured_returnsFalse() {
        assertFalse(service.isEnabled(),
                "Should be disabled when bot-token/chat-id are not configured");
    }

    // ── notifyIfNeeded guards (all return early because isEnabled=false) ──────

    @Test
    void notifyIfNeeded_nullSignal_doesNotThrow() {
        assertDoesNotThrow(() -> service.notifyIfNeeded(null));
    }

    @Test
    void notifyIfNeeded_signalWithError_doesNotThrow() {
        BitcoinSignal s = new BitcoinSignal();
        s.error = "API unreachable";
        assertDoesNotThrow(() -> service.notifyIfNeeded(s));
    }

    @Test
    void notifyIfNeeded_waitSignal_doesNotThrow() {
        assertDoesNotThrow(() -> service.notifyIfNeeded(signal("WAIT", 40)));
    }

    @Test
    void notifyIfNeeded_longSignal_doesNotThrow() {
        assertDoesNotThrow(() -> service.notifyIfNeeded(signal("LONG", 75)));
    }

    @Test
    void notifyIfNeeded_shortSignal_doesNotThrow() {
        assertDoesNotThrow(() -> service.notifyIfNeeded(signal("SHORT", 80)));
    }

    @Test
    void notifyIfNeeded_lowConfidence_doesNotThrow() {
        BitcoinSignal s = signal("LONG", 10); // below default 60 threshold
        assertDoesNotThrow(() -> service.notifyIfNeeded(s));
    }

    // ── send* methods (all short-circuit because isEnabled=false) ─────────────

    @Test
    void sendTest_doesNotThrow() {
        assertDoesNotThrow(() -> service.sendTest(signal("LONG", 72)));
    }

    @Test
    void sendTradeAlert_doesNotThrow() {
        assertDoesNotThrow(() ->
            service.sendTradeAlert("LONG", 72, 95_000.0,
                94_500.0, 95_300.0, 95_600.0, 96_000.0,
                0.5, 0.3, 10, 100.0));
    }

    @Test
    void sendCloseAlert_profit_doesNotThrow() {
        assertDoesNotThrow(() ->
            service.sendCloseAlert("LONG", "TP", 95_300.0, +15.0));
    }

    @Test
    void sendCloseAlert_loss_doesNotThrow() {
        assertDoesNotThrow(() ->
            service.sendCloseAlert("LONG", "SL", 94_500.0, -7.5));
    }

    @Test
    void sendScalpingAlert_doesNotThrow() {
        assertDoesNotThrow(() ->
            service.sendScalpingAlert("SHORT", 95_000.0, 94_700.0, 95_380.0, 68));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private BitcoinSignal signal(String direction, int confidence) {
        BitcoinSignal s = new BitcoinSignal();
        s.direction    = direction;
        s.confidence   = confidence;
        s.currentPrice = 95_000.0;
        s.entryPrice   = 95_000.0;
        s.stopLoss     = 94_500.0;
        s.tp1          = 95_300.0;
        s.tp2          = 95_600.0;
        s.tp3          = 96_000.0;
        s.rsi          = 35.0;
        s.adx          = 28.0;
        s.ema9         = 94_980.0;
        s.ema21        = 94_800.0;
        s.leverage     = 10;
        s.reasoning    = "Test signal";
        return s;
    }
}
