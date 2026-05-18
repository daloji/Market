package com.market.service;

import com.market.model.StockQuote;
import com.market.provider.DailyQuote;
import com.market.provider.StockDataAggregator;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Tests for StockDataService — verifies quote persistence behaviour.
 * StockDataAggregator (external HTTP) is mocked.
 */
@QuarkusTest
class StockDataServiceTest {

    @Inject     StockDataService     service;
    @InjectMock StockDataAggregator  aggregator;

    private static final String SYM = "TESTDATA-" + System.nanoTime();

    @AfterEach
    @Transactional
    void cleanUp() {
        StockQuote.delete("symbol", SYM);
    }

    // ── Empty source ──────────────────────────────────────────────────────────

    @Test
    void fetchAndStoreQuotes_emptySource_nothingPersisted() {
        when(aggregator.fetchQuotes(SYM, 60)).thenReturn(List.of());

        service.fetchAndStoreQuotes(SYM);

        assertEquals(0L, StockQuote.count("symbol", SYM),
                "No quotes should be persisted when source returns empty list");
    }

    // ── Null close skipped ────────────────────────────────────────────────────

    @Test
    void fetchAndStoreQuotes_nullClose_skipped() {
        DailyQuote withClose = quote(LocalDate.now().minusDays(2), 100.0);
        DailyQuote nullClose = new DailyQuote(LocalDate.now().minusDays(1),
                99.0, 101.0, 98.0, null, 10_000L);  // close = null

        when(aggregator.fetchQuotes(SYM, 60)).thenReturn(List.of(withClose, nullClose));

        service.fetchAndStoreQuotes(SYM);

        // Only the one with non-null close is saved
        assertEquals(1L, StockQuote.count("symbol", SYM));
    }

    // ── Normal persistence ────────────────────────────────────────────────────

    @Test
    void fetchAndStoreQuotes_validQuotes_allPersisted() {
        List<DailyQuote> quotes = List.of(
                quote(LocalDate.now().minusDays(3), 100.0),
                quote(LocalDate.now().minusDays(2), 101.5),
                quote(LocalDate.now().minusDays(1), 103.0)
        );
        when(aggregator.fetchQuotes(SYM, 60)).thenReturn(quotes);

        service.fetchAndStoreQuotes(SYM);

        assertEquals(3L, StockQuote.count("symbol", SYM));
    }

    @Test
    void fetchAndStoreQuotes_persistsAllOHLCV() {
        LocalDate today = LocalDate.now().minusDays(1);
        DailyQuote q = new DailyQuote(today, 98.0, 105.0, 97.0, 102.5, 500_000L);
        when(aggregator.fetchQuotes(SYM, 60)).thenReturn(List.of(q));

        service.fetchAndStoreQuotes(SYM);

        StockQuote stored = StockQuote.findBySymbolAndDate(SYM, today);
        assertNotNull(stored);
        assertEquals(98.0,    stored.open);
        assertEquals(105.0,   stored.high);
        assertEquals(97.0,    stored.low);
        assertEquals(102.5,   stored.close);
        assertEquals(500_000L, stored.volume);
    }

    @Test
    void fetchAndStoreQuotes_upsertSameDate_updatesFields() {
        LocalDate date = LocalDate.now().minusDays(1);
        when(aggregator.fetchQuotes(SYM, 60)).thenReturn(List.of(quote(date, 100.0)));
        service.fetchAndStoreQuotes(SYM); // first insert

        // Second call with updated price
        when(aggregator.fetchQuotes(SYM, 60)).thenReturn(List.of(quote(date, 105.0)));
        service.fetchAndStoreQuotes(SYM); // should upsert, not duplicate

        assertEquals(1L, StockQuote.count("symbol", SYM),
                "Upsert should not create a duplicate for the same symbol+date");
        StockQuote stored = StockQuote.findBySymbolAndDate(SYM, date);
        assertEquals(105.0, stored.close, "Close price should be updated on upsert");
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private DailyQuote quote(LocalDate date, double close) {
        return new DailyQuote(date, close - 1.0, close + 2.0, close - 2.0, close, 100_000L);
    }
}
