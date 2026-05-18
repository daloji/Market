package com.market.service;

import com.market.model.BitcoinSignal;
import com.market.model.BitcoinSignalHistory;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SignalHistoryService — verifies what gets persisted and what is filtered out.
 */
@QuarkusTest
class SignalHistoryServiceTest {

    @Inject SignalHistoryService service;

    /** Unique prefix per test run to isolate DB records. */
    private String sym;

    @BeforeEach
    void uniqueSymbol() {
        sym = "TEST-" + System.nanoTime();
    }

    // ── Filtering (should NOT persist) ────────────────────────────────────────

    @Test
    void record_nullSignal_doesNotPersist() {
        assertDoesNotThrow(() -> service.record(null, false, null));
        // nothing to query — just verifying no exception
    }

    @Test
    void record_signalWithError_doesNotPersist() {
        BitcoinSignal s = new BitcoinSignal();
        s.direction    = "LONG";
        s.currentPrice = 99_001.0; // unique price to identify this test's records
        s.error        = "API timeout";
        service.record(s, false, null);

        // Verify no record was persisted with this specific price
        boolean found = BitcoinSignalHistory.<BitcoinSignalHistory>listAll().stream()
                .anyMatch(h -> h.price == 99_001.0);
        assertFalse(found, "Signal with error field set must not be persisted");
    }

    @Test
    void record_waitSignal_doesNotPersist() {
        BitcoinSignal s = signal("WAIT", 45);
        s.currentPrice = 99_002.0; // unique price
        service.record(s, false, null);

        boolean found = BitcoinSignalHistory.<BitcoinSignalHistory>listAll().stream()
                .anyMatch(h -> h.price == 99_002.0);
        assertFalse(found, "WAIT signals must not be persisted");
    }

    // ── Persistence (should persist) ─────────────────────────────────────────

    @Test
    void record_longSignal_persistsWithAllFields() {
        BitcoinSignal s = signal("LONG", 72);
        s.rsi           = 27.5;
        s.ema9          = 94_980.0;
        s.ema21         = 94_800.0;
        s.macdLine      = 12.5;
        s.macdSignal    = 10.0;
        s.macdHistogram = 2.5;
        s.stochK        = 18.0;
        s.stochD        = 22.0;
        s.atr           = 150.0;
        s.adx           = 32.0;
        s.plusDI        = 28.0;
        s.minusDI       = 15.0;
        s.bollingerUpper = 95_800.0;
        s.bollingerMid   = 95_000.0;
        s.bollingerLower = 94_200.0;
        s.bollingerPosition = 0.5;
        s.reasoning      = "Test LONG reasoning";

        service.record(s, true, null);

        List<BitcoinSignalHistory> rows = BitcoinSignalHistory.findByDirection("LONG", 200);
        BitcoinSignalHistory h = rows.stream()
                .filter(r -> r.price == s.currentPrice && r.reasoning != null && r.reasoning.contains("Test LONG"))
                .findFirst().orElse(null);

        assertNotNull(h, "LONG signal should be persisted");
        assertEquals("LONG",   h.direction);
        assertEquals(72,       h.confidence);
        assertEquals(95_000.0, h.price);
        assertEquals(27.5,     h.rsi);
        assertEquals(94_980.0, h.ema9);
        assertEquals(94_800.0, h.ema21);
        assertEquals(12.5,     h.macdLine);
        assertEquals(2.5,      h.macdHistogram);
        assertEquals(18.0,     h.stochK);
        assertEquals(150.0,    h.atr);
        assertTrue(h.tradeTriggered, "tradeTriggered should be true");
        assertNull(h.skipReason);
        assertEquals("Test LONG reasoning", h.reasoning);
    }

    @Test
    void record_shortSignal_persistsCorrectly() {
        BitcoinSignal s = signal("SHORT", 68);
        s.reasoning = "Test SHORT";
        service.record(s, false, "Already in position");

        List<BitcoinSignalHistory> rows = BitcoinSignalHistory.findByDirection("SHORT", 200);
        BitcoinSignalHistory h = rows.stream()
                .filter(r -> r.price == s.currentPrice && "Test SHORT".equals(r.reasoning))
                .findFirst().orElse(null);

        assertNotNull(h, "SHORT signal should be persisted");
        assertEquals("SHORT", h.direction);
        assertFalse(h.tradeTriggered);
        assertEquals("Already in position", h.skipReason);
    }

    @Test
    void record_longReasoningTruncatedAt1000() {
        BitcoinSignal s = signal("LONG", 75);
        s.reasoning = "R".repeat(1500);
        s.currentPrice = 95_001.0;
        service.record(s, false, null);

        List<BitcoinSignalHistory> rows = BitcoinSignalHistory.findByDirection("LONG", 200);
        BitcoinSignalHistory h = rows.stream()
                .filter(r -> r.price == 95_001.0)
                .findFirst().orElse(null);

        assertNotNull(h);
        assertEquals(1000, h.reasoning.length(), "Reasoning must be capped at 1000 chars");
    }

    @Test
    void record_longSkipReasonTruncatedAt500() {
        BitcoinSignal s = signal("SHORT", 65);
        s.currentPrice = 95_002.0;
        service.record(s, false, "X".repeat(700));

        List<BitcoinSignalHistory> rows = BitcoinSignalHistory.findByDirection("SHORT", 200);
        BitcoinSignalHistory h = rows.stream()
                .filter(r -> r.price == 95_002.0)
                .findFirst().orElse(null);

        assertNotNull(h);
        assertEquals(500, h.skipReason.length(), "skipReason must be capped at 500 chars");
    }

    @Test
    void record_detectedAtSetToNow() {
        BitcoinSignal s = signal("LONG", 70);
        s.currentPrice = 95_003.0;
        long before = System.currentTimeMillis() / 1000;
        service.record(s, true, null);
        long after = System.currentTimeMillis() / 1000 + 1;

        List<BitcoinSignalHistory> rows = BitcoinSignalHistory.findByDirection("LONG", 200);
        BitcoinSignalHistory h = rows.stream()
                .filter(r -> r.price == 95_003.0)
                .findFirst().orElse(null);

        assertNotNull(h);
        long epochSec = h.detectedAt.getEpochSecond();
        assertTrue(epochSec >= before && epochSec <= after,
                "detectedAt should be set to approximately now");
    }

    private BitcoinSignal signal(String direction, int confidence) {
        BitcoinSignal s = new BitcoinSignal();
        s.direction    = direction;
        s.confidence   = confidence;
        s.currentPrice = 95_000.0;
        return s;
    }
}
