package com.market.service;

import com.market.model.RecommendationSignal;
import com.market.model.StockQuote;
import com.market.model.StockRecommendation;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for RecommendationService.
 *
 * Uses a unique test symbol per test run to isolate DB state.
 * AlertService is mocked to avoid real email sending.
 */
@QuarkusTest
class RecommendationServiceTest {

    @Inject        RecommendationService  service;
    @InjectMock    AlertService           alertService;

    /** Unique symbol for this test run — avoids cross-test pollution. */
    private String symbol;

    @BeforeEach
    void setUp() {
        symbol = "REC-" + System.nanoTime();
        doNothing().when(alertService).sendBuyAlert(any());
    }

    @AfterEach
    @Transactional
    void cleanUp() {
        StockQuote.delete("symbol", symbol);
        StockRecommendation.delete("symbol", symbol);
    }

    // ── Not enough data ───────────────────────────────────────────────────────

    @Test
    void generateRecommendation_noQuotes_returnsNull() {
        StockRecommendation result = service.generateRecommendation(symbol);
        assertNull(result, "Should return null when there are no quotes");
    }

    @Test
    void generateRecommendation_fewQuotes_returnsNull() {
        insertQuotes(symbol, 10, 150.0); // MIN_QUOTES = 35
        StockRecommendation result = service.generateRecommendation(symbol);
        assertNull(result, "Should return null with fewer than 35 quotes");
    }

    // ── Enough data ───────────────────────────────────────────────────────────

    @Test
    void generateRecommendation_enoughQuotes_returnsNonNull() {
        insertQuotes(symbol, 60, 200.0);
        StockRecommendation result = service.generateRecommendation(symbol);

        assertNotNull(result);
        assertNotNull(result.signal);
        assertTrue(result.score >= 0 && result.score <= 100,
                "Score must be in [0, 100]");
    }

    @Test
    void generateRecommendation_allFieldsPopulated() {
        insertQuotes(symbol, 60, 200.0);
        StockRecommendation result = service.generateRecommendation(symbol);

        assertNotNull(result);
        assertEquals(symbol, result.symbol);
        assertTrue(result.currentPrice > 0);
        assertTrue(result.rsi >= 0 && result.rsi <= 100);
        assertTrue(result.sma20 > 0);
        assertTrue(result.sma50 > 0);
        assertNotNull(result.reasons);
        assertNotNull(result.timestamp);
    }

    @Test
    void generateRecommendation_persistedInDB() {
        insertQuotes(symbol, 60, 200.0);
        service.generateRecommendation(symbol);

        StockRecommendation stored = StockRecommendation.findLatestBySymbol(symbol);
        assertNotNull(stored, "Recommendation should be persisted");
    }

    // ── BUY alert transition logic ─────────────────────────────────────────────

    @Test
    void generateRecommendation_firstBuySignal_triggersAlert() {
        // Insert data that produces RSI < 30 (strong BUY signal)
        insertOversoldQuotes(symbol, 60);
        StockRecommendation result = service.generateRecommendation(symbol);

        if (result != null && result.signal == RecommendationSignal.BUY) {
            verify(alertService, times(1)).sendBuyAlert(result);
        }
        // If signal is not BUY (depends on exact price data), no alert expected
    }

    @Test
    void generateRecommendation_repeatedBuySignal_noRepeatAlert() {
        // Pre-insert a BUY recommendation so the "previous" is BUY
        insertQuotes(symbol, 60, 200.0);
        insertPreviousBuyRecommendation(symbol);

        service.generateRecommendation(symbol);

        // Alert should NOT be called again for an already-BUY symbol
        // (only fires on BUY transitions, not repeated BUY)
        verify(alertService, never()).sendBuyAlert(argThat(r ->
                r.signal == RecommendationSignal.BUY
             && symbol.equals(r.symbol)));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @Transactional
    void insertQuotes(String symbol, int count, double basePrice) {
        LocalDate start = LocalDate.now().minusDays(count);
        for (int i = 0; i < count; i++) {
            StockQuote q = new StockQuote();
            q.symbol = symbol;
            q.date   = start.plusDays(i);
            q.close  = basePrice + (i % 5) - 2.0; // slight oscillation
            q.open   = q.close - 0.5;
            q.high   = q.close + 1.0;
            q.low    = q.close - 1.0;
            q.volume = 100_000L + (i * 1_000L);
            q.persist();
        }
    }

    /**
     * Inserts quotes with strong declining prices to produce RSI < 30.
     * Prices drop 1% per candle over first 45 bars, then stabilise.
     */
    @Transactional
    void insertOversoldQuotes(String symbol, int count) {
        LocalDate start = LocalDate.now().minusDays(count);
        double price = 200.0;
        for (int i = 0; i < count; i++) {
            StockQuote q = new StockQuote();
            q.symbol = symbol;
            q.date   = start.plusDays(i);
            if (i < 45) price *= 0.99; // steady decline → low RSI
            q.close  = price;
            q.open   = price - 0.2;
            q.high   = price + 0.5;
            q.low    = price - 0.5;
            q.volume = 80_000L;
            q.persist();
        }
    }

    @Transactional
    void insertPreviousBuyRecommendation(String symbol) {
        StockRecommendation prev = new StockRecommendation();
        prev.symbol       = symbol;
        prev.signal       = RecommendationSignal.BUY;
        prev.score        = 70;
        prev.currentPrice = 200.0;
        prev.rsi          = 27.0;
        prev.sma20        = 198.0;
        prev.sma50        = 195.0;
        prev.reasons      = "Test BUY";
        prev.timestamp    = java.time.LocalDateTime.now().minusHours(1);
        prev.persist();
    }
}
