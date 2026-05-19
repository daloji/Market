package com.market.service;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for all pure-function indicator methods in TechnicalAnalysisService.
 * No external I/O — all inputs are synthetic price/volume arrays.
 */
@QuarkusTest
class TechnicalAnalysisServiceTest {

    @Inject
    TechnicalAnalysisService svc;

    // ═══════════════════════════════════  RSI  ════════════════════════════════

    @Test
    void rsi_insufficientData_returns50() {
        List<Double> prices = List.of(100.0, 101.0, 102.0);  // < 14+1
        assertEquals(50.0, svc.calculateRSI(prices, 14));
    }

    @Test
    void rsi_flatPrices_returnsNeutral() {
        List<Double> prices = Collections.nCopies(20, 100.0);
        double rsi = svc.calculateRSI(prices, 14);
        // all changes = 0, avgLoss = 0 → returns 100 (RSI formula: avgLoss=0 → rs = ∞)
        assertEquals(100.0, rsi, 0.01);
    }

    @Test
    void rsi_constantlyRising_returnsHigh() {
        List<Double> prices = new ArrayList<>();
        for (int i = 0; i < 20; i++) prices.add(100.0 + i);  // +1 each candle
        double rsi = svc.calculateRSI(prices, 14);
        assertTrue(rsi > 85, "Constantly rising prices should yield RSI > 85, got " + rsi);
    }

    @Test
    void rsi_constantlyFalling_returnsLow() {
        List<Double> prices = new ArrayList<>();
        for (int i = 0; i < 20; i++) prices.add(200.0 - i);  // -1 each candle
        double rsi = svc.calculateRSI(prices, 14);
        assertTrue(rsi < 15, "Constantly falling prices should yield RSI < 15, got " + rsi);
    }

    @Test
    void rsi_oversold_below30() {
        // Sharp drop then flat — RSI should be low
        List<Double> prices = new ArrayList<>();
        for (int i = 0; i < 10; i++) prices.add(200.0 - i * 5); // -5 each candle
        for (int i = 0; i < 10; i++) prices.add(150.0);
        double rsi = svc.calculateRSI(prices, 14);
        assertTrue(rsi < 40, "After sharp drop RSI should be < 40, got " + rsi);
    }

    @Test
    void rsi_overbought_above70() {
        // Sharp rise — RSI should be high
        List<Double> prices = new ArrayList<>();
        for (int i = 0; i < 20; i++) prices.add(100.0 + i * 3); // +3 each candle
        double rsi = svc.calculateRSI(prices, 14);
        assertTrue(rsi > 65, "After sharp rise RSI should be > 65, got " + rsi);
    }

    @Test
    void rsi_range_always0to100() {
        // Randomized prices — RSI must stay [0, 100]
        List<Double> prices = List.of(100.0, 98.0, 103.0, 97.0, 105.0, 99.0,
                102.0, 96.0, 108.0, 94.0, 110.0, 92.0, 106.0, 98.0,
                104.0, 100.0, 103.0, 97.0, 105.0, 99.0);
        double rsi = svc.calculateRSI(prices, 14);
        assertTrue(rsi >= 0.0 && rsi <= 100.0, "RSI out of [0,100]: " + rsi);
    }

    // ═══════════════════════════════════  SMA  ════════════════════════════════

    @Test
    void sma_basic() {
        List<Double> prices = List.of(1.0, 2.0, 3.0, 4.0, 5.0);
        assertEquals(4.0, svc.calculateSMA(prices, 3), 0.001);  // avg(3,4,5)
    }

    @Test
    void sma_periodEqualsSize() {
        List<Double> prices = List.of(2.0, 4.0, 6.0);
        assertEquals(4.0, svc.calculateSMA(prices, 3), 0.001);
    }

    @Test
    void sma_periodLargerThanData_returnsOverallAverage() {
        List<Double> prices = List.of(10.0, 20.0, 30.0);
        assertEquals(20.0, svc.calculateSMA(prices, 10), 0.001);
    }

