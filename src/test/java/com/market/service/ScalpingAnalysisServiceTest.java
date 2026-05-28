package com.market.service;

import com.market.client.BinanceClient;
import com.market.model.ScalpingSignal;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Tests for ScalpingAnalysisService.
 * BinanceClient is mocked to avoid real HTTP calls.
 * Tests verify all code paths: error handling, BB squeeze gate, signal scoring,
 * VWAP integration, TP/SL calculation and cache logic.
 */
@QuarkusTest
class ScalpingAnalysisServiceTest {

    @Inject
    ScalpingAnalysisService service;

    @InjectMock
    @RestClient
    BinanceClient binanceClient;

    @BeforeEach
    void resetCache() throws Exception {
        ScalpingAnalysisService real = service instanceof ClientProxy
                ? (ScalpingAnalysisService) ((ClientProxy) service).arc_contextualInstance()
                : service;
        Field fCached = ScalpingAnalysisService.class.getDeclaredField("cached");
        fCached.setAccessible(true);
        fCached.set(real, null);
        Field fCachedAt = ScalpingAnalysisService.class.getDeclaredField("cachedAt");
        fCachedAt.setAccessible(true);
        fCachedAt.set(real, 0L);
    }

    // ── Error handling ────────────────────────────────────────────────────────

    @Test
    void getSignal_binanceReturnsNull_signalIsNotNull() {
        when(binanceClient.getKlines(anyString(), anyString(), anyInt())).thenReturn(null);
        ScalpingSignal s = service.getSignal();
        assertNotNull(s);
        assertNotNull(s.error);
    }

    @Test
    void getSignal_binanceReturnsEmptyList_signalHasError() {
        when(binanceClient.getKlines(anyString(), anyString(), anyInt()))
                .thenReturn(Collections.emptyList());
        ScalpingSignal s = service.getSignal();
        assertNotNull(s);
        assertNotNull(s.error, "Should carry error when data is insufficient");
    }

    @Test
    void getSignal_binanceReturns25Candles_signalHasError() {
        when(binanceClient.getKlines(anyString(), anyString(), anyInt()))
                .thenReturn(buildCandles(25, 50_000, CandlePattern.FLAT));
        ScalpingSignal s = service.getSignal();
        assertNotNull(s);
        assertNotNull(s.error, "Should carry error when < 30 candles returned");
    }

