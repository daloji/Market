package com.market.service;

import com.market.client.BinanceClient;
import com.market.model.CandleDTO;
import com.market.model.ScalpingSignal;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Computes ultra-short-term scalping signals for BTC/USDT based on 1m candles.
 *
 * Strategy:
 *   - RSI(7)          : oversold < 30 → LONG bias, overbought > 70 → SHORT bias
 *   - EMA(5) / EMA(13): fast cross on 1m for trend direction
 *   - MACD(6,13,4)    : faster MACD adapted for 1m scalping
 *   - Volume Delta    : takerBuyVolume / totalVolume pressure
 *
 * TP/SL are ATR(7)-based, very tight for scalping:
 *   TP1 = 0.5 × ATR, TP2 = 1.0 × ATR, SL = 0.4 × ATR
 *
 * Signal: score >= 78 → LONG/SHORT, else WAIT.
 * Thresholds: 78 with-trend (SMA50), 92 counter-trend.
 * Cache TTL: 10 seconds (fast refresh for scalping).
 */
@ApplicationScoped
public class ScalpingAnalysisService {

    private static final Logger LOG          = Logger.getLogger(ScalpingAnalysisService.class);
    private static final int    LEVERAGE     = 10;
    private static final long   CACHE_TTL_MS = 10_000;

    @Inject @RestClient BinanceClient binanceClient;
    @Inject TechnicalAnalysisService ta;

    private ScalpingSignal cached;
    private long           cachedAt = 0;

    public ScalpingSignal getSignal() {
        long now = System.currentTimeMillis();
        if (cached != null && (now - cachedAt) < CACHE_TTL_MS) {
            return cached;
        }
        ScalpingSignal signal = compute();
        if (signal.error == null) {
            cached   = signal;
            cachedAt = now;
        } else if (cached != null) {
            LOG.warnf("[Scalping] Compute error (%s) — returning stale cache (%.2f)", signal.error, cached.currentPrice);
            return cached;
        }
        return signal;
    }

    // ── Core computation ──────────────────────────────────────────────────────