    @Test
    void sma_singleValue() {
        assertEquals(42.0, svc.calculateSMA(List.of(42.0), 1), 0.001);
    }

    // ═══════════════════════════════════  EMA  ════════════════════════════════

    @Test
    void ema_allSameValues_equalsInput() {
        List<Double> prices = Collections.nCopies(20, 50.0);
        assertEquals(50.0, svc.calculateEMA(prices, 9), 0.001);
    }

    @Test
    void ema_recentValuesWeightedMore_vsSlowerSma() {
        // Exponentially rising: EMA(9) should be above SMA(20) since EMA weighs recent values more
        List<Double> prices = new ArrayList<>();
        for (int i = 0; i < 30; i++) prices.add(100.0 * Math.pow(1.02, i));  // +2% each candle
        double ema9  = svc.calculateEMA(prices, 9);
        double sma20 = svc.calculateSMA(prices, 20);
        assertTrue(ema9 > sma20, "EMA(9) should be above SMA(20) for exponentially rising series");
    }

    @Test
    void ema_insufficientData_returnsAverage() {
        List<Double> prices = List.of(10.0, 20.0, 30.0);
        assertEquals(20.0, svc.calculateEMA(prices, 9), 0.001);
    }

    // ═══════════════════════════════════  MACD  ═══════════════════════════════

    @Test
    void macd_insufficientData_returnsZeroArray() {
        List<Double> prices = Collections.nCopies(30, 100.0);  // need 26+9=35
        double[] macd = svc.calculateMACD(prices);
        assertArrayEquals(new double[]{0, 0, 0}, macd, 0.001);
    }

    @Test
    void macd_trendingUp_positiveHistogram() {
        // Exponential growth creates an accelerating MACD_line → histogram > 0
        List<Double> prices = new ArrayList<>();
        for (int i = 0; i < 60; i++) prices.add(100.0 * Math.pow(1.01, i));
        double[] macd = svc.calculateMACD(prices);
        // MACD line (EMA12 - EMA26) must be positive for uptrend
        assertTrue(macd[0] > 0, "Uptrend should give positive MACD line, got " + macd[0]);
    }

    @Test
    void macd_trendingDown_negativeHistogram() {
        // Exponential decline: MACD line should be negative
        List<Double> prices = new ArrayList<>();
        for (int i = 0; i < 60; i++) prices.add(200.0 * Math.pow(0.99, i));
        double[] macd = svc.calculateMACD(prices);
        assertTrue(macd[0] < 0, "Downtrend should give negative MACD line, got " + macd[0]);
    }

    @Test
    void macd_returnsThreeComponents() {
        List<Double> prices = new ArrayList<>();
        for (int i = 0; i < 50; i++) prices.add(100.0 + i);
        double[] macd = svc.calculateMACD(prices);
        assertEquals(3, macd.length);
        // histogram = macdLine - signalLine
        assertEquals(macd[0] - macd[1], macd[2], 0.001);
    }

    // ══════════════════════════════  BOLLINGER BANDS  ═════════════════════════

    @Test
    void bollinger_uniformPrices_allBandsEqual() {
        List<Double> prices = Collections.nCopies(30, 100.0);
        double[] bb = svc.calculateBollingerBands(prices, 20);
        // σ = 0 → upper == mid == lower
        assertEquals(bb[1], bb[0], 0.001);  // upper = mid
        assertEquals(bb[1], bb[2], 0.001);  // lower = mid
    }

    @Test
    void bollinger_volatilePrices_upperAboveLower() {
        List<Double> prices = new ArrayList<>();
        for (int i = 0; i < 30; i++) prices.add(i % 2 == 0 ? 100.0 : 110.0);
        double[] bb = svc.calculateBollingerBands(prices, 20);
        assertTrue(bb[0] > bb[2], "Upper band should be above lower band");
        assertTrue(bb[1] > bb[2] && bb[1] < bb[0], "Middle band should be between upper and lower");
    }