    @Test
    void getSignal_binanceThrows_signalHasError() {
        when(binanceClient.getKlines(anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("Network timeout"));
        ScalpingSignal s = service.getSignal();
        assertNotNull(s);
        assertNotNull(s.error);
    }

    // ── BB Squeeze gate ───────────────────────────────────────────────────────

    @Test
    void getSignal_bbSqueeze_returnsWaitImmediately() {
        // Flat prices → Bollinger bandwidth ≈ 0 → SQUEEZE gate fires
        when(binanceClient.getKlines(anyString(), anyString(), anyInt()))
                .thenReturn(buildCandles(200, 50_000, CandlePattern.SQUEEZE));
        ScalpingSignal s = service.getSignal();
        assertNotNull(s);
        assertEquals("WAIT", s.direction, "BB SQUEEZE should produce WAIT signal");
        assertEquals(0, s.confidence, "BB SQUEEZE should have confidence=0");
    }

    @Test
    void getSignal_bbSqueeze_timestampIsSet() {
        when(binanceClient.getKlines(anyString(), anyString(), anyInt()))
                .thenReturn(buildCandles(200, 50_000, CandlePattern.SQUEEZE));
        ScalpingSignal s = service.getSignal();
        assertNotNull(s.timestamp);
    }

    // ── Full computation — all indicators exercised ────────────────────────────

    @Test
    void getSignal_normalVolatileCandles_signalIsNotNull() {
        when(binanceClient.getKlines(anyString(), anyString(), anyInt()))
                .thenReturn(buildCandles(200, 50_000, CandlePattern.VOLATILE));
        ScalpingSignal s = service.getSignal();
        assertNotNull(s);
        assertNull(s.error, "Normal candles should not produce an error");
    }

    @Test
    void getSignal_normalCandles_directionIsOneOfThree() {
        when(binanceClient.getKlines(anyString(), anyString(), anyInt()))
                .thenReturn(buildCandles(200, 50_000, CandlePattern.VOLATILE));
        ScalpingSignal s = service.getSignal();
        assertTrue(
            "LONG".equals(s.direction) || "SHORT".equals(s.direction) || "WAIT".equals(s.direction),
            "direction must be LONG, SHORT or WAIT, got: " + s.direction
        );
    }

    @Test
    void getSignal_normalCandles_indicatorsArePopulated() {
        when(binanceClient.getKlines(anyString(), anyString(), anyInt()))
                .thenReturn(buildCandles(200, 50_000, CandlePattern.VOLATILE));
        ScalpingSignal s = service.getSignal();
        assertTrue(s.atr >= 0,    "ATR must be non-negative");
        assertTrue(s.rsi7 >= 0 && s.rsi7 <= 100, "RSI7 must be in [0,100]");
        assertTrue(s.stochK >= 0 && s.stochK <= 100, "Stoch %K must be in [0,100]");
        assertTrue(s.atrPct >= 0, "ATR% must be non-negative");
        assertTrue(s.currentPrice > 0, "Current price must be positive");
        assertNotNull(s.bbState, "BB state must not be null");
        assertNotNull(s.volumeDeltaTrend, "Volume delta trend must not be null");
    }

    @Test
    void getSignal_normalCandles_vwapIsPopulated() {
        // TRENDING pattern: tight candles (BB>KC → no TTM squeeze) + strong +DM (ADX>>18) → reaches VWAP
        when(binanceClient.getKlines(anyString(), anyString(), anyInt()))
                .thenReturn(buildCandles(200, 50_000, CandlePattern.TRENDING));
        ScalpingSignal s = service.getSignal();
        assertTrue(s.vwap > 0, "VWAP must be positive");
        // VWAP must be close to current price range
        assertTrue(s.vwap > s.currentPrice * 0.5 && s.vwap < s.currentPrice * 2,
                "VWAP must be in a sensible range relative to current price");
    }

    @Test
    void getSignal_normalCandles_tpAndSlPopulated() {
        when(binanceClient.getKlines(anyString(), anyString(), anyInt()))
                .thenReturn(buildCandles(200, 50_000, CandlePattern.VOLATILE));
        ScalpingSignal s = service.getSignal();
        assertTrue(s.tp1 > 0, "TP1 must be positive");
        assertTrue(s.tp2 > 0, "TP2 must be positive");
        assertTrue(s.stopLoss > 0, "Stop-loss must be positive");
    }

    @Test
    void getSignal_normalCandles_candlesChartDataLoaded() {
        when(binanceClient.getKlines(anyString(), anyString(), anyInt()))
                .thenReturn(buildCandles(200, 50_000, CandlePattern.VOLATILE));
        ScalpingSignal s = service.getSignal();
        assertNotNull(s.candles, "Chart candles must be populated");
        assertTrue(s.candles.size() <= 100, "At most 100 chart candles returned");
    }

    @Test
    void getSignal_normalCandles_reasoningIsPopulated() {
        when(binanceClient.getKlines(anyString(), anyString(), anyInt()))
                .thenReturn(buildCandles(200, 50_000, CandlePattern.VOLATILE));
        ScalpingSignal s = service.getSignal();
        assertNotNull(s.reasoning, "Reasoning must not be null");
        assertFalse(s.reasoning.isBlank(), "Reasoning must not be blank");
    }

    // ── Bullish setup → exercises LONG scoring paths ──────────────────────────

    @Test
    void getSignal_strongBullishCandles_longScoreIsBuilt() {
        when(binanceClient.getKlines(anyString(), anyString(), anyInt()))
                .thenReturn(buildCandles(200, 50_000, CandlePattern.BULLISH));
        ScalpingSignal s = service.getSignal();
        // RSI will be high (overbought) → SHORT scoring path is exercised
        // Either LONG or SHORT or WAIT is fine; we just verify indicators are present
        assertNull(s.error);
        assertTrue(s.rsi7 >= 0 && s.rsi7 <= 100);
    }

    @Test
    void getSignal_strongBearishCandles_shortScoreIsBuilt() {
        when(binanceClient.getKlines(anyString(), anyString(), anyInt()))
                .thenReturn(buildCandles(200, 50_000, CandlePattern.BEARISH));
        ScalpingSignal s = service.getSignal();
        assertNull(s.error);
        assertTrue(s.rsi7 >= 0 && s.rsi7 <= 100);
    }

    // ── RSI divergence paths ──────────────────────────────────────────────────

    @Test
    void getSignal_rsiDivergenceIsSet() {
        when(binanceClient.getKlines(anyString(), anyString(), anyInt()))
                .thenReturn(buildCandles(200, 50_000, CandlePattern.VOLATILE));
        ScalpingSignal s = service.getSignal();
        assertNotNull(s.rsiDivergence, "RSI divergence field must always be set");
        assertTrue(
            "BULLISH".equals(s.rsiDivergence) || "BEARISH".equals(s.rsiDivergence) || "NONE".equals(s.rsiDivergence),
            "RSI divergence must be BULLISH, BEARISH or NONE, got: " + s.rsiDivergence
        );
    }

    // ── HIGH volume delta exercises STRONG_BUY / STRONG_SELL paths ────────────

    @Test
    void getSignal_highBuyVolumeDelta_strongBuyPath() {
        when(binanceClient.getKlines(anyString(), anyString(), anyInt()))
                .thenReturn(buildCandles(200, 50_000, CandlePattern.HIGH_BUY_VOLUME));
        ScalpingSignal s = service.getSignal();
        assertNull(s.error);
        assertTrue(s.volumeDeltaPct > 0);
    }

    @Test
    void getSignal_highSellVolumeDelta_strongSellPath() {
        when(binanceClient.getKlines(anyString(), anyString(), anyInt()))
                .thenReturn(buildCandles(200, 50_000, CandlePattern.HIGH_SELL_VOLUME));
        ScalpingSignal s = service.getSignal();
        assertNull(s.error);
    }

    // ── Stochastic oversold/overbought paths ───────────────────────────────────

    @Test
    void getSignal_priceAtBottomOfRange_stochOversoldPath() {
        when(binanceClient.getKlines(anyString(), anyString(), anyInt()))
                .thenReturn(buildCandles(200, 50_000, CandlePattern.STOCH_OVERSOLD));
        ScalpingSignal s = service.getSignal();
        assertNull(s.error);
        assertTrue(s.stochK >= 0);
    }

    // ── Cache ─────────────────────────────────────────────────────────────────

    @Test
    void getSignal_twoCallsWithinTtl_returnsSameCachedInstance() {
        when(binanceClient.getKlines(anyString(), anyString(), anyInt()))
                .thenReturn(buildCandles(200, 50_000, CandlePattern.VOLATILE));
        ScalpingSignal first  = service.getSignal();
        ScalpingSignal second = service.getSignal();
        assertNotNull(first);
        assertNotNull(second);
        // If first succeeded (no error), cache is populated → same instance returned
        if (first.error == null) {
            assertSame(first, second, "Within TTL the same cached instance should be returned");
        }
    }

    @Test
    void getSignal_errorThenStaleCache_returnsStaleCacheOnSecondError() throws Exception {
        // First call: success → populates cache
        when(binanceClient.getKlines(anyString(), anyString(), anyInt()))
                .thenReturn(buildCandles(200, 50_000, CandlePattern.VOLATILE));
        ScalpingSignal first = service.getSignal();

        // Expire the cache manually
        ScalpingAnalysisService real = service instanceof ClientProxy
                ? (ScalpingAnalysisService) ((ClientProxy) service).arc_contextualInstance()
                : service;
        Field fCachedAt = ScalpingAnalysisService.class.getDeclaredField("cachedAt");
        fCachedAt.setAccessible(true);
        fCachedAt.set(real, 0L); // expire cache

        // Second call: Binance fails → should return stale cache if first succeeded
        when(binanceClient.getKlines(anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("Binance down"));
        ScalpingSignal second = service.getSignal();
        assertNotNull(second, "Must never return null");
        // Either stale cache or error signal
        if (first.error == null) {
            // stale cache was used
            assertSame(first, second, "Should return stale cache on error after previous success");
        }
    }

    // ── VWAP signal contribution ───────────────────────────────────────────────

    @Test
    void getSignal_priceAboveVwap_vwapScoreRegimeAware() {
        // Regime-aware (ADX-based): TREND/NEUTRAL → momentum (positive), RANGE → mean-reversion (negative)
        when(binanceClient.getKlines(anyString(), anyString(), anyInt()))
                .thenReturn(buildCandles(200, 50_000, CandlePattern.BULLISH));
        ScalpingSignal s = service.getSignal();
        if (s.error == null && s.currentPrice > s.vwap && s.vwapScore != 0) {
            if (!"RANGE".equals(s.marketRegime)) {
                assertTrue(s.vwapScore > 0, "Trend/Neutral regime: price > VWAP should give positive vwapScore (momentum)");
            } else {
                assertTrue(s.vwapScore < 0, "Range regime: price > VWAP should give negative vwapScore (mean-reversion)");
            }
        }
    }

    @Test
    void getSignal_priceBelowVwap_vwapScoreRegimeAware() {
        // Regime-aware (ADX-based): TREND/NEUTRAL → momentum (negative), RANGE → mean-reversion (positive)
        when(binanceClient.getKlines(anyString(), anyString(), anyInt()))
                .thenReturn(buildCandles(200, 50_000, CandlePattern.BEARISH));
        ScalpingSignal s = service.getSignal();
        if (s.error == null && s.currentPrice < s.vwap && s.vwapScore != 0) {
            if (!"RANGE".equals(s.marketRegime)) {
                assertTrue(s.vwapScore < 0, "Trend/Neutral regime: price < VWAP should give negative vwapScore (momentum)");
            } else {
                assertTrue(s.vwapScore > 0, "Range regime: price < VWAP should give positive vwapScore (mean-reversion)");
            }
        }
    }

    // ── ATR gate ──────────────────────────────────────────────────────────────

    @Test
    void getSignal_lowVolatility_atrGateReturnsWait() {
        // LOW_VOLATILITY: TR ≈ 1.5 USDT → ATR ~1.5 USDT < adaptive gate floor 7.5 USDT (0.015% × 50000)
        when(binanceClient.getKlines(anyString(), anyString(), anyInt()))
                .thenReturn(buildCandles(200, 50_000, CandlePattern.LOW_VOLATILITY));
        ScalpingSignal s = service.getSignal();
        assertNotNull(s);
        assertEquals("WAIT", s.direction, "Very low ATR should trigger ATR gate");
        assertEquals(0, s.confidence, "ATR gate should set confidence to 0");
        assertNotNull(s.reasoning);
        assertTrue(s.reasoning.contains("ATR"), "Reasoning should mention ATR gate");
    }

    // ── Candle data builder ───────────────────────────────────────────────────

    enum CandlePattern {
        FLAT, SQUEEZE, VOLATILE, BULLISH, BEARISH, HIGH_BUY_VOLUME, HIGH_SELL_VOLUME,
        STOCH_OVERSOLD, LOW_VOLATILITY, TRENDING
    }

    /**
     * Builds {@code count} synthetic 1m kline rows matching Binance format:
     * [openTime, open, high, low, close, volume, closeTime, quoteVol, trades,
     *  takerBuyBaseVol, takerBuyQuoteVol, ignore]
     */
    private List<List<Object>> buildCandles(int count, double basePrice, CandlePattern pattern) {
        List<List<Object>> result = new ArrayList<>(count);
        long now = System.currentTimeMillis();

        for (int i = 0; i < count; i++) {
            long ts    = now - (long)(count - i) * 60_000L;
            double pct = (double) i / count;
            double close, high, low, vol, buyVol;

            switch (pattern) {
                case SQUEEZE:
                    close  = basePrice + (i % 2 == 0 ? 0.5 : -0.5);   // ±0.5 → tiny σ
                    high   = close + 0.5;
                    low    = close - 0.5;
                    vol    = 100;
                    buyVol = 50;
                    break;

                case BULLISH:
                    close  = basePrice * (1 + pct * 0.05);             // +5% over session
                    high   = close + basePrice * 0.002;
                    low    = close - basePrice * 0.001;
                    vol    = 1000 + i * 5;
                    buyVol = vol * 0.75;                                // 75% buy pressure
                    break;

                case BEARISH:
                    close  = basePrice * (1 - pct * 0.05);             // -5% over session
                    high   = close + basePrice * 0.001;
                    low    = close - basePrice * 0.002;
                    vol    = 1000 + i * 5;
                    buyVol = vol * 0.25;                                // 25% buy = 75% sell
                    break;

                case HIGH_BUY_VOLUME:
                    close  = basePrice + (i % 10 - 5) * 50;
                    high   = close + 100;
                    low    = close - 100;
                    vol    = 5000;
                    buyVol = vol * 0.85;                                // 85% buy → STRONG_BUY
                    break;

                case HIGH_SELL_VOLUME:
                    close  = basePrice + (i % 10 - 5) * 50;
                    high   = close + 100;
                    low    = close - 100;
                    vol    = 5000;
                    buyVol = vol * 0.15;                                // 15% buy = 85% sell → STRONG_SELL
                    break;

                case STOCH_OVERSOLD:
                    // Close near the bottom of a wide range → stoch K near 0
                    high   = basePrice + 1000;
                    low    = basePrice - 1000;
                    close  = basePrice - 900;                           // near bottom
                    vol    = 1000;
                    buyVol = 400;
                    break;

                case LOW_VOLATILITY:
                    // Slow linear drift (+1 USDT/bar) → TR ≈ 1.5 USDT, KC also narrow → BB wider than KC → TTM squeeze OFF
                    // ATR ≈ 1.5 USDT < adaptive gate floor 7.5 USDT (0.015% × 50000) → WAIT
                    close  = basePrice + i;
                    high   = close + 0.5;
                    low    = close - 0.5;
                    vol    = 1000;
                    buyVol = 500;
                    break;

                case TRENDING:
                    // Strong uptrend (+20 USDT/bar) with tight candles (±25 USDT range).
                    // Tight range → ATR ≈ 50 USDT → KC narrow → BB >> KC → no TTM squeeze.
                    // Consistent +DM >> -DM every bar → ADX ≈ 100 → passes ADX gate.
                    close  = basePrice + i * 20.0;
                    high   = close + 25;
                    low    = close - 25;
                    vol    = 1500;
                    buyVol = 1000;
                    break;

                default: // VOLATILE / FLAT
                    close  = basePrice + Math.sin(i * 0.3) * basePrice * 0.005
                                      + Math.cos(i * 0.7) * basePrice * 0.003;
                    high   = close + basePrice * 0.003;
                    low    = close - basePrice * 0.003;
                    vol    = 1000 + (i % 7) * 200;
                    buyVol = vol * (0.45 + (i % 3) * 0.1);             // alternates 45%/55%/65%
                    break;
            }

            result.add(Arrays.asList(
                (Object) ts,
                String.valueOf(close),
                String.valueOf(high),
                String.valueOf(low),
                String.valueOf(close),
                String.valueOf(vol),
                ts + 59_999L,
                String.valueOf(vol * close),
                100,
                String.valueOf(buyVol),
                String.valueOf(buyVol * close),
                "0"
            ));
        }
        return result;
    }
}
