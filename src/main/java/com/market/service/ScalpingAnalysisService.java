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
 * Computes ultra-short-term scalping signals for BTC/USDT based on 1m candles.
 *
 * Indicators (1m timeframe):
 *   RSI(7) + dynamics (slope, acceleration, divergence)
 *   EMA(5/13/21) — cross + triple-stack alignment
 *   MACD(6,13,4) — faster MACD adapted for 1m
 *   Supertrend(7,3) — ATR-based trend direction
 *   Volume Delta (takerBuy/total, 20 candles)
 *   CVD — Cumulative Volume Delta (net accumulation)
 *   VWAP + ±1σ/±2σ SD bands (regime-aware scoring)
 *   Stochastic(5,3)
 *   ATR(7) + Bollinger(20) + Keltner(20,1.5) for TTM Squeeze
 *   Market Structure 1m (mini HH/HL/LH/LL pivots)
 *
 * Higher TF:
 *   5m EMA(9/21) bias — macro direction filter
 *
 * Filters:
 *   - TTM Squeeze (BB inside KC — compression vraie seulement) → WAIT
 *   - ATR gate < 0.05% → WAIT
 *
 * TP/SL are ATR-based, very tight for scalping:
 *   TP1 = 0.5×ATR (60% qty), TP2 = 1.0×ATR (40% qty), SL = 0.4×ATR
 *
 * Signal: longScore/shortScore >= 55 (with-trend) / 72 (counter-trend SMA50)
 * Cache TTL: 10 seconds.
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
            // ── 1m candles (200 × 1m ≈ 3h20) ─────────────────────────────────
            List<List<Object>> raw1m = binanceClient.getKlines("BTCUSDT", "1m", 200);
            if (raw1m == null || raw1m.size() < 30) {
                s.error = "Not enough 1m candle data from Binance";
                return s;
            }

            List<CandleDTO> candles = raw1m.stream().map(this::parseKline).collect(Collectors.toList());
            int n = candles.size();

            double[] highs      = new double[n];
            double[] lows       = new double[n];
            double[] closes     = new double[n];
            double[] volumes    = new double[n];
            double[] buyVolumes = new double[n];

            for (int i = 0; i < n; i++) {
                CandleDTO c  = candles.get(i);
                highs[i]     = c.high;
                lows[i]      = c.low;
                closes[i]    = c.close;
                volumes[i]   = c.volume;
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

            // ── 5m higher-timeframe bias (non-blocking) ────────────────────────
            s.bias5m   = "NEUTRAL";
            s.ema9_5m  = 0;
            s.ema21_5m = 0;
            try {
                List<List<Object>> raw5m = binanceClient.getKlines("BTCUSDT", "5m", 50);
                if (raw5m != null && raw5m.size() >= 21) {
                    List<Double> closes5m = raw5m.stream()
                            .map(k -> Double.parseDouble(k.get(4).toString()))
                            .collect(Collectors.toList());
                    s.ema9_5m  = r2(ta.calculateEMA(closes5m, 9));
                    s.ema21_5m = r2(ta.calculateEMA(closes5m, 21));
                    if (s.ema9_5m > s.ema21_5m * 1.0002)      s.bias5m = "LONG";
                    else if (s.ema9_5m < s.ema21_5m * 0.9998) s.bias5m = "SHORT";
                }
            } catch (Exception ex) {
                LOG.debugf("[Scalping] 5m candles unavailable: %s", ex.getMessage());
            }

            // ── 1m indicators ──────────────────────────────────────────────────

            // RSI(7)
            s.rsi7     = ta.calculateRSI(closeList, 7);
            s.rsiPrev  = ta.calculateRSI(closeList.subList(0, n - 1), 7);
            s.rsiPrev2 = ta.calculateRSI(closeList.subList(0, n - 2), 7);
            s.rsiSlope        = r2(s.rsi7   - s.rsiPrev);
            s.rsiAcceleration = r2(s.rsiSlope - r2(s.rsiPrev - s.rsiPrev2));

            // RSI divergence over last 5 candles (threshold 3.0 to cut 1m noise)
            double priceSlope5 = closes[n - 1] - closes[n - 5];
            double rsi5ago     = ta.calculateRSI(closeList.subList(0, n - 4), 7);
            double rsiSlope5   = s.rsi7 - rsi5ago;
            if      (priceSlope5 < 0 && rsiSlope5 >  3.0) s.rsiDivergence = "BULLISH";
            else if (priceSlope5 > 0 && rsiSlope5 < -3.0) s.rsiDivergence = "BEARISH";
            else                                            s.rsiDivergence = "NONE";

            // EMA(5), EMA(13), EMA(21)
            s.ema5  = ta.calculateEMA(closeList, 5);
            s.ema13 = ta.calculateEMA(closeList, 13);
            s.ema21 = ta.calculateEMA(closeList, 21);

            // SMA(50) trend filter
            s.sma50_1m = n >= 50 ? ta.calculateSMA(closeList, 50) : s.ema13;

            // ATR(7)
            s.atr    = ta.computeATR(highs, lows, closes, 7);
            s.atrPct = r2(s.atr / price * 100);

            // Bollinger Bands(20)
            double[] bb      = ta.calculateBollingerBands(closeList, 20);
            double bbUpper   = bb[0], bbMid = bb[1], bbLower = bb[2];
            s.bbWidth = r2((bbUpper - bbLower) / bbMid * 100);
            s.bbState = s.bbWidth < 0.30 ? "SQUEEZE" : s.bbWidth > 1.0 ? "EXPANSION" : "NORMAL";

            // Keltner Channels(20, 1.5) for TTM Squeeze
            double[] kc   = ta.calculateKeltnerChannels(closeList, highs, lows, 20, 1.5);
            s.kcUpper     = r2(kc[0]);
            s.kcLower     = r2(kc[2]);
            // TTM Squeeze: ON when BB is entirely inside Keltner Channels
            s.ttmSqueezeOn = (bbUpper < kc[0]) && (bbLower > kc[2]);

            // Volume Delta pre-computed for diagnostics (available even on WAIT)
            {
                double tv = 0, tb = 0;
                for (int i = n - 20; i < n; i++) { tv += volumes[i]; tb += buyVolumes[i]; }
                s.volumeDeltaPct   = tv > 0 ? r2(tb / tv * 100) : 50.0;
                s.volumeDeltaTrend = s.volumeDeltaPct > 60 ? "STRONG_BUY"
                        : s.volumeDeltaPct > 52 ? "BUY"
                        : s.volumeDeltaPct < 40 ? "STRONG_SELL"
                        : s.volumeDeltaPct < 48 ? "SELL" : "NEUTRAL";
            }

            // ── TTM Squeeze gate — bloque uniquement la compression vraie (BB dans KC) ─
            // Le bbState SQUEEZE seul (bbWidth<0.30%) est trop fréquent sur 1m et redondant
            // avec l'ATR gate ; on ne bloque que le TTM Squeeze au sens strict.
            if (s.ttmSqueezeOn) {
                s.direction  = "WAIT";
                s.confidence = 0;
                s.reasoning  = String.format(
                    "TTM SQUEEZE (BB dans KC) — compression, bbWidth=%.2f%%, pas de trade.",
                    s.bbWidth);
                s.candles    = candles.subList(Math.max(0, n - 100), n);
                return s;
            }

            // ── ATR gate (0.05% minimum — inclut sessions asiatiques 1m BTC) ────
            if (s.atrPct < 0.05) {
                s.direction  = "WAIT";
                s.confidence = 0;
                s.reasoning  = String.format(
                    "ATR trop bas (%.2f%%) — volatilité insuffisante pour scalper.", s.atrPct);
                s.candles    = candles.subList(Math.max(0, n - 100), n);
                return s;
            }

            // Stochastic(5,3)
            double[] stoch = ta.calculateStochastic(highs, lows, closes, 5, 3);
            s.stochK = r2(stoch[0]);
            s.stochD = r2(stoch[1]);

            // ADX(14) — regime detection
            double[] adxResult = ta.calculateADX(highs, lows, closes, 14);
            s.adx      = r2(adxResult[0]);
            s.plusDI   = r2(adxResult[1]);
            s.minusDI  = r2(adxResult[2]);
            s.marketRegime = s.adx > 25 ? "TREND" : s.adx < 20 ? "RANGE" : "NEUTRAL";

            // VWAP with ±SD bands
            double[] vwapBands = ta.calculateVWAPWithBands(highs, lows, closes, volumes);
            s.vwap        = r2(vwapBands[0]);
            s.vwapSd1Upper = r2(vwapBands[1]);
            s.vwapSd1Lower = r2(vwapBands[2]);
            s.vwapSd2Upper = r2(vwapBands[3]);
            s.vwapSd2Lower = r2(vwapBands[4]);

            // MACD(6, 13, 4) — O(n) iterative computation
            double[] macd   = calculateFastMACD(closeList, 6, 13, 4);
            s.macdLine      = r2(macd[0]);
            s.macdSignal    = r2(macd[1]);
            s.macdHistogram = r2(macd[2]);

            // CVD (Cumulative Volume Delta, 20-bar slope)
            double[] cvdResult = ta.calculateCVD(buyVolumes, volumes, 20);
            s.cvdSlope = r2(cvdResult[1]);
            s.cvdPct   = r2(cvdResult[1] / cvdResult[2] * 100);
            s.cvdTrend = s.cvdPct >  15 ? "BULLISH"
                    : s.cvdPct >   5 ? "SLIGHTLY_BULLISH"
                    : s.cvdPct < -15 ? "BEARISH"
                    : s.cvdPct <  -5 ? "SLIGHTLY_BEARISH"
                    : "NEUTRAL";

            // Supertrend(7, 3)
            double[] stResult     = ta.calculateSupertrend(highs, lows, closes, 7, 3.0);
            s.supertrendDirection = stResult[0] > 0 ? "LONG" : "SHORT";
            s.supertrendValue     = r2(stResult[1]);

            // Market Structure 1m (mini-pivots, strength=3)
            MarketStructureResult ms = ta.detectMarketStructure(candles, 3);
            s.marketStructure1m = ms.type != null ? ms.type.name() : "CONSOLIDATION";

            // ── Scoring ───────────────────────────────────────────────────────

            int longScore  = 0;
            int shortScore = 0;
            StringBuilder reasoning = new StringBuilder();

            // Regime multipliers (ADX-based):
            //   TREND  (ADX>25) → EMA/MACD/Supertrend ×1.5, RSI/Stoch ×0.7
            //   RANGE  (ADX<20) → RSI/Stoch ×1.5, EMA/MACD/Supertrend ×0.7, VWAP = mean-reversion
            //   NEUTRAL (20–25) → all ×1.0
            double oscMult   = "TREND".equals(s.marketRegime) ? 0.7 : "RANGE".equals(s.marketRegime) ? 1.5 : 1.0;
            double trendMult = "TREND".equals(s.marketRegime) ? 1.5 : "RANGE".equals(s.marketRegime) ? 0.7 : 1.0;
            reasoning.append(String.format("ADX=%.1f[%s](osc×%.1f,trend×%.1f). ",
                s.adx, s.marketRegime, oscMult, trendMult));

            // ── RSI(7) ±25×oscMult ────────────────────────────────────────────
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

            // ── RSI dynamics: slope (+5), acceleration (+5), divergence (+7) ──
            int rsiDynLong = 0, rsiDynShort = 0;
            if (s.rsiSlope > 0.5) {
                int pts = (int)(5 * oscMult); rsiDynLong  += pts;
                reasoning.append(String.format("RSI↑pente+%.1f(%dpts). ", s.rsiSlope, pts));
            } else if (s.rsiSlope < -0.5) {
                int pts = (int)(5 * oscMult); rsiDynShort += pts;
                reasoning.append(String.format("RSI↓pente%.1f(%dpts). ", s.rsiSlope, pts));
            }
            if (s.rsiAcceleration > 0.3) {
                int pts = (int)(5 * oscMult); rsiDynLong  += pts;
                reasoning.append(String.format("RSI accél+%.1f(%dpts). ", s.rsiAcceleration, pts));
            } else if (s.rsiAcceleration < -0.3) {
                int pts = (int)(5 * oscMult); rsiDynShort += pts;
                reasoning.append(String.format("RSI accél%.1f(%dpts). ", s.rsiAcceleration, pts));
            }
            if ("BULLISH".equals(s.rsiDivergence)) {
                int pts = (int)(7 * oscMult); rsiDynLong  += pts;
                reasoning.append(String.format("DivRSI haussière(%dpts). ", pts));
            } else if ("BEARISH".equals(s.rsiDivergence)) {
                int pts = (int)(7 * oscMult); rsiDynShort += pts;
                reasoning.append(String.format("DivRSI baissière(%dpts). ", pts));
            }
            s.rsiDynScore = rsiDynLong > rsiDynShort ? rsiDynLong : -rsiDynShort;
            longScore  += rsiDynLong;
            shortScore += rsiDynShort;

            // ── EMA cross ±25×trendMult ───────────────────────────────────────
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
                reasoning.append(String.format("Prix>EMA13(%dpts). ", pts));
            } else if (price < s.ema13) {
                int pts = (int)(10 * trendMult);
                s.emaScore = -pts; shortScore += pts;
                reasoning.append(String.format("Prix<EMA13(%dpts). ", pts));
            }

            // ── EMA(21) triple-stack ±10×trendMult ───────────────────────────
            if (price > s.ema5 && s.ema5 > s.ema13 && s.ema13 > s.ema21) {
                int pts = (int)(10 * trendMult);
                s.ema21Score = pts; longScore += pts;
                reasoning.append(String.format("TripleEMA↑(%dpts). ", pts));
            } else if (price < s.ema5 && s.ema5 < s.ema13 && s.ema13 < s.ema21) {
                int pts = (int)(10 * trendMult);
                s.ema21Score = -pts; shortScore += pts;
                reasoning.append(String.format("TripleEMA↓(%dpts). ", pts));
            }

            // ── MACD histogram proportional ±20×trendMult ────────────────────
            double macdStrength = s.atr > 0 ? Math.abs(s.macdHistogram) / s.atr : 0;
            int macdPts = (int)(Math.min(20, Math.max(3, macdStrength * 40)) * trendMult);
            if (s.macdHistogram > 0) {
                s.macdScore = macdPts; longScore += macdPts;
                reasoning.append(String.format("MACD+%.2f(%dpts). ", s.macdHistogram, macdPts));
            } else if (s.macdHistogram < 0) {
                s.macdScore = -macdPts; shortScore += macdPts;
                reasoning.append(String.format("MACD%.2f(%dpts). ", s.macdHistogram, macdPts));
            }

            // ── Supertrend(7,3) ±15×trendMult ────────────────────────────────
            {
                int pts = (int)(15 * trendMult);
                if ("LONG".equals(s.supertrendDirection)) {
                    s.supertrendScore = pts; longScore += pts;
                    reasoning.append(String.format("Supertrend↑(%.0f,%dpts). ", s.supertrendValue, pts));
                } else {
                    s.supertrendScore = -pts; shortScore += pts;
                    reasoning.append(String.format("Supertrend↓(%.0f,%dpts). ", s.supertrendValue, pts));
                }
            }

            // ── Volume Delta ±25 ─────────────────────────────────────────────
            if (s.volumeDeltaPct > 60) {
                s.volScore = 25; longScore += 25;
                reasoning.append("FortAchat(").append((int)s.volumeDeltaPct).append("%). ");
            } else if (s.volumeDeltaPct > 52) {
                s.volScore = 12; longScore += 12;
                reasoning.append("Achat(").append((int)s.volumeDeltaPct).append("%). ");
            } else if (s.volumeDeltaPct < 40) {
                s.volScore = -25; shortScore += 25;
                reasoning.append("ForteVente(").append((int)s.volumeDeltaPct).append("%). ");
            } else if (s.volumeDeltaPct < 48) {
                s.volScore = -12; shortScore += 12;
                reasoning.append("Vente(").append((int)s.volumeDeltaPct).append("%). ");
            }

            // ── CVD ±15 ──────────────────────────────────────────────────────
            if (s.cvdPct > 20) {
                s.cvdScore = 15; longScore += 15;
                reasoning.append(String.format("CVD fort achat(%.0f%%,+15pts). ", s.cvdPct));
            } else if (s.cvdPct > 5) {
                s.cvdScore = 7; longScore += 7;
                reasoning.append(String.format("CVD achat(%.0f%%,+7pts). ", s.cvdPct));
            } else if (s.cvdPct < -20) {
                s.cvdScore = -15; shortScore += 15;
                reasoning.append(String.format("CVD forte vente(%.0f%%,+15pts). ", s.cvdPct));
            } else if (s.cvdPct < -5) {
                s.cvdScore = -7; shortScore += 7;
                reasoning.append(String.format("CVD vente(%.0f%%,+7pts). ", s.cvdPct));
            }

            // ── Stochastic ±15×oscMult ────────────────────────────────────────
            if (s.stochK < 20 && s.stochD < 20) {
                int stochPts = (int)((s.stochK < 10 ? 15 : 10) * oscMult);
                longScore += stochPts;
                reasoning.append(String.format("Stoch oversold(K=%.0f,%dpts). ", s.stochK, stochPts));
            } else if (s.stochK > 80 && s.stochD > 80) {
                int stochPts = (int)((s.stochK > 90 ? 15 : 10) * oscMult);
                shortScore += stochPts;
                reasoning.append(String.format("Stoch overbought(K=%.0f,%dpts). ", s.stochK, stochPts));
            }

            // ── VWAP SD-band scoring ±20 (regime-aware) ──────────────────────
            boolean priceAboveVwap = price > s.vwap;
            int vwapPts = 0;
            String vwapZone = "";
            if (price >= s.vwapSd2Upper)      { vwapPts = 20; vwapZone = "+2σ"; }
            else if (price >= s.vwapSd1Upper)  { vwapPts = 12; vwapZone = "+1σ"; }
            else if (price > s.vwap)           { vwapPts =  5; vwapZone = "+0σ"; }
            else if (price <= s.vwapSd2Lower)  { vwapPts = 20; vwapZone = "-2σ"; }
            else if (price <= s.vwapSd1Lower)  { vwapPts = 12; vwapZone = "-1σ"; }
            else if (price < s.vwap)           { vwapPts =  5; vwapZone = "-0σ"; }

            if (vwapPts > 0) {
                boolean vwapMomentum = !"RANGE".equals(s.marketRegime);
                if (vwapMomentum) {
                    if (priceAboveVwap) {
                        s.vwapScore = vwapPts; longScore += vwapPts;
                        reasoning.append(String.format("VWAP↑momentum(%s,%dpts). ", vwapZone, vwapPts));
                    } else {
                        s.vwapScore = -vwapPts; shortScore += vwapPts;
                        reasoning.append(String.format("VWAP↓momentum(%s,%dpts). ", vwapZone, vwapPts));
                    }
                } else {
                    // RANGE: fade the extremes (mean-reversion)
                    if (priceAboveVwap) {
                        s.vwapScore = -vwapPts; shortScore += vwapPts;
                        reasoning.append(String.format("VWAP↑reversion(%s,%dpts→SHORT). ", vwapZone, vwapPts));
                    } else {
                        s.vwapScore = vwapPts; longScore += vwapPts;
                        reasoning.append(String.format("VWAP↓reversion(%s,%dpts→LONG). ", vwapZone, vwapPts));
                    }
                }
            }

            // ── 5m bias ±12 ───────────────────────────────────────────────────
            if ("LONG".equals(s.bias5m)) {
                s.bias5mScore = 12; longScore += 12;
                reasoning.append("Bias5m↑(+12pts). ");
            } else if ("SHORT".equals(s.bias5m)) {
                s.bias5mScore = -12; shortScore += 12;
                reasoning.append("Bias5m↓(+12pts). ");
            }

            // ── Market Structure 1m ±15 ──────────────────────────────────────
            switch (ms.type) {
                case BULL_TREND:
                    s.marketStructureScore =  15; longScore  += 15;
                    reasoning.append("MS BullTrend(+15pts). "); break;
                case BREAKOUT_UP:
                    s.marketStructureScore =  10; longScore  += 10;
                    reasoning.append("MS Breakout↑(+10pts). "); break;
                case BEAR_TREND:
                    s.marketStructureScore = -15; shortScore += 15;
                    reasoning.append("MS BearTrend(+15pts). "); break;
                case BREAKOUT_DOWN:
                    s.marketStructureScore = -10; shortScore += 10;
                    reasoning.append("MS Breakout↓(+10pts). "); break;
                default:
                    s.marketStructureScore = 0; // CONSOLIDATION — neutral
                    break;
            }

            // ── ATR bonus +10 (symmetric, rewards active volatility) ─────────
            s.atrScore = (int) Math.min(10, Math.max(0, (s.atrPct - 0.08) / 0.17 * 10));
            longScore  += s.atrScore;
            shortScore += s.atrScore;
            if (s.atrScore > 0)
                reasoning.append(String.format("Volatilité %.2f%%(+%dpts). ", s.atrPct, s.atrScore));

            // ── Last candle body direction +5 ─────────────────────────────────
            boolean bullishBody = closes[n - 1] > candles.get(n - 1).open;
            if (bullishBody) {
                longScore  += 5; s.candleBodyScore =  5;
                reasoning.append("Bougie↑(+5pts). ");
            } else {
                shortScore += 5; s.candleBodyScore = -5;
                reasoning.append("Bougie↓(+5pts). ");
            }

            // ── Indicator conflict: RSI lean ≠ EMA lean → dampen both ─────────
            boolean rsiLeanLong = s.rsi7 < 50;
            boolean emaLeanLong = price > s.ema13;
            if (rsiLeanLong != emaLeanLong && Math.abs(s.rsiScore) >= 12 && Math.abs(s.emaScore) >= 10) {
                longScore  = Math.max(0, longScore  - 10);
                shortScore = Math.max(0, shortScore - 10);
                reasoning.append("⚠Conflit RSI/EMA(-10pts). ");
            }

            // ── Direction decision ────────────────────────────────────────────
            // Seuils calibrés pour le régime 1m BTC :
            //   55 avec tendance  (~3-4 indicateurs alignés)
            //   72 contre tendance (~5+ indicateurs forts requis)
            boolean upTrend       = price > s.sma50_1m;
            int longThreshold     = upTrend  ? 55 : 72;
            int shortThreshold    = !upTrend ? 55 : 72;
            String trendNote      = upTrend ? "↑SMA50" : "↓SMA50";

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

            // ── TP1 / TP2 / SL (ATR-based, tight for scalping) ───────────────
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

            s.tp1PnlPct = r2(pnl(price, s.tp1, s.direction));
            s.tp2PnlPct = r2(pnl(price, s.tp2, s.direction));
            s.slPnlPct  = r2(pnl(price, s.stopLoss, s.direction));

            s.candles   = candles.subList(Math.max(0, n - 100), n);
            s.reasoning = reasoning.toString();

        } catch (Exception e) {
            LOG.errorf("[Scalping] Compute error: %s", e.getMessage());
            s.error = e.getMessage();
        }
        return s;
    }

    // ── Fast MACD(fast, slow, signal) — O(n) iterative ───────────────────────

    private double[] calculateFastMACD(List<Double> prices, int fastPeriod, int slowPeriod, int signalPeriod) {
        int sz = prices.size();
        if (sz < slowPeriod + signalPeriod) {
            double diff = ta.calculateEMA(prices, fastPeriod) - ta.calculateEMA(prices, slowPeriod);
            return new double[]{diff, diff, 0};
        }

        double kFast   = 2.0 / (fastPeriod   + 1);
        double kSlow   = 2.0 / (slowPeriod   + 1);
        double kSignal = 2.0 / (signalPeriod + 1);

        // Initialize fast EMA with SMA(fastPeriod), advance it to index slowPeriod-1
        double emaFast = 0;
        for (int i = 0; i < fastPeriod; i++) emaFast += prices.get(i);
        emaFast /= fastPeriod;
        for (int i = fastPeriod; i < slowPeriod; i++)
            emaFast = prices.get(i) * kFast + emaFast * (1 - kFast);

        // Initialize slow EMA with SMA(slowPeriod)
        double emaSlow = 0;
        for (int i = 0; i < slowPeriod; i++) emaSlow += prices.get(i);
        emaSlow /= slowPeriod;

        // Build MACD line series from index slowPeriod onward
        List<Double> macdSeries = new ArrayList<>();
        macdSeries.add(emaFast - emaSlow);
        for (int i = slowPeriod; i < sz; i++) {
            emaFast = prices.get(i) * kFast + emaFast * (1 - kFast);
            emaSlow = prices.get(i) * kSlow + emaSlow * (1 - kSlow);
            macdSeries.add(emaFast - emaSlow);
        }

        // Signal line = EMA(signalPeriod) of MACD series
        double sigEma = 0;
        for (int i = 0; i < signalPeriod; i++) sigEma += macdSeries.get(i);
        sigEma /= signalPeriod;
        for (int i = signalPeriod; i < macdSeries.size(); i++)
            sigEma = macdSeries.get(i) * kSignal + sigEma * (1 - kSignal);

        double macdLine  = macdSeries.get(macdSeries.size() - 1);
        double histogram = macdLine - sigEma;
        return new double[]{macdLine, sigEma, histogram};
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

    private double r1(double v) { return Math.round(v * 10.0)   / 10.0; }
    private double r2(double v) { return Math.round(v * 100.0) / 100.0; }
}