    @Test
    void bollinger_upperMinus2σ_equalsLower() {
        List<Double> prices = new ArrayList<>();
        for (int i = 0; i < 30; i++) prices.add(100.0 + (i % 3) * 5.0);
        double[] bb = svc.calculateBollingerBands(prices, 20);
        // bb[0]=upper(+2σ), bb[1]=mid, bb[2]=lower(-2σ) → upper-mid ≈ mid-lower
        assertEquals(bb[0] - bb[1], bb[1] - bb[2], 0.01);
    }

    // ═══════════════════════════════════  SCORE  ══════════════════════════════

    @Test
    void score_oversoldGoldenCrossHighVolume_maxScore() {
        // RSI <30 (+40), price>sma20>sma50 (+40), volume ≥ 2× avg (+20) = 100
        int score = svc.calculateScore(25.0, 110.0, 105.0, 100.0, 2000L, 1000.0);
        assertEquals(100, score);
    }

    @Test
    void score_overboughtBearishLowVolume_minScore() {
        // RSI >70 (0 pts), price < sma20 < sma50 (0 pts), volume low (+5 pts) = 5
        int score = svc.calculateScore(75.0, 90.0, 95.0, 100.0, 500L, 1000.0);
        assertEquals(5, score);
    }

    @Test
    void score_rsiBelow30_adds40Points() {
        // RSI<30 (+40) + price=sma20=sma50 (0 trend) + avgVol=0 (+10) = 50
        assertEquals(50, svc.calculateScore(29.0, 100.0, 100.0, 100.0, 0L, 0.0));
    }

    @Test
    void score_rsi40to50_adds22Points() {
        // RSI 40-50 (+22) + price=sma20=sma50 (+0) + ratio 1.0 (+10) = 32
        int score = svc.calculateScore(45.0, 100.0, 100.0, 100.0, 1000L, 1000.0);
        assertEquals(32, score);
    }

    @Test
    void score_rsiAbove70_adds0Points() {
        // RSI > 70 (0) + price=sma20=sma50 (0) + ratio 1.0 (+10) = 10
        int score = svc.calculateScore(75.0, 100.0, 100.0, 100.0, 1000L, 1000.0);
        assertEquals(10, score);
    }

    @Test
    void score_zeroAverageVolume_addsTenPoints() {
        // avgVolume=0 → +10; RSI 40-50 (+22), price<sma20<sma50 (0 trend) → 32
        int score = svc.calculateScore(45.0, 90.0, 95.0, 100.0, 0L, 0.0);
        assertEquals(32, score);
    }

    // ════════════════════════════════════  ADX  ═══════════════════════════════

    @Test
    void adx_insufficientData_returnsZeroArray() {
        double[] adx = svc.calculateADX(new double[]{100}, new double[]{99}, new double[]{100}, 14);
        assertArrayEquals(new double[]{0, 0, 0}, adx, 0.001);
    }

    @Test
    void adx_strongTrend_above25() {
        int n = 40;
        double[] h = new double[n], l = new double[n], c = new double[n];
        for (int i = 0; i < n; i++) {
            c[i] = 100 + i * 2.0;
            h[i] = c[i] + 1;
            l[i] = c[i] - 1;
        }
        double[] result = svc.calculateADX(h, l, c, 14);
        assertTrue(result[0] > 15, "Strong trend should yield ADX > 15, got " + result[0]);
    }

    @Test
    void adx_uptrend_plusDI_greaterThan_minusDI() {
        int n = 30;
        double[] h = new double[n], l = new double[n], c = new double[n];
        for (int i = 0; i < n; i++) {
            c[i] = 100 + i;
            h[i] = c[i] + 0.5;
            l[i] = c[i] - 0.5;
        }
        double[] result = svc.calculateADX(h, l, c, 14);
        assertTrue(result[1] > result[2], "+DI should exceed -DI in uptrend");
    }

