package com.market.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Ultra-short-term scalping signal for BTC/USDT based on 1m candles.
 * Returned by /api/crypto/btc/scalping.
 *
 * Indicators: RSI(7), EMA(5)/EMA(13), MACD(6,13,4), ATR(7), Bollinger(20), Stochastic(5,3), Volume Delta, SMA(50) trend, VWAP.
 *
 * Filters applied before generating a signal:
 *   - BB SQUEEZE blocked (bbWidth < 0.3%)
 *   - ATR gate: atrPct < 0.08% → WAIT (market too quiet)
 *   - Score threshold 78 with-trend / 92 counter-trend (SMA50 gate)
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
    /** SMA(50) on 1m — medium-term trend filter. Price > sma50_1m → uptrend. */
    public double sma50_1m;
    /** VWAP over the full 1m window (~3h20). Price > VWAP → buying pressure. Scoring proportional to distance (max ±20 pts). */
    public double vwap;

    // ── Score breakdown ──────────────────────────────────────────────────────
    public int rsiScore;
    public int emaScore;
    public int macdScore;
    public int volScore;
    public int vwapScore;
    /** ATR volatility bonus applied symmetrically to both sides (+0/+5/+10 pts). */
    public int atrScore;
    /** Last candle body direction confirmation (+5 bullish / -5 bearish). */
    public int candleBodyScore;

    // ── RSI dynamics ─────────────────────────────────────────────────────────
    /** RSI value 1 candle ago (for slope) */
    public double rsiPrev;
    /** RSI value 2 candles ago (for acceleration) */
    public double rsiPrev2;
    /** rsi7 - rsiPrev : positive = RSI rising, negative = RSI falling */
    public double rsiSlope;
    /** (rsi7-rsiPrev) - (rsiPrev-rsiPrev2) : positive = RSI accelerating up */
    public double rsiAcceleration;
    /**
     * RSI divergence over last 5 candles:
     * "BULLISH" = price falling but RSI rising (long signal)
     * "BEARISH" = price rising but RSI falling (short signal)
     * "NONE"    = no divergence
     */
    public String rsiDivergence;
    /** Score contribution from slope + acceleration + divergence */
    public int rsiDynScore;

    // ── Chart data ───────────────────────────────────────────────────────────
    /** Last 100 1m candles for candlestick chart */
    public List<CandleDTO> candles;

    // ── Meta ─────────────────────────────────────────────────────────────────
    public String reasoning;
    public LocalDateTime timestamp;
    public String error;
}
