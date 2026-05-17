package com.market.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.market.client.BinanceClient;
import com.market.model.BitcoinSignal;
import com.market.model.CandleDTO;
import com.market.model.MarketStructureResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Computes intraday signals for BTC/USDT futures with x10 leverage.
 *
 * Indicators used (on 1h candles):
 *   RSI(14), EMA(9), EMA(21), MACD(12,26,9), Bollinger(20,±2σ), ATR(14)
 *
 * TP/SL are ATR-based:
 *   SL  = entry ± 1.0 × ATR
 *   TP1 = entry ∓ 1.0 × ATR  (R/R 1:1)
 *   TP2 = entry ∓ 2.0 × ATR  (R/R 1:2)
 *   TP3 = entry ∓ 3.0 × ATR  (R/R 1:3)
 */
@ApplicationScoped
public class CryptoAnalysisService {

    private static final Logger       LOG    = Logger.getLogger(CryptoAnalysisService.class);
    private static final int          LEVERAGE = 10;
    private static final ObjectMapper MAPPER   = new ObjectMapper();

    @Inject
    @RestClient
    BinanceClient binanceClient;

    @Inject
    TechnicalAnalysisService ta;

    @Inject
    TelegramAlertService telegramAlertService;

    @Inject
    BinanceFuturesService futuresService;

    // Simple cache to avoid hammering Binance on every UI refresh
    private BitcoinSignal cached;
    private long          cachedAt = 0;
    private static final long CACHE_TTL_MS = 15_000;

    public BitcoinSignal getSignal() {
        long now = System.currentTimeMillis();
        if (cached != null && (now - cachedAt) < CACHE_TTL_MS) {
            return cached;
        }
        BitcoinSignal signal = compute();
        if (signal.error == null) {
            telegramAlertService.notifyIfNeeded(signal);
            cached   = signal;
            cachedAt = now;
        } else if (cached != null) {
            // Return stale cache rather than a broken signal with price=0
            LOG.warnf("Signal compute error (%s) — returning stale cache (%.2f)", signal.error, cached.currentPrice);
            return cached;
        }
        return signal;
    }

    // ── Core computation ──────────────────────────────────────────────────────