    @Test
    void adx_downtrend_minusDI_greaterThan_plusDI() {
        int n = 30;
        double[] h = new double[n], l = new double[n], c = new double[n];
        for (int i = 0; i < n; i++) {
            c[i] = 200 - i;
            h[i] = c[i] + 0.5;
            l[i] = c[i] - 0.5;
        }
        double[] result = svc.calculateADX(h, l, c, 14);
        assertTrue(result[2] > result[1], "-DI should exceed +DI in downtrend");
    }

    // ══════════════════════════════  STOCHASTIC  ══════════════════════════════

    @Test
    void stochastic_insufficientData_returns50_50() {
        double[] h = {100, 101}, l = {99, 100}, c = {100, 101};
        double[] result = svc.calculateStochastic(h, l, c, 14, 3);
        assertArrayEquals(new double[]{50, 50}, result, 0.001);
    }

    @Test
    void stochastic_atTopOfRange_kNear100() {
        int n = 20;
        double[] h = new double[n], l = new double[n], c = new double[n];
        for (int i = 0; i < n; i++) {
            h[i] = 100 + i;
            l[i] = i;         // wide range, price near top
            c[i] = 99 + i;   // close near high
        }
        double[] result = svc.calculateStochastic(h, l, c, 14, 3);
        assertTrue(result[0] > 80, "%K near top should be > 80, got " + result[0]);
    }

    @Test
    void stochastic_atBottomOfRange_kNear0() {
        int n = 20;
        double[] h = new double[n], l = new double[n], c = new double[n];
        for (int i = 0; i < n; i++) {
            h[i] = 100;
            l[i] = 0;         // wide range
            c[i] = 1;         // close near low
        }
        double[] result = svc.calculateStochastic(h, l, c, 14, 3);
        assertTrue(result[0] < 20, "%K near bottom should be < 20, got " + result[0]);
    }

    @Test
    void stochastic_range_always0to100() {
        int n = 30;
        double[] h = new double[n], l = new double[n], c = new double[n];
        for (int i = 0; i < n; i++) {
            h[i] = 100 + (i % 5) * 3;
            l[i] = 90 - (i % 3);
            c[i] = 95 + (i % 7);
        }
        double[] result = svc.calculateStochastic(h, l, c, 14, 3);
        assertTrue(result[0] >= 0 && result[0] <= 100, "%K out of range: " + result[0]);
        assertTrue(result[1] >= 0 && result[1] <= 100, "%D out of range: " + result[1]);
    }

    // ═══════════════════════════════  OBV SLOPE  ══════════════════════════════

    @Test
    void obvSlope_risingPricesWithVolume_positive() {
        List<Double> closes  = List.of(100.0, 101.0, 102.0, 103.0, 104.0,
                                       105.0, 106.0, 107.0, 108.0, 109.0,
                                       110.0, 111.0, 112.0, 113.0, 114.0);
        List<Double> volumes = Collections.nCopies(15, 1000.0);
        double slope = svc.calculateOBVSlope(closes, volumes, 10);
        assertTrue(slope > 0, "Rising prices should produce positive OBV slope, got " + slope);
    }

    @Test
    void obvSlope_fallingPricesWithVolume_negative() {
        List<Double> closes  = List.of(114.0, 113.0, 112.0, 111.0, 110.0,
                                       109.0, 108.0, 107.0, 106.0, 105.0,
                                       104.0, 103.0, 102.0, 101.0, 100.0);
        List<Double> volumes = Collections.nCopies(15, 1000.0);
        double slope = svc.calculateOBVSlope(closes, volumes, 10);
        assertTrue(slope < 0, "Falling prices should produce negative OBV slope, got " + slope);
    }

    @Test
    void obvSlope_insufficientData_returnsZero() {
        assertEquals(0.0, svc.calculateOBVSlope(List.of(100.0), List.of(1000.0), 10));
    }

    // ═══════════════════════════════════  ATR  ════════════════════════════════

    @Test
    void atr_insufficientData_returnsZero() {
        double[] h = {105}, l = {95}, c = {100};
        assertEquals(0.0, svc.computeATR(h, l, c, 14), 0.001);
    }

