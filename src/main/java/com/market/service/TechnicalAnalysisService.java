package com.market.service;

import jakarta.enterprise.context.ApplicationScoped;
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
}