    private BitcoinSignal compute() {
        BitcoinSignal s = new BitcoinSignal();
        s.timestamp = LocalDateTime.now();
        s.leverage  = LEVERAGE;

        try {
            // ── Fetch candles for 3 timeframes ────────────────────────────────
            // 1h  (120 candles ≈ 5 days)  — main signal
            // 4h  (60 candles  ≈ 10 days) — structural trend
            // 5m  (120 candles ≈ 10 h)    — entry timing
            List<List<Object>> raw1h = binanceClient.getKlines("BTCUSDT", "1h",  120);
            List<List<Object>> raw4h = binanceClient.getKlines("BTCUSDT", "4h",  60);
            List<List<Object>> raw5m = binanceClient.getKlines("BTCUSDT", "5m",  120);

            if (raw1h == null || raw1h.size() < 30) {
                s.error = "Not enough candle data from Binance";
                return s;
            }

            List<CandleDTO> candles = raw1h.stream().map(this::parseKline).collect(Collectors.toList());

            // Arrays for indicator computation
            int n = candles.size();
            double[] highs   = new double[n];
            double[] lows    = new double[n];
            double[] closes  = new double[n];
            List<Double> closeList  = new ArrayList<>(n);
            List<Double> volumeList = new ArrayList<>(n);

            for (int i = 0; i < n; i++) {
                CandleDTO c = candles.get(i);
                highs[i]  = c.high;
                lows[i]   = c.low;
                closes[i] = c.close;
                closeList.add(c.close);
                volumeList.add(c.volume);
            }

            double currentPrice = closes[n - 1];

            // ── Indicators ────────────────────────────────────────────────────
            double   rsi      = ta.calculateRSI(closeList, 14);
            double   ema9     = ta.calculateEMA(closeList, 9);
            double   ema21    = ta.calculateEMA(closeList, 21);
            double[] macd     = ta.calculateMACD(closeList);
            double[] boll     = ta.calculateBollingerBands(closeList, 20);
            double   atr      = ta.computeATR(highs, lows, closes, 14);
            double[] adxArr   = ta.calculateADX(highs, lows, closes, 14);
            double[] stoch    = ta.calculateStochastic(highs, lows, closes, 14, 3);
            double   obvSlope = ta.calculateOBVSlope(closeList, volumeList, 14);

            double bandWidth  = boll[0] - boll[2];
            double bollPos    = bandWidth > 0 ? (currentPrice - boll[2]) / bandWidth : 0.5;

            // ── Directional score (–100 → +100) ──────────────────────────────
            int raw_score = 0;
            boolean bullTrend = ema9 > ema21;
            boolean bearTrend = ema9 < ema21;

            // RSI (±30) — TREND-CONTEXT-AWARE
            // In a downtrend, oversold RSI is NOT a buy signal (trend can persist for hours/days).
            // In an uptrend, overbought RSI is only mildly penalised (momentum, not reversal).
            if (bearTrend) {
                // Look for failed rallies (RSI 60+) as SHORT confirmation
                if      (rsi > 75) raw_score -= 25;  // very overbought in downtrend → excellent SHORT entry
                else if (rsi > 60) raw_score -= 15;  // failed rally → SHORT opportunity
                else if (rsi < 20) raw_score -= 5;   // deeply oversold → trend extension (small short bias)
                // 20-60: neutral — oversold in downtrend does NOT generate bullish points
            } else {
                // Uptrend or neutral: oversold = buy opportunity, overbought = mild caution
                if      (rsi < 25) raw_score += 30;
                else if (rsi < 35) raw_score += 20;
                else if (rsi < 40) raw_score += 10;
                else if (rsi < 65) raw_score += 0;
                else if (rsi < 75) raw_score -= (bullTrend ? 5  : 20);
                else               raw_score -= (bullTrend ? 15 : 30);
            }

            // EMA trend (±30) — structural trend indicator
            double emaDiff = (ema9 - ema21) / ema21;
            if      (emaDiff >  0.005) raw_score += 30;
            else if (emaDiff >  0.001) raw_score += 18;
            else if (emaDiff >  0)     raw_score += 10;
            else if (emaDiff > -0.001) raw_score -= 10;
            else if (emaDiff > -0.005) raw_score -= 18;
            else                       raw_score -= 30;

            // MACD histogram (±20) — momentum, weighted by amplitude
            double hist = macd[2];
            double histAbs = Math.abs(hist);
            int macdPts = histAbs > 100 ? 20 : histAbs > 30 ? 15 : histAbs > 5 ? 10 : 5;
            if      (hist > 0) raw_score += macdPts;
            else if (hist < 0) raw_score -= macdPts;

            // Bollinger position (±15) — TREND-CONTEXT-AWARE
            // In a downtrend, near lower band is NORMAL (trend continuation), not a buy signal.
            // Near upper band in a downtrend = failed rally = SHORT entry.
            if (bearTrend) {
                if      (bollPos > 0.85) raw_score -= 15; // near upper band = resistance → SHORT
                else if (bollPos > 0.70) raw_score -= 7;
                else if (bollPos < 0.15) raw_score -= 3;  // near lower band in downtrend = continuation
                // 0.15–0.70: neutral — does NOT generate bullish points
            } else {
                if      (bollPos < 0.15)  raw_score += 15;
                else if (bollPos < 0.30)  raw_score += 7;
                else if (bollPos > 0.85)  raw_score -= (bullTrend ? 3  : 15);
                else if (bollPos > 0.70)  raw_score -= (bullTrend ? 0  : 7);
            }

            // Stochastic %K (±15) — TREND-CONTEXT-AWARE
            // In a downtrend, oversold Stoch does NOT generate bullish points.
            // Overbought Stoch in a downtrend = weak bounce = SHORT opportunity.
            double stochK = stoch[0];
            if (bearTrend) {
                if      (stochK > 80) raw_score -= 15; // overbought in downtrend → SHORT entry
                else if (stochK > 65) raw_score -= 7;
                // < 65: neutral — oversold in downtrend does NOT generate bullish points
            } else {
                if      (stochK < 20) raw_score += 15;
                else if (stochK < 35) raw_score += 7;
                else if (stochK > 80) raw_score -= (bullTrend ? 0  : 15);
                else if (stochK > 65) raw_score -= (bullTrend ? 0  : 7);
            }

            // OBV slope (±10) — does volume confirm direction?
            if      (obvSlope > 0) raw_score += 10;
            else if (obvSlope < 0) raw_score -= 10;

            // ── Market structure (±20 pts) ────────────────────────────────────
            // Detect swing pivots on 1h candles to identify HH/HL, LH/LL,
            // breakout or consolidation phases.
            MarketStructureResult ms = ta.detectMarketStructure(candles, 3);
            raw_score += ms.score;

            // ── Multi-timeframe scoring ───────────────────────────────────────
            // 4h structural trend (±25 pts) — dominant bias filter
            int tf4hScore = 0;
            String tf4hBias = "NEUTRAL";
            double tf4hEma9 = 0, tf4hEma21 = 0, tf4hRsi = 0;
            if (raw4h != null && raw4h.size() >= 26) {
                List<Double> closes4h = raw4h.stream()
                        .map(k -> Double.parseDouble(k.get(4).toString()))
                        .collect(Collectors.toList());
                tf4hEma9  = ta.calculateEMA(closes4h, 9);
                tf4hEma21 = ta.calculateEMA(closes4h, 21);
                tf4hRsi   = ta.calculateRSI(closes4h, 14);
                double diff4h = (tf4hEma9 - tf4hEma21) / tf4hEma21;
                if      (diff4h >  0.008) { tf4hScore = 25;  tf4hBias = "BULL"; }
                else if (diff4h >  0.003) { tf4hScore = 15;  tf4hBias = "BULL"; }
                else if (diff4h >  0)     { tf4hScore = 8;   tf4hBias = "BULL"; }
                else if (diff4h > -0.003) { tf4hScore = -8;  tf4hBias = "BEAR"; }
                else if (diff4h > -0.008) { tf4hScore = -15; tf4hBias = "BEAR"; }
                else                      { tf4hScore = -25; tf4hBias = "BEAR"; }
                raw_score += tf4hScore;
            }

            // 5m entry timing (±15 pts) — confirms or delays the entry
            int tf5mScore = 0;
            String tf5mMomentum = "NEUTRAL";
            double tf5mEma9 = 0, tf5mEma21 = 0, tf5mMacdHist = 0;
            if (raw5m != null && raw5m.size() >= 30) {
                List<Double> closes5m = raw5m.stream()
                        .map(k -> Double.parseDouble(k.get(4).toString()))
                        .collect(Collectors.toList());
                tf5mEma9      = ta.calculateEMA(closes5m, 9);
                tf5mEma21     = ta.calculateEMA(closes5m, 21);
                double[] macd5m = ta.calculateMACD(closes5m);
                tf5mMacdHist  = macd5m[2];
                boolean ema5mBull = tf5mEma9 > tf5mEma21;
                boolean macd5mBull = tf5mMacdHist > 0;
                if      ( ema5mBull &&  macd5mBull) { tf5mScore = 15;  tf5mMomentum = "UP";   }
                else if ( ema5mBull && !macd5mBull) { tf5mScore = 5;   tf5mMomentum = "UP";   }
                else if (!ema5mBull && !macd5mBull) { tf5mScore = -15; tf5mMomentum = "DOWN"; }
                else if (!ema5mBull &&  macd5mBull) { tf5mScore = -5;  tf5mMomentum = "DOWN"; }
                raw_score += tf5mScore;
            }

            // ── Futures Volumetrics (±25 pts) ────────────────────────────────
            int    volScore      = 0;
            double fundingRate   = 0;
            String fundingBias   = "NEUTRAL";
            double openInterest  = 0;
            String oiTrend       = "NEUTRAL";
            int    oiScore       = 0;
            double volumeDelta   = 0;
            String vdTrend       = "NEUTRAL";

            // 1) Volume delta from 1h klines (col[9] = takerBuyBaseVolume)
            // delta = 2 × takerBuy − totalVolume  (positive = net buying)
            try {
                int lookback = Math.min(5, raw1h.size());
                double sumDelta = 0;
                for (int i = raw1h.size() - lookback; i < raw1h.size(); i++) {
                    double totalVol  = Double.parseDouble(raw1h.get(i).get(5).toString());
                    double takerBuy  = Double.parseDouble(raw1h.get(i).get(9).toString());
                    sumDelta += 2 * takerBuy - totalVol;
                }
                volumeDelta = r2(sumDelta);
                if      (sumDelta >  0.5) { volScore += 8;  vdTrend = "POSITIVE"; }
                else if (sumDelta < -0.5) { volScore -= 8;  vdTrend = "NEGATIVE"; }
            } catch (Exception e) { LOG.debugf("Volume delta error: %s", e.getMessage()); }

            // 2) Funding rate from Binance premiumIndex (always live API)
            try {
                String premJson = futuresService.getPremiumIndex("BTCUSDT");
                JsonNode prem   = MAPPER.readTree(premJson);
                fundingRate     = prem.path("lastFundingRate").asDouble(0);
                if      (fundingRate >  0.0005) { volScore -= 7; fundingBias = "EXTREME_LONG";    }
                else if (fundingRate >  0.0001) { volScore -= 3; fundingBias = "MODERATE_LONG";   }
                else if (fundingRate < -0.0005) { volScore += 7; fundingBias = "EXTREME_SHORT";   }
                else if (fundingRate < -0.0001) { volScore += 3; fundingBias = "MODERATE_SHORT";  }
                openInterest = prem.path("openInterest").asDouble(0);
            } catch (Exception e) { LOG.debugf("Funding/OI error: %s", e.getMessage()); }

            // 3) OI current if not from premiumIndex
            if (openInterest == 0) {
                try {
                    String oiJson = futuresService.getOpenInterest("BTCUSDT");
                    JsonNode oi   = MAPPER.readTree(oiJson);
                    openInterest  = oi.path("openInterest").asDouble(0);
                } catch (Exception e) { LOG.debugf("OI error: %s", e.getMessage()); }
            }

            // 4) OI Trend from history (±10 pts) — /fapi/v1/openInterestHist
            // Compare recent 2h OI average vs older 4h OI average.
            // Rising OI + price rising  = new longs being opened    → BULL confirmation
            // Rising OI + price falling = new shorts being opened   → BEAR confirmation
            // Falling OI + any direction = positions closing        → trend weakening
            try {
                String oiHistJson = futuresService.getOpenInterestHistory("BTCUSDT", "1h", 6);
                JsonNode oiHist   = MAPPER.readTree(oiHistJson);
                if (oiHist.isArray() && oiHist.size() >= 4) {
                    // recent = last 2 entries, older = first 4 entries
                    double recentOi = 0, olderOi = 0;
                    int sz = oiHist.size();
                    for (int i = sz - 2; i < sz; i++)
                        recentOi += oiHist.get(i).path("sumOpenInterest").asDouble(0);
                    recentOi /= 2.0;
                    for (int i = 0; i < sz - 2; i++)
                        olderOi += oiHist.get(i).path("sumOpenInterest").asDouble(0);
                    olderOi /= (sz - 2.0);

                    double oiChangePct = olderOi > 0 ? (recentOi - olderOi) / olderOi * 100 : 0;
                    boolean priceRising = closes[n - 1] > closes[n - 4]; // price direction over ~3h

                    if (oiChangePct > 0.3) {          // OI rising meaningfully
                        if (priceRising) {
                            oiTrend = "RISING_BULL";  // new longs → LONG confirmation
                            oiScore = 10;
                        } else {
                            oiTrend = "RISING_BEAR";  // new shorts → SHORT confirmation
                            oiScore = -10;
                        }
                    } else if (oiChangePct < -0.3) {  // OI falling meaningfully
                        if (priceRising) {
                            oiTrend = "FALLING_BULL"; // shorts closing → weaker LONG
                            oiScore = 3;
                        } else {
                            oiTrend = "FALLING_BEAR"; // longs closing → weaker SHORT
                            oiScore = -3;
                        }
                    }
                    // else: flat OI = NEUTRAL, 0 pts
                    volScore += oiScore;
                }
            } catch (Exception e) { LOG.debugf("OI history error: %s", e.getMessage()); }

            raw_score += volScore;

            // ── Volatility filter (P4) ────────────────────────────────────────
            // Block or penalise trades when market volatility is extreme.
            // Uses ATR% (raw volatility) and last-candle range spike (news detection).
            double atrPct            = atr / currentPrice * 100;
            double lastCandleRange   = candles.get(n - 1).high - candles.get(n - 1).low;
            boolean extremeCandle    = lastCandleRange > 3.0 * atr;
            double bbMid             = boll[1];
            double bbWidthPct        = bbMid > 0 ? (boll[0] - boll[2]) / bbMid * 100 : 0;

            String volatilityRegime;
            String bbState;
            int volFilterScore = 0;

            if      (atrPct > 3.0) { volatilityRegime = "EXTREME"; volFilterScore = -20; }
            else if (atrPct > 2.0) { volatilityRegime = "HIGH";    volFilterScore = -10; }
            else if (atrPct > 0.5) { volatilityRegime = "NORMAL";  volFilterScore = 0;  }
            else                   { volatilityRegime = "LOW";     volFilterScore = -5; }

            if      (bbWidthPct < 1.0) bbState = "SQUEEZE";    // imminent breakout, direction unknown
            else if (bbWidthPct > 4.0) bbState = "EXPANSION";  // already exploding
            else                       bbState = "NORMAL";

            // Extra penalty: BB squeeze → no entry (direction unknown)
            if ("SQUEEZE".equals(bbState)) volFilterScore = Math.min(volFilterScore, -8);
            // Extra penalty: extreme candle (news)
            if (extremeCandle) volFilterScore = Math.min(volFilterScore, -15);

            raw_score += volFilterScore;
            int confidence = (raw_score + 100) / 2;
            confidence = Math.max(0, Math.min(100, confidence));

            // ADX — filtre de tendance : marché en range → atténuer les signaux uniquement si très faible ADX
            double adx = adxArr[0];
            if (adx < 15) confidence = (int)(confidence * 0.70 + 50 * 0.30); // extrême range : forte atténuation
            // ADX 15-22 : légère atténuation supprimée — évite de bloquer trop longtemps

            // LONG ≥ 57 · SHORT ≤ 43 · WAIT entre les deux (seuils assouplis vs 60/40)
            String direction;
            if      (confidence >= 57) direction = "LONG";
            else if (confidence <= 43) direction = "SHORT";
            else                       direction = "WAIT";

            // Filtre EMA supprimé : l'EMA est déjà pondérée ±30 pts dans le score.
            // Double-filtrer créait une zone morte permanente dans les marchés oscillants.

            // ── Entry / TP / SL (ATR-based) ───────────────────────────────────
            double entry = currentPrice;
            double sl, tp1, tp2, tp3, liquidation;

            // SL=1.5×ATR gives room to breathe vs normal wicks; TPs maintain 1:1, 1:2, 1:3 R:R
            if ("LONG".equals(direction)) {
                sl          = r2(entry - 1.5 * atr);
                tp1         = r2(entry + 1.5 * atr);
                tp2         = r2(entry + 3.0 * atr);
                tp3         = r2(entry + 4.5 * atr);
                liquidation = r2(entry * (1 - 1.0 / LEVERAGE));
            } else if ("SHORT".equals(direction)) {
                sl          = r2(entry + 1.5 * atr);
                tp1         = r2(entry - 1.5 * atr);
                tp2         = r2(entry - 3.0 * atr);
                tp3         = r2(entry - 4.5 * atr);
                liquidation = r2(entry * (1 + 1.0 / LEVERAGE));
            } else {
                // WAIT — show levels anyway for reference
                sl          = r2(entry - 1.5 * atr);
                tp1         = r2(entry + 1.5 * atr);
                tp2         = r2(entry + 3.0 * atr);
                tp3         = r2(entry + 4.5 * atr);
                liquidation = r2(entry * (1 - 1.0 / LEVERAGE));
            }

            // ── P&L on position with leverage ────────────────────────────────
            double tp1Pnl = pnl(entry, tp1, direction);
            double tp2Pnl = pnl(entry, tp2, direction);
            double tp3Pnl = pnl(entry, tp3, direction);
            double slPnl  = pnl(entry, sl,  direction);

            // ── Reasoning ────────────────────────────────────────────────────
            List<String> reasons = new ArrayList<>();

            // RSI
            if (bearTrend) {
                if      (rsi > 75) reasons.add("RSI suracheté en downtrend (" + r1(rsi) + ") → SHORT");
                else if (rsi > 60) reasons.add("RSI rally échoué en downtrend (" + r1(rsi) + ") → SHORT");
                else if (rsi < 20) reasons.add("RSI très survendu (" + r1(rsi) + ") — trend fort, neutre");
                else               reasons.add("RSI survendu (" + r1(rsi) + ") — neutre en downtrend");
            } else {
                if      (rsi < 35) reasons.add("RSI survendu (" + r1(rsi) + ") → achat");
                else if (rsi > 65) reasons.add("RSI suracheté (" + r1(rsi) + ") → prudence");
                else               reasons.add("RSI neutre (" + r1(rsi) + ")");
            }

            if (ema9 > ema21)    reasons.add("EMA9 > EMA21 (tendance haussière)");
            else                 reasons.add("EMA9 < EMA21 (tendance baissière)");

            if (hist > 0)        reasons.add("MACD haussier (hist=" + r4(hist) + ")");
            else                 reasons.add("MACD baissier (hist=" + r4(hist) + ")");

            if (bearTrend) {
                if      (bollPos > 0.75) reasons.add("Prix bande haute Bollinger en downtrend → SHORT");
                else if (bollPos < 0.20) reasons.add("Prix bande basse Bollinger — neutre en downtrend");
                else                     reasons.add("Bollinger neutre (" + r1(bollPos * 100) + "%)");
            } else {
                if      (bollPos < 0.25) reasons.add("Prix près de la bande basse de Bollinger → achat");
                else if (bollPos > 0.75) reasons.add("Prix près de la bande haute de Bollinger → surachat");
            }

            if      (adx > 40)   reasons.add("ADX=" + r1(adx) + " (tendance forte)");
            else if (adx > 22)   reasons.add("ADX=" + r1(adx) + " (tendance modérée)");
            else                 reasons.add("ADX=" + r1(adx) + " (marché en range, signal atténué)");

            if (bearTrend) {
                if      (stochK > 80) reasons.add("Stoch suracheté en downtrend (" + r1(stochK) + ") → SHORT");
                else if (stochK < 20) reasons.add("Stoch survendu (" + r1(stochK) + ") — neutre en downtrend");
                else                  reasons.add("Stoch neutre (" + r1(stochK) + ")");
            } else {
                if      (stochK < 20) reasons.add("Stoch survendu (" + r1(stochK) + ") → achat");
                else if (stochK > 80) reasons.add("Stoch suracheté (" + r1(stochK) + ")");
            }

            reasons.add(obvSlope > 0 ? "OBV haussier (volume confirme)" : "OBV baissier (pression vendeuse)");

            // Multi-TF reasoning
            reasons.add(String.format("4h %s (EMA9=%.0f vs EMA21=%.0f, score%+d)",
                    tf4hBias, tf4hEma9, tf4hEma21, tf4hScore));
            reasons.add(String.format("5m %s (MACD hist=%.1f, score%+d)",
                    tf5mMomentum, tf5mMacdHist, tf5mScore));

            // Market structure reasoning
            reasons.add("Structure 1h: " + ms.description +
                    (ms.score != 0 ? " (score" + (ms.score > 0 ? "+" : "") + ms.score + ")" : ""));

            // Volumetrics reasoning
            reasons.add(String.format("Vol.delta %s (%.2f BTC) · Funding %.4f%% (%s) · OI %s (score%+d) · total%+d",
                    vdTrend, volumeDelta, fundingRate * 100, fundingBias, oiTrend, oiScore, volScore));

            // Volatility filter reasoning
            reasons.add(String.format("Volatilité %s (ATR=%.2f%%) · BB %s (%.1f%%)%s · score%+d",
                    volatilityRegime, atrPct, bbState, bbWidthPct,
                    extremeCandle ? " · ⚠️ bougie extrême" : "", volFilterScore));

            // ── Populate DTO ──────────────────────────────────────────────────
            s.direction        = direction;
            s.confidence       = confidence;
            s.currentPrice     = r2(currentPrice);
            s.entryPrice       = r2(entry);
            s.tp1              = tp1;
            s.tp2              = tp2;
            s.tp3              = tp3;
            s.stopLoss         = sl;
            s.liquidationPrice = liquidation;
            s.tp1PnlPct        = r2(tp1Pnl);
            s.tp2PnlPct        = r2(tp2Pnl);
            s.tp3PnlPct        = r2(tp3Pnl);
            s.slPnlPct         = r2(slPnl);
            s.rsi              = r2(rsi);
            s.ema9             = r2(ema9);
            s.ema21            = r2(ema21);
            s.macdLine         = r4(macd[0]);
            s.macdSignal       = r4(macd[1]);
            s.macdHistogram    = r4(hist);
            s.bollingerUpper   = r2(boll[0]);
            s.bollingerMid     = r2(boll[1]);
            s.bollingerLower   = r2(boll[2]);
            s.bollingerPosition= r2(bollPos);
            s.atr              = r2(atr);
            s.adx              = r1(adx);
            s.plusDI           = r1(adxArr[1]);
            s.minusDI          = r1(adxArr[2]);
            s.stochK           = r1(stoch[0]);
            s.stochD           = r1(stoch[1]);
            s.obvSlope         = obvSlope;

            // Multi-TF fields
            s.tf4hBias      = tf4hBias;
            s.tf4hEma9      = r2(tf4hEma9);
            s.tf4hEma21     = r2(tf4hEma21);
            s.tf4hRsi       = r1(tf4hRsi);
            s.tf4hScore     = tf4hScore;
            s.tf5mMomentum  = tf5mMomentum;
            s.tf5mEma9      = r2(tf5mEma9);
            s.tf5mEma21     = r2(tf5mEma21);
            s.tf5mMacdHist  = r4(tf5mMacdHist);
            s.tf5mScore     = tf5mScore;

            // Market structure fields
            s.marketStructure = ms.type.name();
            s.msHH            = ms.hh;
            s.msHL            = ms.hl;
            s.msLH            = ms.lh;
            s.msLL            = ms.ll;
            s.msPivotHigh     = r2(ms.lastPivotHigh);
            s.msPivotLow      = r2(ms.lastPivotLow);
            s.msResistance    = r2(ms.resistance);
            s.msSupport       = r2(ms.support);
            s.msScore         = ms.score;
            s.msDescription   = ms.description;

            // Volumetric fields
            s.fundingRate      = fundingRate;
            s.fundingBias      = fundingBias;
            s.openInterest     = openInterest;
            s.oiTrend          = oiTrend;
            s.oiScore          = oiScore;
            s.volumeDelta      = volumeDelta;
            s.volumeDeltaTrend = vdTrend;
            s.volScore         = volScore;

            // Volatility filter fields
            s.atrPct           = r2(atrPct);
            s.volatilityRegime = volatilityRegime;
            s.extremeCandle    = extremeCandle;
            s.bbWidth          = r2(bbWidthPct);
            s.bbState          = bbState;
            s.volFilterScore   = volFilterScore;

            s.reasoning        = String.join(" · ", reasons);
            s.candles          = candles.subList(Math.max(0, n - 100), n);

            LOG.infof("[BTC] %s  conf=%d  RSI=%.1f  EMA9/21=%.0f/%.0f  MACD=%.2f  ADX=%.1f  structure=%s",
                    direction, confidence, rsi, ema9, ema21, hist, adx, ms.type);

        } catch (Exception e) {
            LOG.error("BTC signal computation failed", e);
            s.error = e.getMessage();
        }
        return s;
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

    /** P&L % on position including leverage. */
    private double pnl(double entry, double target, String direction) {
        double move = (target - entry) / entry;
        if ("SHORT".equals(direction)) move = -move;
        return move * LEVERAGE * 100;
    }

    private double r1(double v) { return Math.round(v * 10.0) / 10.0; }
    private double r2(double v) { return Math.round(v * 100.0) / 100.0; }
    private double r4(double v) { return Math.round(v * 10000.0) / 10000.0; }
}