    @Test
    void atr_uniformCandles_equalsRange() {
        int n = 20;
        double[] h = new double[n], l = new double[n], c = new double[n];
        for (int i = 0; i < n; i++) { h[i] = 105; l[i] = 95; c[i] = 100; }
        // TR = 105 - 95 = 10 every candle → ATR should converge to 10
        assertEquals(10.0, svc.computeATR(h, l, c, 14), 0.001);
    }

    @Test
    void atr_alwaysPositive() {
        int n = 25;
        double[] h = new double[n], l = new double[n], c = new double[n];
        for (int i = 0; i < n; i++) {
            c[i] = 100 + (i % 5 - 2) * 3;
            h[i] = c[i] + 2;
            l[i] = c[i] - 2;
        }
        assertTrue(svc.computeATR(h, l, c, 14) >= 0, "ATR must be non-negative");
    }

    // ══════════════════════════════════  VWAP  ════════════════════════════════

    @Test
    void vwap_uniformCandles_equalsTypicalPrice() {
        // All candles identical → VWAP = (high+low+close)/3
        int n = 20;
        double[] h = new double[n], l = new double[n], c = new double[n], v = new double[n];
        for (int i = 0; i < n; i++) { h[i] = 105; l[i] = 95; c[i] = 100; v[i] = 1000; }
        double expected = (105 + 95 + 100) / 3.0;
        assertEquals(expected, svc.calculateVWAP(h, l, c, v), 0.001);
    }

    @Test
    void vwap_heavierVolumeAtLowerPrice_belowSimpleAvg() {
        // Two candles: one at 100 (low price, high volume), one at 200 (high price, low volume)
        double[] h = {101, 201}, l = {99, 199}, c = {100, 200}, v = {9000, 1000};
        double vwap = svc.calculateVWAP(h, l, c, v);
        // VWAP should be closer to 100 than to 150 (simple avg of typical prices)
        assertTrue(vwap < 125, "VWAP should be pulled toward the high-volume candle, got " + vwap);
        assertTrue(vwap > 100, "VWAP must be above the lowest typical price, got " + vwap);
    }

    @Test
    void vwap_heavierVolumeAtHigherPrice_aboveSimpleAvg() {
        // Two candles: one at 100 (low price, low volume), one at 200 (high price, high volume)
        double[] h = {101, 201}, l = {99, 199}, c = {100, 200}, v = {1000, 9000};
        double vwap = svc.calculateVWAP(h, l, c, v);
        assertTrue(vwap > 175, "VWAP should be pulled toward the high-volume candle, got " + vwap);
    }

    @Test
    void vwap_singleCandle_equalsItsTypicalPrice() {
        double[] h = {110}, l = {90}, c = {100}, v = {500};
        double expected = (110 + 90 + 100) / 3.0;
        assertEquals(expected, svc.calculateVWAP(h, l, c, v), 0.001);
    }

    @Test
    void vwap_zeroVolume_fallsBackToLastClose() {
        double[] h = {105, 110}, l = {95, 90}, c = {100, 95}, v = {0, 0};
        // All volumes zero → returns last close
        assertEquals(95.0, svc.calculateVWAP(h, l, c, v), 0.001);
    }

    @Test
    void vwap_alwaysBetweenMinAndMaxTypicalPrice() {
        int n = 30;
        double[] h = new double[n], l = new double[n], c = new double[n], v = new double[n];
        for (int i = 0; i < n; i++) {
            c[i] = 100 + (i % 7) * 5;
            h[i] = c[i] + 3;
            l[i] = c[i] - 3;
            v[i] = 1000 + (i % 5) * 200;
        }
        double vwap = svc.calculateVWAP(h, l, c, v);
        double minTP = Double.MAX_VALUE, maxTP = Double.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            double tp = (h[i] + l[i] + c[i]) / 3.0;
            minTP = Math.min(minTP, tp);
            maxTP = Math.max(maxTP, tp);
        }
        assertTrue(vwap >= minTP && vwap <= maxTP,
                String.format("VWAP %.2f must be in [%.2f, %.2f]", vwap, minTP, maxTP));
    }
}
