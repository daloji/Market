package com.market.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Ultra-short-term scalping signal for BTC/USDT based on 1m candles.
 * Returned by /api/crypto/btc/scalping.
 *
 * Indicators: RSI(7), EMA(5)/EMA(13), MACD(6,13,4), ATR(7), Bollinger(20), Stochastic(5,3), Volume Delta.
 * TP/SL are ATR-based but very tight (scalping style):
 *   TP1 = entry ± 0.5 × ATR
 *   TP2 = entry ± 1.0 × ATR
 *   SL  = entry ∓ 0.4 × ATR
 */
public class ScalpingSignal {

    /** LONG, SHORT or WAIT */
    public String direction;

    /** Confidence 0–100 */
    public int confidence;

    public double currentPrice;

    // ── Entry / Targets / Stop ───────────────────────────────────────────────
    public double entryPrice;
    public double tp1;
    public double tp2;
    public double stopLoss;

    /** Estimated P&L % with leverage on TP1/TP2/SL */
    public double tp1PnlPct;
    public double tp2PnlPct;
    public double slPnlPct;

    // ── Indicators (1m timeframe) ────────────────────────────────────────────
    /** RSI with period 7 — faster than RSI(14) */
    public double rsi7;
    /** Fast EMA (period 5) */
    public double ema5;
    /** Slow EMA (period 13) */
    public double ema13;
    /** MACD histogram on fast MACD(6,13,4) */
    public double macdHistogram;
    /** Raw MACD line */
    public double macdLine;
    /** MACD signal line */
    public double macdSignal;
    /** ATR(7) on 1m */
    public double atr;
    /** ATR as % of price */
    public double atrPct;
    /** Stochastic %K (5,3) */
    public double stochK;
    /** Stochastic %D */
    public double stochD;
    /** Bollinger Band width as % of mid */
    public double bbWidth;
    /** "SQUEEZE" (<0.3%) / "NORMAL" / "EXPANSION" (>1%) */
    public String bbState;
    /** Net buying pressure: takerBuyVolume / totalVolume over last 20 candles */
    public double volumeDeltaPct;
    /** "STRONG_BUY" / "BUY" / "NEUTRAL" / "SELL" / "STRONG_SELL" */
    public String volumeDeltaTrend;

    // ── Score breakdown ──────────────────────────────────────────────────────
    public int rsiScore;
    public int emaScore;
    public int macdScore;
    public int volScore;

    // ── Chart data ───────────────────────────────────────────────────────────
    /** Last 100 1m candles for candlestick chart */
    public List<CandleDTO> candles;

    // ── Meta ─────────────────────────────────────────────────────────────────
    public String reasoning;
    public LocalDateTime timestamp;
    public String error;
}
