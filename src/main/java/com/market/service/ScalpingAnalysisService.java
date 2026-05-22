package com.market.service;

import com.market.client.BinanceClient;
import com.market.model.CandleDTO;
import com.market.model.MarketStructureResult;
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
 * Multi-timeframe scalping signal for BTC/USDT — v4 "Confluence Sniper".
 *
 * Strategy inspired by professional order-flow and SMC traders:
 *
 *   Pillar 1 — Multi-TF Trend Alignment (max 40 pts)
 *     15m EMA(20/50)  → macro trend  (+15)
 *     5m  EMA(9/21)   → meso trend   (+15)
 *     1m  SMA50+ST    → micro trend  (+10)
 *
 *   Pillar 2 — Momentum Quality (max 40 pts)
 *     RSI(14) momentum zone (+20), MACD(12,26,9) (+10), Stochastic crossover (+10)
 *
 *   Pillar 3 — Volume & Order Flow (max 25 pts)
 *     Taker delta (+15), CVD slope (+5), Volume ratio vs avg (+5)
 *
 *   Bonus — VWAP confluence (+5 near VWAP with momentum)
 *
 * Decision thresholds:
 *   3/3 TFs aligned → 60 pts
 *   2/3 TFs aligned → 68 pts
 *   1/3 TF aligned (non-conflicting) → 88 pts (very strong momentum required)
 *   Conflicting TFs (long+short) → WAIT
 *
 * Hard gates:
 *   - TTM Squeeze (BB inside KC) → WAIT
 *   - ATR(14) < 0.12%            → WAIT (insufficient volatility to cover fees)
 *
 * TP/SL: TP1=1.0×ATR(60%), TP2=2.0×ATR(40%), SL=0.6×ATR  →  R:R ≈ 1.67/3.33
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

    // ─────────────────────────────────────────────────────────────────────────

    private ScalpingSignal compute() {
        ScalpingSignal s = new ScalpingSignal();
        s.timestamp = LocalDateTime.now();

        try {
            // ── 1m candles (200 bars ≈ 3h20) ─────────────────────────────────
            List<List<Object>> raw1m = binanceClient.getKlines("BTCUSDT", "1m", 200);
            if (raw1m == null || raw1m.size() < 30) {
                s.error = "Not enough 1m candle data from Binance";
                return s;
            }

            List<CandleDTO> candles = raw1m.stream().map(this::parseKline).collect(Collectors.toList());
            int n = candles.size();

            double[] highs      = new double[n];
            double[] lows       = new double[n];
            double[] opens      = new double[n];
            double[] closes     = new double[n];
            double[] volumes    = new double[n];
            double[] buyVolumes = new double[n];

            for (int i = 0; i < n; i++) {
                CandleDTO c   = candles.get(i);
                highs[i]      = c.high;
                lows[i]       = c.low;
                opens[i]      = c.open;
                closes[i]     = c.close;
                volumes[i]    = c.volume;
                List<Object> raw = raw1m.get(i);
                buyVolumes[i] = raw.size() > 9
                        ? Double.parseDouble(raw.get(9).toString())
                        : c.volume * 0.5;
            }

            List<Double> closeList = new ArrayList<>();
            for (double c : closes) closeList.add(c);

            double price   = closes[n - 1];
            s.currentPrice = price;
            s.entryPrice   = price;

            // ── 15m macro trend ────────────────────────────────────────────────
            s.trend15m  = "NEUTRAL";
            s.ema20_15m = 0;
            s.ema50_15m = 0;
            s.rsi14_15m = 50;
            try {
                List<List<Object>> raw15m = binanceClient.getKlines("BTCUSDT", "15m", 100);
                if (raw15m != null && raw15m.size() >= 50) {
                    List<Double> c15 = raw15m.stream()
                            .map(k -> Double.parseDouble(k.get(4).toString()))
                            .collect(Collectors.toList());
                    s.ema20_15m = r2(ta.calculateEMA(c15, 20));
                    s.ema50_15m = r2(ta.calculateEMA(c15, 50));
                    s.rsi14_15m = r1(ta.calculateRSI(c15, 14));
                    if      (s.ema20_15m > s.ema50_15m * 1.0001) s.trend15m = "BULLISH";
                    else if (s.ema20_15m < s.ema50_15m * 0.9999) s.trend15m = "BEARISH";
                }
            } catch (Exception ex) {
                LOG.debugf("[Scalping] 15m unavailable: %s", ex.getMessage());
            }

            // ── 5m meso trend ─────────────────────────────────────────────────
            s.bias5m   = "NEUTRAL";
            s.ema9_5m  = 0;
            s.ema21_5m = 0;
            s.rsi14_5m = 50;
            try {
                List<List<Object>> raw5m = binanceClient.getKlines("BTCUSDT", "5m", 100);
                if (raw5m != null && raw5m.size() >= 21) {
                    List<Double> c5 = raw5m.stream()
                            .map(k -> Double.parseDouble(k.get(4).toString()))
                            .collect(Collectors.toList());
                    s.ema9_5m  = r2(ta.calculateEMA(c5, 9));
                    s.ema21_5m = r2(ta.calculateEMA(c5, 21));
                    s.rsi14_5m = r1(ta.calculateRSI(c5, 14));
                    if      (s.ema9_5m > s.ema21_5m * 1.0001) s.bias5m = "LONG";
                    else if (s.ema9_5m < s.ema21_5m * 0.9999) s.bias5m = "SHORT";
                }
            } catch (Exception ex) {
                LOG.debugf("[Scalping] 5m unavailable: %s", ex.getMessage());
            }

            // ── 1m indicators ─────────────────────────────────────────────────

            // RSI(14) — standard, more reliable than RSI(7) on 1m for trend reading
            s.rsi7     = r1(ta.calculateRSI(closeList, 14));   // field named rsi7 for backward compat
            s.rsiPrev  = r1(ta.calculateRSI(closeList.subList(0, n - 1), 14));
            s.rsiPrev2 = r1(ta.calculateRSI(closeList.subList(0, n - 2), 14));
            s.rsiSlope        = r2(s.rsi7   - s.rsiPrev);
            s.rsiAcceleration = r2(s.rsiSlope - r2(s.rsiPrev - s.rsiPrev2));

            // RSI divergence (5-bar lookback)
            double priceSlope5 = closes[n - 1] - closes[n - 5];
            double rsi5ago     = ta.calculateRSI(closeList.subList(0, n - 4), 14);
            double rsiSlope5   = s.rsi7 - rsi5ago;
            if      (priceSlope5 < 0 && rsiSlope5 >  2.0) s.rsiDivergence = "BULLISH";
            else if (priceSlope5 > 0 && rsiSlope5 < -2.0) s.rsiDivergence = "BEARISH";
            else                                            s.rsiDivergence = "NONE";

            // EMAs on 1m
            s.ema5     = r2(ta.calculateEMA(closeList, 8));    // field ema5 = EMA(8) v4
            s.ema13    = r2(ta.calculateEMA(closeList, 13));
            s.ema21    = r2(ta.calculateEMA(closeList, 21));
            s.sma50_1m = n >= 50 ? r2(ta.calculateSMA(closeList, 50)) : r2(ta.calculateSMA(closeList, n));

            // ATR — use max(ATR7, ATR14): captures recent spikes (7) or sustained volatility (14)
            double atr7  = ta.computeATR(highs, lows, closes, 7);
            double atr14 = ta.computeATR(highs, lows, closes, 14);
            s.atr    = Math.max(atr7, atr14);
            s.atrPct = r2(s.atr / price * 100);

            // Bollinger(20) + Keltner(20, 1.5) → TTM Squeeze
            double[] bb    = ta.calculateBollingerBands(closeList, 20);
            double bbUpper = bb[0], bbMid = bb[1], bbLower = bb[2];
            s.bbWidth      = r2((bbUpper - bbLower) / bbMid * 100);
            s.bbState      = s.bbWidth < 0.30 ? "SQUEEZE" : s.bbWidth > 1.0 ? "EXPANSION" : "NORMAL";

            double[] kc   = ta.calculateKeltnerChannels(closeList, highs, lows, 20, 1.5);
            s.kcUpper     = r2(kc[0]);
            s.kcLower     = r2(kc[2]);
            s.ttmSqueezeOn = (bbUpper < kc[0]) && (bbLower > kc[2]);

            // Volume data (always computed — needed even on early WAIT)
            double totalVol20 = 0, buyVol20 = 0;
            for (int i = n - 20; i < n; i++) { totalVol20 += volumes[i]; buyVol20 += buyVolumes[i]; }
            s.volumeDeltaPct   = totalVol20 > 0 ? r2(buyVol20 / totalVol20 * 100) : 50.0;
            s.volumeDeltaTrend = s.volumeDeltaPct > 60 ? "STRONG_BUY"
                    : s.volumeDeltaPct > 52 ? "BUY"
                    : s.volumeDeltaPct < 40 ? "STRONG_SELL"
                    : s.volumeDeltaPct < 48 ? "SELL" : "NEUTRAL";
            double avgVol20 = totalVol20 / 20.0;
            s.volumeRatio   = avgVol20 > 0 ? r2(volumes[n - 1] / avgVol20) : 1.0;

            // ── GATE 1: TTM Squeeze ───────────────────────────────────────────
            if (s.ttmSqueezeOn) {
                s.direction  = "WAIT";
                s.confidence = 0;
                s.reasoning  = String.format("TTM SQUEEZE (BB dans KC) — compression, bbWidth=%.2f%%.", s.bbWidth);
                s.candles    = candles.subList(Math.max(0, n - 100), n);
                return s;
            }

            // ── GATE 2: ATR minimum 0.06% ─────────────────────────────────────
            if (s.atrPct < 0.06) {
                s.direction  = "WAIT";
                s.confidence = 0;
                s.reasoning  = String.format(
                    "ATR trop bas (%.2f%% < 0.06%%) — volatilité insuffisante pour couvrir frais.", s.atrPct);
                s.candles    = candles.subList(Math.max(0, n - 100), n);
                return s;
            }

            // ── Remaining indicators ──────────────────────────────────────────

            // Stochastic(14, 3) — more reliable than (5,3) for momentum reading
            double[] stoch = ta.calculateStochastic(highs, lows, closes, 14, 3);
            s.stochK = r2(stoch[0]);
            s.stochD = r2(stoch[1]);

            // ADX(14)
            double[] adxRes = ta.calculateADX(highs, lows, closes, 14);
            s.adx      = r2(adxRes[0]);
            s.plusDI   = r2(adxRes[1]);
            s.minusDI  = r2(adxRes[2]);
            s.marketRegime = s.adx > 25 ? "TREND" : s.adx < 20 ? "RANGE" : "NEUTRAL";

            // VWAP + SD bands
            double[] vwapBands = ta.calculateVWAPWithBands(highs, lows, closes, volumes);
            s.vwap         = r2(vwapBands[0]);
            s.vwapSd1Upper = r2(vwapBands[1]);
            s.vwapSd1Lower = r2(vwapBands[2]);
            s.vwapSd2Upper = r2(vwapBands[3]);
            s.vwapSd2Lower = r2(vwapBands[4]);

            // MACD(12, 26, 9) — standard parameters
            double[] macd   = buildMACD(closeList, 12, 26, 9);
            s.macdLine      = r2(macd[0]);
            s.macdSignal    = r2(macd[1]);
            s.macdHistogram = r2(macd[2]);

            // CVD(20)
            double[] cvdRes = ta.calculateCVD(buyVolumes, volumes, 20);
            s.cvdSlope = r2(cvdRes[1]);
            s.cvdPct   = r2(cvdRes[1] / cvdRes[2] * 100);
            s.cvdTrend = s.cvdPct >  15 ? "BULLISH"
                    : s.cvdPct >   5 ? "SLIGHTLY_BULLISH"
                    : s.cvdPct < -15 ? "BEARISH"
                    : s.cvdPct <  -5 ? "SLIGHTLY_BEARISH"
                    : "NEUTRAL";

            // Supertrend(10, 3.0) — slightly longer period for fewer whipsaws
            double[] stRes        = ta.calculateSupertrend(highs, lows, closes, 10, 3.0);
            s.supertrendDirection = stRes[0] > 0 ? "LONG" : "SHORT";
            s.supertrendValue     = r2(stRes[1]);

            // Market Structure
            MarketStructureResult ms = ta.detectMarketStructure(candles, 3);
            s.marketStructure1m = ms.type != null ? ms.type.name() : "CONSOLIDATION";

            // ─────────────────────────────────────────────────────────────────
            // SCORING — 3 Pillars + VWAP bonus
            // ─────────────────────────────────────────────────────────────────

            int longScore  = 0;
            int shortScore = 0;
            StringBuilder reason = new StringBuilder();

            reason.append(String.format("ADX=%.1f[%s]. ", s.adx, s.marketRegime));

            // ══════════════════════════════════════════════════════════════════
            // PILLAR 1 — Multi-TF Trend Alignment (max 40 pts)
            // ══════════════════════════════════════════════════════════════════

            int p1Long = 0, p1Short = 0;

            // 15m macro (15 pts) — the most important filter
            if ("BULLISH".equals(s.trend15m)) {
                p1Long  += 15;
                reason.append(String.format("15m↑EMA(%.0f>%.0f,+15). ", s.ema20_15m, s.ema50_15m));
            } else if ("BEARISH".equals(s.trend15m)) {
                p1Short += 15;
                reason.append(String.format("15m↓EMA(%.0f<%.0f,+15). ", s.ema20_15m, s.ema50_15m));
            } else {
                reason.append("15m neutre. ");
            }

            // 5m meso (15 pts)
            if ("LONG".equals(s.bias5m)) {
                p1Long  += 15;
                reason.append(String.format("5m↑EMA(%.0f>%.0f,+15). ", s.ema9_5m, s.ema21_5m));
            } else if ("SHORT".equals(s.bias5m)) {
                p1Short += 15;
                reason.append(String.format("5m↓EMA(%.0f<%.0f,+15). ", s.ema9_5m, s.ema21_5m));
            } else {
                reason.append("5m neutre. ");
            }

            // 1m micro: SMA50 + Supertrend must agree (up to 10 pts)
            boolean above1mSma = price > s.sma50_1m;
            boolean stLong     = "LONG".equals(s.supertrendDirection);
            if (above1mSma && stLong) {
                p1Long  += 10;
                reason.append("1m↑(SMA50+ST,+10). ");
            } else if (!above1mSma && !stLong) {
                p1Short += 10;
                reason.append("1m↓(SMA50+ST,+10). ");
            } else if (above1mSma || stLong) {
                // partial: one agrees
                p1Long  += 5;
                reason.append("1m↑partiel(+5). ");
            } else {
                p1Short += 5;
                reason.append("1m↓partiel(+5). ");
            }

            longScore  += p1Long;
            shortScore += p1Short;
            s.pillar1Score = p1Long > p1Short ? p1Long : -p1Short;

            // Count TF alignments for threshold selection
            s.longTfCount  = 0;
            s.shortTfCount = 0;
            if ("BULLISH".equals(s.trend15m)) s.longTfCount++;
            else if ("BEARISH".equals(s.trend15m)) s.shortTfCount++;
            if ("LONG".equals(s.bias5m)) s.longTfCount++;
            else if ("SHORT".equals(s.bias5m)) s.shortTfCount++;
            if (above1mSma && stLong) s.longTfCount++;
            else if (!above1mSma && !stLong) s.shortTfCount++;

            // Determine trading direction and threshold based on TF alignment
            boolean tradeDir; // true = LONG candidate, false = SHORT candidate
            int threshold;
            if (s.longTfCount == 3) {
                tradeDir  = true;
                threshold = 60;
            } else if (s.shortTfCount == 3) {
                tradeDir  = false;
                threshold = 60;
            } else if (s.longTfCount == 2 && s.longTfCount > s.shortTfCount) {
                tradeDir  = true;
                threshold = 68;
            } else if (s.shortTfCount == 2 && s.shortTfCount > s.longTfCount) {
                tradeDir  = false;
                threshold = 68;
            } else if (s.longTfCount == 1 && s.shortTfCount == 0) {
                // Single TF aligned, no conflict — very high threshold required
                tradeDir  = true;
                threshold = 88;
            } else if (s.shortTfCount == 1 && s.longTfCount == 0) {
                // Single TF aligned, no conflict — very high threshold required
                tradeDir  = false;
                threshold = 88;
            } else {
                // Conflicting TFs (long + short signals) or all neutral — too risky
                s.direction  = "WAIT";
                s.confidence = Math.max(longScore, shortScore);
                reason.append(String.format("TFs conflictuels(L:%d S:%d) — WAIT.", s.longTfCount, s.shortTfCount));
                s.reasoning  = reason.toString();
                populateTargets(s, price, s.atr);
                s.candles    = candles.subList(Math.max(0, n - 100), n);
                return s;
            }

            reason.append(String.format("[%d/3 TFs,seuil=%d] ", tradeDir ? s.longTfCount : s.shortTfCount, threshold));

            // ══════════════════════════════════════════════════════════════════
            // PILLAR 2 — Momentum Quality (max 40 pts)
            // ══════════════════════════════════════════════════════════════════

            int p2Long = 0, p2Short = 0;

            // RSI(14) momentum zone (max 20 pts)
            // Pro approach: trade WITH momentum, not against it.
            // In uptrend: RSI 50-70 = momentum zone, RSI 40-50 = mild, RSI <40 = oversold bounce
            // In downtrend: RSI 30-50 = momentum zone, RSI 50-60 = mild, RSI >60 = overbought bounce
            if (s.rsi7 >= 52 && s.rsi7 <= 72) {
                int pts = (int)((s.rsi7 - 52) / 20.0 * 10 + 10);  // 10–20 pts
                p2Long  += pts;
                reason.append(String.format("RSI-bull-zone(%.0f,+%d). ", s.rsi7, pts));
            } else if (s.rsi7 < 40) {
                p2Long  += 12;  // oversold → potential bounce
                reason.append(String.format("RSI-oversold(%.0f,+12). ", s.rsi7));
            } else if (s.rsi7 >= 40 && s.rsi7 < 52) {
                p2Long  += 5;   // neutral-bullish territory
            }

            if (s.rsi7 >= 28 && s.rsi7 <= 48) {
                int pts = (int)((48 - s.rsi7) / 20.0 * 10 + 10);  // 10–20 pts
                p2Short += pts;
                reason.append(String.format("RSI-bear-zone(%.0f,+%d). ", s.rsi7, pts));
            } else if (s.rsi7 > 60) {
                p2Short += 12;  // overbought → potential drop
                reason.append(String.format("RSI-overbought(%.0f,+12). ", s.rsi7));
            } else if (s.rsi7 > 48 && s.rsi7 <= 60) {
                p2Short += 5;
            }

            // MACD(12,26,9) — histogram direction + acceleration (max 10 pts)
            if (s.macdHistogram > 0) {
                // Stronger signal if histogram is growing (momentum accelerating)
                double prevHisto = macd[3]; // prev histogram stored in slot [3]
                boolean accel    = s.macdHistogram > prevHisto;
                int pts = accel ? 10 : 6;
                p2Long  += pts;
                reason.append(String.format("MACD+histo(accel=%b,+%d). ", accel, pts));
            } else if (s.macdHistogram < 0) {
                double prevHisto = macd[3];
                boolean accel    = s.macdHistogram < prevHisto;
                int pts = accel ? 10 : 6;
                p2Short += pts;
                reason.append(String.format("MACD-histo(accel=%b,+%d). ", accel, pts));
            }

            // Stochastic(14,3) — crossover momentum (max 10 pts)
            // K > D and rising from mid-zone = continuation
            if (s.stochK > s.stochD && s.stochK > 30 && s.stochK < 80) {
                int pts = s.stochK < 50 ? 10 : 6;
                p2Long  += pts;
                reason.append(String.format("Stoch↑cross(K=%.0f,+%d). ", s.stochK, pts));
            }
            if (s.stochK < s.stochD && s.stochK > 20 && s.stochK < 70) {
                int pts = s.stochK > 50 ? 10 : 6;
                p2Short += pts;
                reason.append(String.format("Stoch↓cross(K=%.0f,+%d). ", s.stochK, pts));
            }

            // RSI divergence bonus
            if ("BULLISH".equals(s.rsiDivergence)) {
                p2Long  += 8;
                reason.append("DivRSI↑(+8). ");
            } else if ("BEARISH".equals(s.rsiDivergence)) {
                p2Short += 8;
                reason.append("DivRSI↓(+8). ");
            }

            longScore  += p2Long;
            shortScore += p2Short;
            s.pillar2Score = p2Long > p2Short ? p2Long : -p2Short;
            // Store in legacy fields for backward compat
            s.rsiScore   = (int)(s.rsi7 >= 50 ? (s.rsi7 - 50) * 0.4 : -(50 - s.rsi7) * 0.4);
            s.macdScore  = s.macdHistogram > 0 ? p2Long : -p2Short;

            // ══════════════════════════════════════════════════════════════════
            // PILLAR 3 — Volume & Order Flow (max 25 pts)
            // ══════════════════════════════════════════════════════════════════

            int p3Long = 0, p3Short = 0;

            // Taker buy delta (max 15 pts) — most direct measure of order flow
            if (s.volumeDeltaPct > 62) {
                p3Long  += 15; reason.append(String.format("Delta++buy(%.0f%%,+15). ", s.volumeDeltaPct));
            } else if (s.volumeDeltaPct > 54) {
                p3Long  += 8;  reason.append(String.format("Delta+buy(%.0f%%,+8). ", s.volumeDeltaPct));
            } else if (s.volumeDeltaPct < 38) {
                p3Short += 15; reason.append(String.format("Delta++sell(%.0f%%,+15). ", s.volumeDeltaPct));
            } else if (s.volumeDeltaPct < 46) {
                p3Short += 8;  reason.append(String.format("Delta+sell(%.0f%%,+8). ", s.volumeDeltaPct));
            }
            s.volScore = p3Long > 0 ? p3Long : -p3Short;

            // CVD slope (max 5 pts)
            if (s.cvdPct > 12) {
                p3Long  += 5; reason.append("CVD↑(+5). ");
            } else if (s.cvdPct < -12) {
                p3Short += 5; reason.append("CVD↓(+5). ");
            }
            s.cvdScore = p3Long > 0 ? 5 : -5;

            // Volume above average = confirms participation (max 5 pts, symmetric)
            if (s.volumeRatio >= 1.3) {
                p3Long  += 5;
                p3Short += 5;
                reason.append(String.format("Vol↑(×%.1f,+5). ", s.volumeRatio));
            } else if (s.volumeRatio >= 1.0) {
                p3Long  += 2;
                p3Short += 2;
            }

            longScore  += p3Long;
            shortScore += p3Short;
            s.pillar3Score = p3Long > p3Short ? p3Long : -p3Short;

            // ══════════════════════════════════════════════════════════════════
            // VWAP confluence bonus (max 5 pts — quality confirmation)
            // ══════════════════════════════════════════════════════════════════
            double vwapDist = (price - s.vwap) / s.vwap * 100;
            if (Math.abs(vwapDist) < 0.25 && s.atr > 0) {
                // Near VWAP with momentum direction confirmation
                if (s.rsiSlope > 0) {
                    longScore  += 5;
                    reason.append(String.format("VWAPbounce+mom(%.2f%%,+5). ", vwapDist));
                } else if (s.rsiSlope < 0) {
                    shortScore += 5;
                    reason.append(String.format("VWAPbounce-mom(%.2f%%,+5). ", vwapDist));
                }
            }
            // VWAP score — regime-aware for backward compat with tests
            // TREND/NEUTRAL: momentum (positive above VWAP, negative below)
            // RANGE: mean-reversion (negative above VWAP, positive below)
            double signedVwapDist = "RANGE".equals(s.marketRegime) ? -vwapDist : vwapDist;
            s.vwapScore = (int)(signedVwapDist * 5);

            // Market structure — aligned with direction adds bonus
            switch (ms.type) {
                case BULL_TREND:
                    longScore  += 8; s.marketStructureScore =  8;
                    reason.append("MS BullTrend(+8). "); break;
                case BREAKOUT_UP:
                    longScore  += 5; s.marketStructureScore =  5;
                    reason.append("MS Breakout↑(+5). "); break;
                case BEAR_TREND:
                    shortScore += 8; s.marketStructureScore = -8;
                    reason.append("MS BearTrend(+8). "); break;
                case BREAKOUT_DOWN:
                    shortScore += 5; s.marketStructureScore = -5;
                    reason.append("MS Breakout↓(+5). "); break;
                default:
                    s.marketStructureScore = 0;
                    break;
            }

            // ATR bonus for diagnostics (symmetric)
            s.atrScore = (int) Math.min(10, Math.max(0, (s.atrPct - 0.06) / 0.14 * 10));

            // Last candle body (small bonus for entry confirmation)
            boolean bullishBody = closes[n - 1] > opens[n - 1];
            if (bullishBody) {
                longScore  += 3; s.candleBodyScore =  3;
            } else {
                shortScore += 3; s.candleBodyScore = -3;
            }

            // ══════════════════════════════════════════════════════════════════
            // DECISION
            // ══════════════════════════════════════════════════════════════════

            int candidateScore = tradeDir ? longScore : shortScore;

            if (candidateScore >= threshold) {
                s.direction  = tradeDir ? "LONG" : "SHORT";
                s.confidence = Math.min(100, candidateScore);
                reason.append(String.format("%s(%dpts>=%d). ",
                    s.direction, candidateScore, threshold));
            } else {
                s.direction  = "WAIT";
                s.confidence = candidateScore;
                reason.append(String.format("Score insuffisant (%d/%d) — WAIT.", candidateScore, threshold));
            }

            populateTargets(s, price, s.atr);
            s.candles   = candles.subList(Math.max(0, n - 100), n);
            s.reasoning = reason.toString();

        } catch (Exception e) {
            LOG.errorf("[Scalping] Compute error: %s", e.getMessage());
            s.error = e.getMessage();
        }
        return s;
    }

    // ── MACD(fast, slow, signal) — returns [macdLine, signalLine, histogram, prevHistogram] ──

    private double[] buildMACD(List<Double> prices, int fast, int slow, int sig) {
        int sz = prices.size();
        if (sz < slow + sig) {
            double diff = ta.calculateEMA(prices, fast) - ta.calculateEMA(prices, slow);
            return new double[]{diff, diff, 0, 0};
        }

        double kFast = 2.0 / (fast + 1);
        double kSlow = 2.0 / (slow + 1);
        double kSig  = 2.0 / (sig  + 1);

        double emaFast = 0;
        for (int i = 0; i < fast; i++) emaFast += prices.get(i);
        emaFast /= fast;
        for (int i = fast; i < slow; i++)
            emaFast = prices.get(i) * kFast + emaFast * (1 - kFast);

        double emaSlow = 0;
        for (int i = 0; i < slow; i++) emaSlow += prices.get(i);
        emaSlow /= slow;

        List<Double> macdSeries = new ArrayList<>();
        macdSeries.add(emaFast - emaSlow);
        for (int i = slow; i < sz; i++) {
            emaFast = prices.get(i) * kFast + emaFast * (1 - kFast);
            emaSlow = prices.get(i) * kSlow + emaSlow * (1 - kSlow);
            macdSeries.add(emaFast - emaSlow);
        }

        double prevSigEma = 0;
        double sigEma = 0;
        for (int i = 0; i < sig; i++) sigEma += macdSeries.get(i);
        sigEma /= sig;
        for (int i = sig; i < macdSeries.size(); i++) {
            prevSigEma = sigEma;
            sigEma = macdSeries.get(i) * kSig + sigEma * (1 - kSig);
        }

        double macdLine      = macdSeries.get(macdSeries.size() - 1);
        double histogram     = macdLine - sigEma;
        double prevHistogram = macdSeries.size() > sig
                ? macdSeries.get(macdSeries.size() - 2) - prevSigEma
                : histogram;

        return new double[]{macdLine, sigEma, histogram, prevHistogram};
    }

    // ── TP / SL calculation ────────────────────────────────────────────────────

    private void populateTargets(ScalpingSignal s, double price, double atr) {
        if ("LONG".equals(s.direction)) {
            s.tp1      = r1(price + 1.0 * atr);
            s.tp2      = r1(price + 2.0 * atr);
            s.stopLoss = r1(price - 0.6 * atr);
        } else if ("SHORT".equals(s.direction)) {
            s.tp1      = r1(price - 1.0 * atr);
            s.tp2      = r1(price - 2.0 * atr);
            s.stopLoss = r1(price + 0.6 * atr);
        } else {
            s.tp1      = r1(price + 1.0 * atr);
            s.tp2      = r1(price + 2.0 * atr);
            s.stopLoss = r1(price - 0.6 * atr);
        }
        s.tp1PnlPct = r2(pnl(price, s.tp1, s.direction));
        s.tp2PnlPct = r2(pnl(price, s.tp2, s.direction));
        s.slPnlPct  = r2(pnl(price, s.stopLoss, s.direction));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

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

    private double r1(double v) { return Math.round(v * 10.0)   / 10.0; }
    private double r2(double v) { return Math.round(v * 100.0) / 100.0; }
}