    private ScalpingSignal compute() {
        ScalpingSignal s = new ScalpingSignal();
        s.timestamp = LocalDateTime.now();

        try {
            // Fetch 200 × 1m candles (≈ 3h20 of data)
            List<List<Object>> raw1m = binanceClient.getKlines("BTCUSDT", "1m", 200);
            if (raw1m == null || raw1m.size() < 30) {
                s.error = "Not enough 1m candle data from Binance";
                return s;
            }

            List<CandleDTO> candles = raw1m.stream().map(this::parseKline).collect(Collectors.toList());
            int n = candles.size();

            double[] highs   = new double[n];
            double[] lows    = new double[n];
            double[] closes  = new double[n];
            double[] volumes = new double[n];
            double[] buyVolumes = new double[n];

            for (int i = 0; i < n; i++) {
                CandleDTO c = candles.get(i);
                highs[i]   = c.high;
                lows[i]    = c.low;
                closes[i]  = c.close;
                volumes[i] = c.volume;
                // index 9 = takerBuyBaseAssetVolume
                List<Object> raw = raw1m.get(i);
                buyVolumes[i] = raw.size() > 9
                        ? Double.parseDouble(raw.get(9).toString())
                        : c.volume * 0.5;
            }

            List<Double> closeList = new ArrayList<>();
            for (double c : closes) closeList.add(c);

            double price = closes[n - 1];
            s.currentPrice = price;
            s.entryPrice   = price;

            // ── Indicators ────────────────────────────────────────────────────

            // RSI(7) — faster than RSI(14)
            s.rsi7 = ta.calculateRSI(closeList, 7);

            // RSI dynamics: slope, acceleration, divergence
            s.rsiPrev  = ta.calculateRSI(closeList.subList(0, n - 1), 7);
            s.rsiPrev2 = ta.calculateRSI(closeList.subList(0, n - 2), 7);
            s.rsiSlope        = r2(s.rsi7    - s.rsiPrev);
            s.rsiAcceleration = r2(s.rsiSlope - r2(s.rsiPrev - s.rsiPrev2));

            // RSI divergence over last 5 candles (price slope vs RSI slope)
            // Threshold 3.0 (was 1.5) to cut 1m noise-induced fake divergences
            double priceSlope5 = closes[n - 1] - closes[n - 5];
            double rsi5ago     = ta.calculateRSI(closeList.subList(0, n - 4), 7);
            double rsiSlope5   = s.rsi7 - rsi5ago;
            if (priceSlope5 < 0 && rsiSlope5 > 3.0) {
                s.rsiDivergence = "BULLISH";
            } else if (priceSlope5 > 0 && rsiSlope5 < -3.0) {
                s.rsiDivergence = "BEARISH";
            } else {
                s.rsiDivergence = "NONE";
            }

            // EMA(5) and EMA(13) on 1m
            s.ema5  = ta.calculateEMA(closeList, 5);
            s.ema13 = ta.calculateEMA(closeList, 13);

            // SMA(50) on 1m — medium-term trend filter
            s.sma50_1m = n >= 50 ? ta.calculateSMA(closeList, 50) : s.ema13;

            // VWAP over the full 1m window
            s.vwap = r2(ta.calculateVWAP(highs, lows, closes, volumes));

            // MACD(6, 13, 4) — faster MACD for 1m scalping
            double[] macd = calculateFastMACD(closeList, 6, 13, 4);
            s.macdLine      = r2(macd[0]);
            s.macdSignal    = r2(macd[1]);
            s.macdHistogram = r2(macd[2]);

            // ATR(7)
            s.atr    = ta.computeATR(highs, lows, closes, 7);
            s.atrPct = r2(s.atr / price * 100);

            // Bollinger Bands(20) on 1m
            double[] bb = ta.calculateBollingerBands(closeList, 20);
            double bbUpper = bb[0], bbMid = bb[1], bbLower = bb[2];
            s.bbWidth = r2((bbUpper - bbLower) / bbMid * 100);
            s.bbState = s.bbWidth < 0.18 ? "SQUEEZE" : s.bbWidth > 1.0 ? "EXPANSION" : "NORMAL";

            // ── Bollinger SQUEEZE gate — no trade when market is flat ─────────
            if ("SQUEEZE".equals(s.bbState)) {
                s.direction  = "WAIT";
                s.confidence = 0;
                s.reasoning  = "BB SQUEEZE — marché sans direction, pas de trade.";
                s.candles    = candles.subList(Math.max(0, candles.size() - 100), candles.size());
                return s;
            }

            // ── ATR gate — no trade when market is too quiet ──────────────────
            if (s.atrPct <= 0.04) {
                s.direction  = "WAIT";
                s.confidence = 0;
                s.reasoning  = String.format("ATR trop bas (%.2f%%) — volatilité insuffisante pour scalper.", s.atrPct);
                s.candles    = candles.subList(Math.max(0, candles.size() - 100), candles.size());
                return s;
            }

            // Stochastic(5, 3) — faster than (14,3) for scalping
            double[] stoch = ta.calculateStochastic(highs, lows, closes, 5, 3);
            s.stochK = r2(stoch[0]);
            s.stochD = r2(stoch[1]);

            // ADX(14) — trend strength / regime detection
            double[] adxResult = ta.calculateADX(highs, lows, closes, 14);
            s.adx      = r2(adxResult[0]);
            s.plusDI   = r2(adxResult[1]);
            s.minusDI  = r2(adxResult[2]);
            s.marketRegime = s.adx > 25 ? "TREND" : s.adx < 20 ? "RANGE" : "NEUTRAL";

            // Volume Delta — buying pressure over last 20 candles
            double totalVol = 0, totalBuy = 0;
            for (int i = n - 20; i < n; i++) {
                totalVol += volumes[i];
                totalBuy += buyVolumes[i];
            }
            s.volumeDeltaPct = totalVol > 0 ? r2(totalBuy / totalVol * 100) : 50.0;
            s.volumeDeltaTrend = s.volumeDeltaPct > 60 ? "STRONG_BUY"
                    : s.volumeDeltaPct > 52 ? "BUY"
                    : s.volumeDeltaPct < 40 ? "STRONG_SELL"
                    : s.volumeDeltaPct < 48 ? "SELL"
                    : "NEUTRAL";

            // ── Scoring ───────────────────────────────────────────────────────

            int longScore  = 0;
            int shortScore = 0;
            StringBuilder reasoning = new StringBuilder();

            // Regime multipliers from ADX(14):
            //   TREND  (ADX>25) → EMA/MACD ×1.5, RSI/Stoch/RSIdyn ×0.7
            //   RANGE  (ADX<20) → RSI/Stoch/RSIdyn ×1.5, EMA/MACD ×0.7
            //   NEUTRAL (20–25) → all ×1.0
            double oscMult   = "TREND".equals(s.marketRegime) ? 0.7 : "RANGE".equals(s.marketRegime) ? 1.5 : 1.0;
            double trendMult = "TREND".equals(s.marketRegime) ? 1.5 : "RANGE".equals(s.marketRegime) ? 0.7 : 1.0;
            reasoning.append(String.format("ADX=%.1f[%s](osc×%.1f,trend×%.1f). ", s.adx, s.marketRegime, oscMult, trendMult));

            // RSI(7): < 30 = oversold (+25 LONG), > 70 = overbought (+25 SHORT) — scaled by oscMult
            if (s.rsi7 < 30) {
                int pts = (int)(25 * oscMult);
                s.rsiScore = pts; longScore += pts;
                reasoning.append(String.format("RSI(7) oversold(%d,%dpts). ", (int)s.rsi7, pts));
            } else if (s.rsi7 < 45) {
                int pts = (int)(12 * oscMult);
                s.rsiScore = pts; longScore += pts;
                reasoning.append(String.format("RSI(7) bas(%d,%dpts). ", (int)s.rsi7, pts));
            } else if (s.rsi7 > 70) {
                int pts = (int)(25 * oscMult);
                s.rsiScore = -pts; shortScore += pts;
                reasoning.append(String.format("RSI(7) overbought(%d,%dpts). ", (int)s.rsi7, pts));
            } else if (s.rsi7 > 55) {
                int pts = (int)(12 * oscMult);
                s.rsiScore = -pts; shortScore += pts;
                reasoning.append(String.format("RSI(7) haut(%d,%dpts). ", (int)s.rsi7, pts));
            } else {
                reasoning.append("RSI(7) neutre (").append((int)s.rsi7).append("). ");
            }

            // RSI dynamics: slope (+5), acceleration (+5), divergence (+7) — scaled by oscMult
            int rsiDynLong = 0, rsiDynShort = 0;

            if (s.rsiSlope > 0.5) {
                int pts = (int)(5 * oscMult);
                rsiDynLong  += pts;
                reasoning.append(String.format("RSI↑pente+%.1f(%dpts). ", s.rsiSlope, pts));
            } else if (s.rsiSlope < -0.5) {
                int pts = (int)(5 * oscMult);
                rsiDynShort += pts;
                reasoning.append(String.format("RSI↓pente%.1f(%dpts). ", s.rsiSlope, pts));
            }

            if (s.rsiAcceleration > 0.3) {
                int pts = (int)(5 * oscMult);
                rsiDynLong  += pts;
                reasoning.append(String.format("RSI accél+%.1f(%dpts). ", s.rsiAcceleration, pts));
            } else if (s.rsiAcceleration < -0.3) {
                int pts = (int)(5 * oscMult);
                rsiDynShort += pts;
                reasoning.append(String.format("RSI accél%.1f(%dpts). ", s.rsiAcceleration, pts));
            }

            // Divergence: stricter score ±7 (was ±10) aligned with reduced threshold
            if ("BULLISH".equals(s.rsiDivergence)) {
                int pts = (int)(7 * oscMult);
                rsiDynLong  += pts;
                reasoning.append(String.format("Divergence RSI haussière(%dpts). ", pts));
            } else if ("BEARISH".equals(s.rsiDivergence)) {
                int pts = (int)(7 * oscMult);
                rsiDynShort += pts;
                reasoning.append(String.format("Divergence RSI baissière(%dpts). ", pts));
            }

            s.rsiDynScore = rsiDynLong > rsiDynShort ? rsiDynLong : -rsiDynShort;
            longScore  += rsiDynLong;
            shortScore += rsiDynShort;

            // EMA cross: price > EMA5 > EMA13 = LONG, price < EMA5 < EMA13 = SHORT — scaled by trendMult
            if (price > s.ema5 && s.ema5 > s.ema13) {
                int pts = (int)(25 * trendMult);
                s.emaScore = pts; longScore += pts;
                reasoning.append(String.format("EMA5>EMA13 bullish(%dpts). ", pts));
            } else if (price < s.ema5 && s.ema5 < s.ema13) {
                int pts = (int)(25 * trendMult);
                s.emaScore = -pts; shortScore += pts;
                reasoning.append(String.format("EMA5<EMA13 bearish(%dpts). ", pts));
            } else if (price > s.ema13) {
                int pts = (int)(10 * trendMult);
                s.emaScore = pts; longScore += pts;
                reasoning.append(String.format("Prix > EMA13(%dpts). ", pts));
            } else if (price < s.ema13) {
                int pts = (int)(10 * trendMult);
                s.emaScore = -pts; shortScore += pts;
                reasoning.append(String.format("Prix < EMA13(%dpts). ", pts));
            }

            // MACD histogram — proportional scoring scaled by trendMult
            double macdStrength = s.atr > 0 ? Math.abs(s.macdHistogram) / s.atr : 0;
            int macdPts = (int)(Math.min(20, Math.max(3, macdStrength * 40)) * trendMult);
            if (s.macdHistogram > 0) {
                s.macdScore = macdPts; longScore += macdPts;
                reasoning.append(String.format("MACD+%.2f(%dpts). ", s.macdHistogram, macdPts));
            } else if (s.macdHistogram < 0) {
                s.macdScore = -macdPts; shortScore += macdPts;
                reasoning.append(String.format("MACD%.2f(%dpts). ", s.macdHistogram, macdPts));
            }

            // Volume delta
            if (s.volumeDeltaPct > 60) {
                s.volScore = 25; longScore += 25;
                reasoning.append("Fort achat (").append((int)s.volumeDeltaPct).append("%). ");
            } else if (s.volumeDeltaPct > 52) {
                s.volScore = 12; longScore += 12;
                reasoning.append("Achat (").append((int)s.volumeDeltaPct).append("%). ");
            } else if (s.volumeDeltaPct < 40) {
                s.volScore = -25; shortScore += 25;
                reasoning.append("Forte vente (").append((int)s.volumeDeltaPct).append("%). ");
            } else if (s.volumeDeltaPct < 48) {
                s.volScore = -12; shortScore += 12;
                reasoning.append("Vente (").append((int)s.volumeDeltaPct).append("%). ");
            }

            // Stochastic — extreme zones only, scaled by oscMult
            if (s.stochK < 20 && s.stochD < 20) {
                int stochPts = (int)((s.stochK < 10 ? 15 : 10) * oscMult);
                longScore += stochPts;
                reasoning.append(String.format("Stoch oversold(K=%.0f,%dpts). ", s.stochK, stochPts));
            } else if (s.stochK > 80 && s.stochD > 80) {
                int stochPts = (int)((s.stochK > 90 ? 15 : 10) * oscMult);
                shortScore += stochPts;
                reasoning.append(String.format("Stoch overbought(K=%.0f,%dpts). ", s.stochK, stochPts));
            }

            // VWAP — régime-aware scoring (max 20 pts)
            // Trend (BB Width > 0.6%) : momentum    — prix > VWAP confirme LONG
            // Range (BB Width 0.3–0.6%): mean-reversion — prix > VWAP = fade → SHORT
            // Squeeze (< 0.3%) : déjà filtré WAIT en amont, pas de points
            double vwapDeltaPct = r2((price - s.vwap) / s.vwap * 100);
            double vwapDistAbs  = Math.abs(vwapDeltaPct);
            int vwapPts = (int) Math.min(20, Math.max(0, (vwapDistAbs - 0.05) / 0.25 * 20));
            boolean vwapTrend = !"RANGE".equals(s.marketRegime);  // TREND or NEUTRAL → momentum ; RANGE → mean-reversion
            boolean priceAboveVwap = price > s.vwap;
            if (vwapPts > 0) {
                if (vwapTrend) {
                    // Momentum : suit la direction de l'écart
                    if (priceAboveVwap) {
                        s.vwapScore = vwapPts; longScore += vwapPts;
                        reasoning.append(String.format("VWAP↑momentum(%+.2f%%,%dpts). ", vwapDeltaPct, vwapPts));
                    } else {
                        s.vwapScore = -vwapPts; shortScore += vwapPts;
                        reasoning.append(String.format("VWAP↓momentum(%.2f%%,%dpts). ", vwapDeltaPct, vwapPts));
                    }
                } else {
                    // Mean-reversion : fade l'écart (direction opposée)
                    if (priceAboveVwap) {
                        s.vwapScore = -vwapPts; shortScore += vwapPts;
                        reasoning.append(String.format("VWAP↑reversion(%+.2f%%,%dpts→SHORT). ", vwapDeltaPct, vwapPts));
                    } else {
                        s.vwapScore = vwapPts; longScore += vwapPts;
                        reasoning.append(String.format("VWAP↓reversion(%.2f%%,%dpts→LONG). ", vwapDeltaPct, vwapPts));
                    }
                }
            }

            // ATR bonus — continuous linear function: 0 pts at 0.04%, 10 pts at ≥0.25%
            // Applied symmetrically (better volatility = better scalping opportunity)
            s.atrScore = (int) Math.min(10, Math.max(0, (s.atrPct - 0.04) / 0.21 * 10));
            longScore  += s.atrScore;
            shortScore += s.atrScore;
            if (s.atrScore > 0) {
                reasoning.append(String.format("Volatilité %.2f%%(%+dpts). ", s.atrPct, s.atrScore));
            }

            // Last candle body direction — confirms momentum (+5 pts)
            boolean bullishBody = closes[n - 1] > candles.get(n - 1).open;
            if (bullishBody) {
                longScore  += 5; s.candleBodyScore = 5;
                reasoning.append("Bougie haussière(+5pts). ");
            } else {
                shortScore += 5; s.candleBodyScore = -5;
                reasoning.append("Bougie baissière(+5pts). ");
            }

            // Indicator conflict: RSI and EMA lean in opposite directions → dampen both
            // Prevents a noise spike in one indicator from reaching the threshold alone
            boolean rsiLeanLong = s.rsi7 < 50;
            boolean emaLeanLong = price > s.ema13;
            if (rsiLeanLong != emaLeanLong && Math.abs(s.rsiScore) >= 12 && Math.abs(s.emaScore) >= 10) {
                int penalty = 10;
                longScore  = Math.max(0, longScore  - penalty);
                shortScore = Math.max(0, shortScore - penalty);
                reasoning.append(String.format("⚠Conflit RSI/EMA(-%dpts). ", penalty));
            }

            // ── Direction decision ────────────────────────────────────────────
            // Raised thresholds require more confluence to reduce false signals.
            // Counter-trend trades need 92 pts (was 85) — very selective.
            boolean upTrend   = price > s.sma50_1m;
            int longThreshold  = upTrend  ? 78 : 92;
            int shortThreshold = !upTrend ? 78 : 92;
            String trendNote   = upTrend ? "↑tendance SMA50" : "↓tendance SMA50";

            if (longScore >= longThreshold) {
                s.direction  = "LONG";
                s.confidence = Math.min(100, longScore);
                reasoning.append(trendNote).append(". ");
            } else if (shortScore >= shortThreshold) {
                s.direction  = "SHORT";
                s.confidence = Math.min(100, shortScore);
                reasoning.append(trendNote).append(". ");
            } else {
                s.direction  = "WAIT";
                s.confidence = Math.max(longScore, shortScore);
                reasoning.append("Signal insuffisant (L:").append(longScore)
                          .append("/").append(longThreshold)
                          .append(" S:").append(shortScore)
                          .append("/").append(shortThreshold).append(").");
            }

            // ── TP / SL (ATR-based, tight for scalping) ───────────────────────
            double atr = s.atr;
            if ("LONG".equals(s.direction)) {
                s.tp1      = r1(price + 0.5 * atr);
                s.tp2      = r1(price + 1.0 * atr);
                s.stopLoss = r1(price - 0.4 * atr);
            } else if ("SHORT".equals(s.direction)) {
                s.tp1      = r1(price - 0.5 * atr);
                s.tp2      = r1(price - 1.0 * atr);
                s.stopLoss = r1(price + 0.4 * atr);
            } else {
                s.tp1      = r1(price + 0.5 * atr);
                s.tp2      = r1(price + 1.0 * atr);
                s.stopLoss = r1(price - 0.4 * atr);
            }

            // P&L % with leverage
            s.tp1PnlPct = r2(pnl(price, s.tp1, s.direction));
            s.tp2PnlPct = r2(pnl(price, s.tp2, s.direction));
            s.slPnlPct  = r2(pnl(price, s.stopLoss, s.direction));

            // Last 100 candles for chart
            s.candles  = candles.subList(Math.max(0, candles.size() - 100), candles.size());
            s.reasoning = reasoning.toString();

        } catch (Exception e) {
            LOG.errorf("[Scalping] Compute error: %s", e.getMessage());
            s.error = e.getMessage();
        }
        return s;
    }

