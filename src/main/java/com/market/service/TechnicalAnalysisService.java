package com.market.service;

import com.market.model.CandleDTO;
import com.market.model.MarketStructureResult;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure functions for computing technical indicators from a chronological
 * list of daily closing prices (oldest index 0, newest index N-1).
 */
@ApplicationScoped
public class TechnicalAnalysisService {

    // ── RSI ───────────────────────────────────────────────────────────────────

    /**
     * Wilder-smoothed RSI over {@code period} candles (typically 14).
     * Returns 50 (neutral) if there is not enough data.
     */
    public double calculateRSI(List<Double> closePrices, int period) {
        if (closePrices.size() <= period) {
            return 50.0;
        }

        double avgGain = 0.0;
        double avgLoss = 0.0;

        for (int i = 1; i <= period; i++) {
            double change = closePrices.get(i) - closePrices.get(i - 1);
            if (change >= 0) avgGain += change;
            else avgLoss += Math.abs(change);
        }
        avgGain /= period;
        avgLoss /= period;

        for (int i = period + 1; i < closePrices.size(); i++) {
            double change = closePrices.get(i) - closePrices.get(i - 1);
            if (change >= 0) {
                avgGain = (avgGain * (period - 1) + change) / period;
                avgLoss = (avgLoss * (period - 1)) / period;
            } else {
                avgGain = (avgGain * (period - 1)) / period;
                avgLoss = (avgLoss * (period - 1) + Math.abs(change)) / period;
            }
        }

        if (avgLoss == 0.0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }

    // ── SMA ───────────────────────────────────────────────────────────────────

    /** Simple Moving Average of the last {@code period} prices. */
    public double calculateSMA(List<Double> closePrices, int period) {
        if (closePrices.size() < period) {
            return closePrices.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        }
        return closePrices.subList(closePrices.size() - period, closePrices.size())
                .stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    // ── EMA ───────────────────────────────────────────────────────────────────

    /**
     * Exponential Moving Average using the full price series.
     * Initialised with SMA of the first {@code period} values.
     */
    public double calculateEMA(List<Double> prices, int period) {
        if (prices.size() < period) {
            return prices.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        }
        double k   = 2.0 / (period + 1);
        double ema = prices.subList(0, period).stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        for (int i = period; i < prices.size(); i++) {
            ema = prices.get(i) * k + ema * (1 - k);
        }
        return ema;
    }

    // ── MACD ──────────────────────────────────────────────────────────────────

    /**
     * Computes MACD(12,26,9) for the latest data point.
     *
     * @return [macdLine, signalLine, histogram], or [0,0,0] if insufficient data.
     */
    public double[] calculateMACD(List<Double> prices) {
        int slow = 26, fast = 12, signal = 9;
        if (prices.size() < slow + signal) return new double[]{0, 0, 0};

        double kFast   = 2.0 / (fast   + 1);
        double kSlow   = 2.0 / (slow   + 1);
        double kSignal = 2.0 / (signal + 1);

        // Initialise EMA12 with SMA12, then advance it to index slow-1
        double ema12 = prices.subList(0, fast).stream().mapToDouble(Double::doubleValue).average().orElse(0);
        for (int i = fast; i < slow; i++) {
            ema12 = prices.get(i) * kFast + ema12 * (1 - kFast);
        }

        // Initialise EMA26 with SMA26
        double ema26 = prices.subList(0, slow).stream().mapToDouble(Double::doubleValue).average().orElse(0);

        // Build MACD line values starting from index `slow`
        List<Double> macdValues = new java.util.ArrayList<>();
        macdValues.add(ema12 - ema26);

        for (int i = slow; i < prices.size(); i++) {
            ema12 = prices.get(i) * kFast + ema12 * (1 - kFast);
            ema26 = prices.get(i) * kSlow + ema26 * (1 - kSlow);
            macdValues.add(ema12 - ema26);
        }

        // Signal line = EMA9 of MACD values
        double signalEma = macdValues.subList(0, signal).stream().mapToDouble(Double::doubleValue).average().orElse(0);
        for (int i = signal; i < macdValues.size(); i++) {
            signalEma = macdValues.get(i) * kSignal + signalEma * (1 - kSignal);
        }

        double macdLine  = macdValues.get(macdValues.size() - 1);
        double histogram = macdLine - signalEma;
        return new double[]{macdLine, signalEma, histogram};
    }

    // ── Bollinger Bands ───────────────────────────────────────────────────────

    /**
     * Bollinger Bands (SMA20 ± 2σ) for the latest data point.
     *
     * @return [upper, middle, lower]
     */
    public double[] calculateBollingerBands(List<Double> prices, int period) {
        if (prices.size() < period) return new double[]{0, 0, 0};
        List<Double> recent = prices.subList(prices.size() - period, prices.size());
        double mean   = recent.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double stddev = Math.sqrt(recent.stream()
                .mapToDouble(p -> Math.pow(p - mean, 2)).average().orElse(0.0));
        return new double[]{mean + 2 * stddev, mean, mean - 2 * stddev};
    }

    // ── Volume ────────────────────────────────────────────────────────────────

    /** Average volume over the last {@code period} candles. */
    public double calculateAverageVolume(List<Long> volumes, int period) {
        if (volumes.isEmpty()) return 0.0;
        int from = Math.max(0, volumes.size() - period);
        return volumes.subList(from, volumes.size())
                .stream().mapToLong(Long::longValue).average().orElse(0.0);
    }

    // ── Score ─────────────────────────────────────────────────────────────────

    /**
     * Composite score 0–100:
     * <ul>
     *   <li>RSI component    0–40 pts: oversold = buy pressure</li>
     *   <li>Trend component  0–40 pts: price position vs SMA20 / SMA50</li>
     *   <li>Volume component 0–20 pts: confirms momentum</li>
     * </ul>
     */
    public int calculateScore(double rsi, double currentPrice,
                              double sma20, double sma50,
                              long currentVolume, double avgVolume) {
        int score = 0;

        // RSI (0–40 pts)
        if      (rsi < 30) score += 40;
        else if (rsi < 40) score += 30;
        else if (rsi < 50) score += 22;
        else if (rsi < 60) score += 18;
        else if (rsi < 70) score += 10;

        // Trend (0–40 pts)
        if      (currentPrice > sma20 && sma20 > sma50) score += 40;
        else if (currentPrice > sma20)                   score += 25;
        else if (currentPrice > sma50)                   score += 15;

        // Volume (0–20 pts)
        if (avgVolume > 0) {
            double ratio = currentVolume / avgVolume;
            if      (ratio >= 2.0) score += 20;
            else if (ratio >= 1.5) score += 15;
            else if (ratio >= 1.0) score += 10;
            else                   score += 5;
        } else {
            score += 10;
        }

        return score;
    }

    // ── ADX (Average Directional Index) ──────────────────────────────────────

    /**
     * Wilder-smoothed ADX(period) with +DI and -DI.
     * Arrays must be same length, chronological order (oldest first).
     *
     * @return [ADX, +DI, -DI]
     */
    public double[] calculateADX(double[] highs, double[] lows, double[] closes, int period) {
        int n = closes.length;
        if (n < period + 1) return new double[]{0, 0, 0};

        double[] tr   = new double[n];
        double[] plusDM  = new double[n];
        double[] minusDM = new double[n];

        tr[0] = highs[0] - lows[0];
        for (int i = 1; i < n; i++) {
            double hl  = highs[i] - lows[i];
            double hpc = Math.abs(highs[i] - closes[i - 1]);
            double lpc = Math.abs(lows[i]  - closes[i - 1]);
            tr[i] = Math.max(hl, Math.max(hpc, lpc));

            double upMove   = highs[i]  - highs[i - 1];
            double downMove = lows[i - 1] - lows[i];
            plusDM[i]  = (upMove   > downMove && upMove   > 0) ? upMove   : 0;
            minusDM[i] = (downMove > upMove   && downMove > 0) ? downMove : 0;
        }

        // Wilder initial sums
        double sTR = 0, sPDM = 0, sMDM = 0;
        for (int i = 1; i <= period; i++) { sTR += tr[i]; sPDM += plusDM[i]; sMDM += minusDM[i]; }

        double[] dx = new double[n];
        for (int i = period + 1; i < n; i++) {
            sTR  = sTR  - sTR  / period + tr[i];
            sPDM = sPDM - sPDM / period + plusDM[i];
            sMDM = sMDM - sMDM / period + minusDM[i];

            double pdi = sTR > 0 ? 100.0 * sPDM / sTR : 0;
            double mdi = sTR > 0 ? 100.0 * sMDM / sTR : 0;
            double sum = pdi + mdi;
            dx[i] = sum > 0 ? 100.0 * Math.abs(pdi - mdi) / sum : 0;
        }

        // ADX = Wilder smooth of DX over last `period` DX values
        double adx = 0;
        int start = period + 1;
        for (int i = start; i < start + period && i < n; i++) adx += dx[i];
        adx /= period;
        for (int i = start + period; i < n; i++) adx = (adx * (period - 1) + dx[i]) / period;

        // Final +DI / -DI at last candle
        double pdi = sTR > 0 ? 100.0 * sPDM / sTR : 0;
        double mdi = sTR > 0 ? 100.0 * sMDM / sTR : 0;

        return new double[]{adx, pdi, mdi};
    }

    // ── Stochastic ────────────────────────────────────────────────────────────

    /**
     * Stochastic oscillator %K(kPeriod) smoothed to %D(dPeriod).
     * Arrays must be same length, chronological order (oldest first).
     *
     * @return [%K, %D]
     */
    public double[] calculateStochastic(double[] highs, double[] lows, double[] closes,
                                        int kPeriod, int dPeriod) {
        int n = closes.length;
        if (n < kPeriod) return new double[]{50, 50};

        // Raw %K for each candle where we have enough history
        java.util.List<Double> kValues = new java.util.ArrayList<>();
        for (int i = kPeriod - 1; i < n; i++) {
            double lo = lows[i], hi = highs[i];
            for (int j = i - kPeriod + 1; j < i; j++) {
                lo = Math.min(lo, lows[j]);
                hi = Math.max(hi, highs[j]);
            }
            double range = hi - lo;
            kValues.add(range > 0 ? 100.0 * (closes[i] - lo) / range : 50.0);
        }

        double lastK = kValues.get(kValues.size() - 1);

        // %D = SMA(dPeriod) of the last dPeriod %K values
        int from = Math.max(0, kValues.size() - dPeriod);
        double d = kValues.subList(from, kValues.size())
                          .stream().mapToDouble(Double::doubleValue).average().orElse(50);

        return new double[]{lastK, d};
    }

    // ── OBV (On-Balance Volume) ───────────────────────────────────────────────

    /**
     * Returns the OBV trend slope: positive = OBV rising (buying pressure),
     * negative = OBV falling (selling pressure).
     * Computed as OBV[last] − OBV[last − lookback].
     */
    public double calculateOBVSlope(List<Double> closes, List<Double> volumes, int lookback) {
        int n = closes.size();
        if (n < 2) return 0;

        double[] obv = new double[n];
        for (int i = 1; i < n; i++) {
            double delta = closes.get(i) - closes.get(i - 1);
            obv[i] = obv[i - 1] + (delta > 0 ? volumes.get(i) : delta < 0 ? -volumes.get(i) : 0);
        }

        int lb = Math.min(lookback, n - 1);
        return obv[n - 1] - obv[n - 1 - lb];
    }

    // ── ATR (Average True Range) ──────────────────────────────────────────────

    /**
     * Wilder-smoothed ATR over {@code period} candles.
     * Arrays must be same length and in chronological order (oldest first).
     */
    public double computeATR(double[] highs, double[] lows, double[] closes, int period) {
        int n = closes.length;
        if (n < period + 1) return 0.0;

        double[] tr = new double[n];
        tr[0] = highs[0] - lows[0];
        for (int i = 1; i < n; i++) {
            double hl  = highs[i]  - lows[i];
            double hpc = Math.abs(highs[i]  - closes[i - 1]);
            double lpc = Math.abs(lows[i]   - closes[i - 1]);
            tr[i] = Math.max(hl, Math.max(hpc, lpc));
        }

        // Initial ATR = simple mean of first `period` TR values
        double atr = 0;
        for (int i = 0; i < period; i++) atr += tr[i];
        atr /= period;

        // Wilder smoothing for remaining candles
        for (int i = period; i < n; i++) {
            atr = (atr * (period - 1) + tr[i]) / period;
        }
        return atr;
    }

    // ── VWAP (Volume Weighted Average Price) ─────────────────────────────────

    /**
     * VWAP over all provided candles: Σ(typicalPrice × volume) / Σ(volume).
     * Typical price = (high + low + close) / 3.
     * Arrays must be same length and in chronological order (oldest first).
     */
    public double calculateVWAP(double[] highs, double[] lows, double[] closes, double[] volumes) {
        double cumTPV = 0.0;
        double cumVol = 0.0;
        for (int i = 0; i < closes.length; i++) {
            double tp = (highs[i] + lows[i] + closes[i]) / 3.0;
            cumTPV += tp * volumes[i];
            cumVol += volumes[i];
        }
        return cumVol > 0 ? cumTPV / cumVol : closes[closes.length - 1];
    }

    // ── Market Structure ──────────────────────────────────────────────────────

    /**
     * Detects market structure (HH/HL, LH/LL, Breakout, Consolidation) from OHLCV candles.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Find swing pivot highs and lows: a candle is a pivot high if its {@code high}
     *       is strictly the highest in the window [i-strength, i+strength].</li>
     *   <li>Compare the last two confirmed pivot highs and lows to classify HH/HL/LH/LL.</li>
     *   <li>Check for breakout: current close exceeds the last pivot high/low by 0.3%.</li>
     *   <li>If no clear structure, classify as Consolidation.</li>
     * </ol>
     *
     * @param candles       chronological OHLCV candles (oldest first)
     * @param pivotStrength number of bars on each side required to confirm a pivot (e.g. 3)
     * @return MarketStructureResult with type, HH/HL/LH/LL flags, support/resistance and score
     */
    public MarketStructureResult detectMarketStructure(List<CandleDTO> candles, int pivotStrength) {
        MarketStructureResult result = new MarketStructureResult();

        int n = candles.size();
        // Need at least 2×strength + some room for 2 confirmed pivots
        if (n < pivotStrength * 2 + 10) {
            result.type        = MarketStructureResult.Type.CONSOLIDATION;
            result.score       = 0;
            result.description = "Pas assez de données pour la structure";
            return result;
        }

        List<Double>  pivotHighs = new ArrayList<>();
        List<Double>  pivotLows  = new ArrayList<>();

        // Last pivotStrength candles are unconfirmed — exclude them
        int limit = n - pivotStrength;
        for (int i = pivotStrength; i < limit; i++) {
            boolean isPivotHigh = true;
            boolean isPivotLow  = true;
            double  candleHigh  = candles.get(i).high;
            double  candleLow   = candles.get(i).low;

            for (int j = i - pivotStrength; j <= i + pivotStrength; j++) {
                if (j == i) continue;
                if (candles.get(j).high >= candleHigh) isPivotHigh = false;
                if (candles.get(j).low  <= candleLow)  isPivotLow  = false;
            }
            if (isPivotHigh) pivotHighs.add(candleHigh);
            if (isPivotLow)  pivotLows.add(candleLow);
        }

        double currentClose = candles.get(n - 1).close;

        // Extract last two confirmed pivots
        double lastPH = pivotHighs.size() >= 1 ? pivotHighs.get(pivotHighs.size() - 1) : Double.NaN;
        double prevPH = pivotHighs.size() >= 2 ? pivotHighs.get(pivotHighs.size() - 2) : Double.NaN;
        double lastPL = pivotLows.size()  >= 1 ? pivotLows.get(pivotLows.size() - 1)   : Double.NaN;
        double prevPL = pivotLows.size()  >= 2 ? pivotLows.get(pivotLows.size() - 2)   : Double.NaN;

        result.lastPivotHigh = Double.isNaN(lastPH) ? 0 : lastPH;
        result.lastPivotLow  = Double.isNaN(lastPL) ? 0 : lastPL;
        result.resistance    = result.lastPivotHigh;
        result.support       = result.lastPivotLow;

        // Classify swing structure
        result.hh = !Double.isNaN(lastPH) && !Double.isNaN(prevPH) && lastPH > prevPH;
        result.hl = !Double.isNaN(lastPL) && !Double.isNaN(prevPL) && lastPL > prevPL;
        result.lh = !Double.isNaN(lastPH) && !Double.isNaN(prevPH) && lastPH < prevPH;
        result.ll = !Double.isNaN(lastPL) && !Double.isNaN(prevPL) && lastPL < prevPL;

        // Breakout: close exceeds last confirmed pivot by 0.3% buffer
        double buf          = currentClose * 0.003;
        boolean breakoutUp   = !Double.isNaN(lastPH) && currentClose > lastPH + buf;
        boolean breakoutDown = !Double.isNaN(lastPL) && currentClose < lastPL - buf;

        if (result.hh && result.hl) {
            result.type        = MarketStructureResult.Type.BULL_TREND;
            result.score       = 20;
            result.description = "HH+HL — structure haussière";
        } else if (result.lh && result.ll) {
            result.type        = MarketStructureResult.Type.BEAR_TREND;
            result.score       = -20;
            result.description = "LH+LL — structure baissière";
        } else if (breakoutUp) {
            result.type        = MarketStructureResult.Type.BREAKOUT_UP;
            result.score       = 15;
            result.description = String.format("Breakout haussier (>%.0f)", lastPH);
        } else if (breakoutDown) {
            result.type        = MarketStructureResult.Type.BREAKOUT_DOWN;
            result.score       = -15;
            result.description = String.format("Breakout baissier (<%.0f)", lastPL);
        } else {
            result.type        = MarketStructureResult.Type.CONSOLIDATION;
            result.score       = -5;
            result.description = "Consolidation — structure indéfinie";
        }

        return result;
    }
}
