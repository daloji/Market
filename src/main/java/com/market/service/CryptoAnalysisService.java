package com.market.service;

import com.market.client.BinanceClient;
import com.market.model.BitcoinSignal;
import com.market.model.CandleDTO;
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

    private static final Logger LOG = Logger.getLogger(CryptoAnalysisService.class);
    private static final int    LEVERAGE = 10;

    @Inject
    @RestClient
    BinanceClient binanceClient;

    @Inject
    TechnicalAnalysisService ta;

    @Inject
    WhatsAppAlertService whatsAppAlertService;

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
            whatsAppAlertService.notifyIfNeeded(signal);
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
            // Fetch 120 × 1h candles for reliable indicator warm-up
            List<List<Object>> raw = binanceClient.getKlines("BTCUSDT", "1h", 120);
            if (raw == null || raw.size() < 30) {
                s.error = "Not enough candle data from Binance";
                return s;
            }

            List<CandleDTO> candles = raw.stream().map(this::parseKline).collect(Collectors.toList());

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
            boolean bullTrend = ema9 > ema21;  // contexte de tendance
            boolean bearTrend = ema9 < ema21;

            // RSI (±30) — en tendance, le surachat/survente est normal et moins pénalisé
            if      (rsi < 30) raw_score += 30;
            else if (rsi < 40) raw_score += 20;
            else if (rsi < 65) raw_score += 0;   // neutre
            else if (rsi < 75) raw_score -= (bullTrend ? 5  : 20); // uptrend: RSI 66-74 = momentum normal
            else               raw_score -= (bullTrend ? 15 : 30); // uptrend: RSI >75 = surachat modéré seulement

            // EMA trend (±30) — indicateur de tendance structurelle
            double emaDiff = (ema9 - ema21) / ema21;
            if      (emaDiff >  0.005) raw_score += 30;
            else if (emaDiff >  0.001) raw_score += 18;
            else if (emaDiff >  0)     raw_score += 10;
            else if (emaDiff > -0.001) raw_score -= 10;
            else if (emaDiff > -0.005) raw_score -= 18;
            else                       raw_score -= 30;

            // MACD histogram (±20) — momentum, pondéré selon amplitude
            double hist = macd[2];
            double histAbs = Math.abs(hist);
            int macdPts = histAbs > 100 ? 20 : histAbs > 30 ? 15 : histAbs > 5 ? 10 : 5;
            if      (hist > 0) raw_score += macdPts;
            else if (hist < 0) raw_score -= macdPts;

            // Bollinger position — coller la bande haute en uptrend = normal (force, pas surachat)
            if      (bollPos < 0.15)  raw_score += 15;
            else if (bollPos < 0.30)  raw_score += 7;
            else if (bollPos > 0.85)  raw_score -= (bullTrend ? 3  : 15); // uptrend: légèrement pénalisé
            else if (bollPos > 0.70)  raw_score -= (bullTrend ? 0  : 7);  // uptrend: neutre

            // Stochastic %K — en tendance haussière, >80 = momentum fort (pas signal de vente)
            double stochK = stoch[0];
            if      (stochK < 20) raw_score += 15;
            else if (stochK < 35) raw_score += 7;
            else if (stochK > 80) raw_score -= (bullTrend ? 0  : 15); // uptrend: ignoré
            else if (stochK > 65) raw_score -= (bullTrend ? 0  : 7);  // uptrend: ignoré

            // OBV slope (±10) — volume confirme-t-il la direction ?
            if      (obvSlope > 0) raw_score += 10;
            else if (obvSlope < 0) raw_score -= 10;

            // Normalise to 0–100
            int confidence = (raw_score + 100) / 2;
            confidence = Math.max(0, Math.min(100, confidence));

            // ADX — filtre de tendance : marché en range → atténuer les signaux
            double adx = adxArr[0];
            if      (adx < 15) confidence = (int)(confidence * 0.65 + 50 * 0.35);
            else if (adx < 22) confidence = (int)(confidence * 0.85 + 50 * 0.15);

            // LONG ≥ 60 · SHORT ≤ 40 · WAIT entre les deux
            String direction;
            if      (confidence >= 60) direction = "LONG";
            else if (confidence <= 40) direction = "SHORT";
            else                       direction = "WAIT";

            // Filtre de tendance EMA : ne jamais shorter une tendance haussière structurelle
            // ni longer une tendance baissière structurelle
            if ("SHORT".equals(direction) && ema9 > ema21) direction = "WAIT";
            if ("LONG".equals(direction)  && ema9 < ema21) direction = "WAIT";

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
            if (rsi < 35)        reasons.add("RSI survendu (" + r1(rsi) + ")");
            else if (rsi > 65)   reasons.add("RSI suracheté (" + r1(rsi) + ")");
            else                 reasons.add("RSI neutre (" + r1(rsi) + ")");

            if (ema9 > ema21)    reasons.add("EMA9 > EMA21 (tendance haussière)");
            else                 reasons.add("EMA9 < EMA21 (tendance baissière)");

            if (hist > 0)        reasons.add("MACD haussier (hist=" + r4(hist) + ")");
            else                 reasons.add("MACD baissier (hist=" + r4(hist) + ")");

            if (bollPos < 0.25)  reasons.add("Prix près de la bande basse de Bollinger");
            else if (bollPos > 0.75) reasons.add("Prix près de la bande haute de Bollinger");

            if      (adx > 40)   reasons.add("ADX=" + r1(adx) + " (tendance forte)");
            else if (adx > 22)   reasons.add("ADX=" + r1(adx) + " (tendance modérée)");
            else                 reasons.add("ADX=" + r1(adx) + " (marché en range)");

            if      (stochK < 20) reasons.add("Stoch survendu (" + r1(stochK) + ")");
            else if (stochK > 80) reasons.add("Stoch suracheté (" + r1(stochK) + ")");

            reasons.add(obvSlope > 0 ? "OBV haussier (volume confirme)" : "OBV baissier (pression vendeuse)");

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
            s.reasoning        = String.join(" · ", reasons);
            s.candles          = candles.subList(Math.max(0, n - 100), n);

            LOG.infof("[BTC] %s  conf=%d  RSI=%.1f  EMA9/21=%.0f/%.0f  MACD=%.2f  ADX=%.1f  Stoch=%.1f",
                    direction, confidence, rsi, ema9, ema21, hist, adx, stochK);

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