    // ── Fast MACD(fast, slow, signal) ─────────────────────────────────────────

    private double[] calculateFastMACD(List<Double> prices, int fastPeriod, int slowPeriod, int signalPeriod) {
        double emaFast   = ta.calculateEMA(prices, fastPeriod);
        double emaSlow   = ta.calculateEMA(prices, slowPeriod);
        double macdLine  = emaFast - emaSlow;

        // Approximate signal line as EMA of MACD — using last few values
        // For a lightweight approach: use the standard ta.calculateMACD result as base
        // and scale it proportionally for fast periods
        double[] stdMacd = ta.calculateMACD(prices);  // returns [macdLine, signal, histogram] for (12,26,9)
        // Scale the signal line proportionally
        double signalLine = macdLine - stdMacd[2] * (macdLine / (stdMacd[0] != 0 ? stdMacd[0] : 1));

        // Simple approximation: signal = EMA of (fast - slow) over last signalPeriod candles
        // Build a mini MACD line series
        int sz = prices.size();
        if (sz >= slowPeriod + signalPeriod) {
            List<Double> macdSeries = new ArrayList<>();
            for (int i = slowPeriod - 1; i < sz; i++) {
                List<Double> sub = prices.subList(0, i + 1);
                double ef = ta.calculateEMA(sub, fastPeriod);
                double es = ta.calculateEMA(sub, slowPeriod);
                macdSeries.add(ef - es);
            }
            if (macdSeries.size() >= signalPeriod) {
                signalLine = ta.calculateEMA(macdSeries, signalPeriod);
                macdLine   = macdSeries.get(macdSeries.size() - 1);
            }
        }

        double histogram = macdLine - signalLine;
        return new double[]{macdLine, signalLine, histogram};
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private CandleDTO parseKline(List<Object> k) {
        long   time   = ((Number) k.get(0)).longValue() / 1000L;
        double open   = Double.parseDouble(k.get(1).toString());
        double high   = Double.parseDouble(k.get(2).toString());
        double low    = Double.parseDouble(k.get(3).toString());
        double close  = Double.parseDouble(k.get(4).toString());
        double volume = Double.parseDouble(k.get(5).toString());
        return new CandleDTO(time, open, high, low, close, volume);
    }

    private double pnl(double entry, double target, String direction) {
        if (entry == 0) return 0;
        double move = (target - entry) / entry;
        if ("SHORT".equals(direction)) move = -move;
        return move * LEVERAGE * 100;
    }

    private double r1(double v) { return Math.round(v * 10.0) / 10.0; }
    private double r2(double v) { return Math.round(v * 100.0) / 100.0; }
}
