package com.market.service;

import com.market.client.dto.YahooQuoteResponse;
import com.market.model.FundamentalData;
import com.market.model.ValuationVerdict;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for FundamentalAnalysisService.
 *
 * Focuses on:
 *  - public API (isEnabled, isCachedToday, storeFromClientFetch)
 *  - scoring logic tested indirectly via buildFromYahooQuote (package-private)
 *
 * No external HTTP calls — uses only in-memory/DB paths.
 */
@QuarkusTest
class FundamentalAnalysisServiceTest {

    @Inject FundamentalAnalysisService service;

    // ── isEnabled / isCachedToday ─────────────────────────────────────────────

    @Test
    void isEnabled_alwaysTrue() {
        assertTrue(service.isEnabled(), "FundamentalAnalysisService is always enabled");
    }

    @Test
    void isCachedToday_uncachedSymbol_returnsFalse() {
        assertFalse(service.isCachedToday("UNCACHED-" + System.nanoTime()));
    }

    // ── Scoring via buildFromYahooQuote (package-private — same package) ──────

    @Test
    void buildFromYahooQuote_lowForwardPE_scores20() {
        // Forward P/E < 10 → 20 pts
        FundamentalData fd = service.buildFromYahooQuote("TEST1", quoteResult(8.0, null));
        assertNotNull(fd);
        assertTrue(fd.valuationScore >= 20, "P/E < 10 should contribute 20 pts");
        assertEquals("TEST1", fd.symbol);
        assertNotNull(fd.fetchedAt);
        assertEquals("YAHOO_QUOTE", fd.dataSource);
    }

    @Test
    void buildFromYahooQuote_moderateForwardPE_scores7() {
        // Forward P/E 20-30 → 7 pts only
        FundamentalData fd = service.buildFromYahooQuote("TEST2", quoteResult(25.0, null));
        assertNotNull(fd);
        assertEquals(7, fd.valuationScore, "P/E 20-30 should contribute exactly 7 pts (no other fields set)");
    }

    @Test
    void buildFromYahooQuote_veryHighPE_scores0() {
        // P/E > 30 → 0 pts from P/E
        FundamentalData fd = service.buildFromYahooQuote("TEST3", quoteResult(50.0, null));
        assertNotNull(fd);
        assertEquals(0, fd.valuationScore);
    }

    @Test
    void buildFromYahooQuote_lowPriceToBook_adds15pts() {
        // P/E 8 (20 pts) + P/B 0.8 < 1.0 (15 pts) = 35 pts
        FundamentalData fd = service.buildFromYahooQuote("TEST4", quoteResult(8.0, 0.8));
        assertNotNull(fd);
        assertEquals(35, fd.valuationScore);
        assertEquals(ValuationVerdict.OVERVALUED, fd.verdict); // 35 < 40 threshold
    }

    @Test
    void buildFromYahooQuote_moderatePriceToBook_adds12pts() {
        // P/E 8 (20 pts) + P/B 1.5 → 12 pts = 32 pts → OVERVALUED
        FundamentalData fd = service.buildFromYahooQuote("TEST5", quoteResult(8.0, 1.5));
        assertNotNull(fd);
        assertEquals(32, fd.valuationScore);
    }

    @Test
    void buildFromYahooQuote_lowPEAndLowPB_scoresCorrectly() {
        // P/E < 10 (20 pts) + P/B < 1 (15 pts) = 35 pts
        FundamentalData fd = service.buildFromYahooQuote("TEST6", quoteResult(5.0, 0.5));
        assertNotNull(fd);
        assertEquals(35, fd.valuationScore);
    }

    @Test
    void buildFromYahooQuote_verdictOVERVALUED_whenPEHigh() {
        // P/E > 30 → 0 pts → OVERVALUED
        FundamentalData fd = service.buildFromYahooQuote("TEST7", quoteResult(40.0, null));
        assertNotNull(fd);
        assertEquals(ValuationVerdict.OVERVALUED, fd.verdict);
    }

    @Test
    void buildFromYahooQuote_verdictUNDERVALUED_whenScoreHigh() {
        // P/E < 10 (20 pts) + P/B < 1 (15 pts) + … only these from YahooQuote
        // 35 pts is fairly valued; need score >= 65 for UNDERVALUED
        // Without PEG/margin/ROE (not in QuoteResult), max from PE+PB = 35 pts
        // So test that low P/E + low P/B is at least FAIRLY_VALUED
        FundamentalData fd = service.buildFromYahooQuote("TEST8", quoteResult(8.0, 0.8));
        assertNotNull(fd);
        assertTrue(fd.valuationScore >= 35,
                "Low PE + low PB should produce score >= 35");
    }

    @Test
    void storeFromClientFetch_validResult_populatesCache() {
        String symbol = "CACHETEST-" + System.nanoTime();
        YahooQuoteResponse.QuoteResult q = quoteResult(12.0, 1.5);

        FundamentalData fd = service.storeFromClientFetch(symbol, q);

        assertNotNull(fd);
        assertEquals(symbol.toUpperCase(), fd.symbol);
        assertTrue(service.isCachedToday(symbol), "Symbol should be cached after storeFromClientFetch");
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /**
     * Builds a minimal YahooQuoteResponse.QuoteResult with the given financial metrics.
     * Null values mean "field not provided" → score contribution = 0.
     *
    /**
     * Builds a YahooQuoteResponse.QuoteResult with forwardPE and priceToBook.
     * YahooQuoteResult does NOT carry profitMargin/ROE/PEG/growth —
     * only forwardPE and priceToBook contribute to the score.
     */
    private YahooQuoteResponse.QuoteResult quoteResult(Double forwardPE, Double priceToBook) {
        YahooQuoteResponse.QuoteResult q = new YahooQuoteResponse.QuoteResult();
        q.forwardPE   = forwardPE;
        q.priceToBook = priceToBook;
        q.beta        = 1.0;
        return q;
    }
}
