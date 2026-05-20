package com.market.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Ultra-short-term scalping signal for BTC/USDT based on 1m candles.
 * Returned by /api/crypto/btc/scalping.
 *
 * Indicators (1m): RSI(7)+dynamics, EMA(5/13/21), MACD(6,13,4), ATR(7), Bollinger(20),
 *   Stochastic(5,3), Volume Delta, VWAP+SD bands, Supertrend(7,3), CVD, Market Structure.
 * Higher TF: 5m EMA(9/21) bias.
 *
 * Filters:
 *   - TTM Squeeze (BB inside KC) → WAIT
 *   - ATR gate: atrPct < 0.08% → WAIT
 *   - Score threshold 78 with-trend / 92 counter-trend (SMA50)
 * TP/SL ATR-based:
 *   TP1 = entry ± 0.5 × ATR  (60% qty)
 *   TP2 = entry ± 1.0 × ATR  (40% qty)
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
    /** ADX(14) — trend strength: >25 = strong trend, <20 = ranging market */
    public double adx;
    /** +DI (bullish directional strength) */
    public double plusDI;
    /** -DI (bearish directional strength) */
    public double minusDI;
    /** "TREND" (ADX>25) / "RANGE" (ADX<20) / "NEUTRAL" (20–25). Drives oscillator/trend multipliers. */
    public String marketRegime;

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

    // ── Supertrend(7,3) ──────────────────────────────────────────────────────
    /** "LONG" when price above supertrend line, "SHORT" when below */
    public String supertrendDirection;
    /** Supertrend line value (ATR-based trailing support/resistance) */
    public double supertrendValue;
    /** Score contribution from Supertrend */
    public int    supertrendScore;

    // ── TTM Squeeze (Bollinger inside Keltner Channels) ──────────────────────
    /** True = BB bands inside KC bands → market compressed → WAIT */
    public boolean ttmSqueezeOn;
    /** Keltner Channel upper band (EMA20 + 1.5×ATR20) */
    public double  kcUpper;
    /** Keltner Channel lower band (EMA20 − 1.5×ATR20) */
    public double  kcLower;

    // ── 5m Higher-Timeframe Bias ─────────────────────────────────────────────
    /** EMA(9) on 5m candles */
    public double ema9_5m;
    /** EMA(21) on 5m candles */
    public double ema21_5m;
    /** "LONG" (EMA9 > EMA21), "SHORT" (EMA9 < EMA21), "NEUTRAL" */
    public String bias5m;
    /** Score contribution from 5m bias */
    public int    bias5mScore;

    // ── VWAP Standard Deviation Bands ────────────────────────────────────────
    /** VWAP + 1σ (primary resistance / momentum target) */
    public double vwapSd1Upper;
    /** VWAP − 1σ (primary support / mean-reversion target) */
    public double vwapSd1Lower;
    /** VWAP + 2σ (extreme overbought → strong mean-reversion in RANGE) */
    public double vwapSd2Upper;
    /** VWAP − 2σ (extreme oversold → strong mean-reversion in RANGE) */
    public double vwapSd2Lower;

    // ── CVD (Cumulative Volume Delta) ─────────────────────────────────────────
    /** CVD slope over last 20 candles: Σ(buyVol−sellVol) change */
    public double cvdSlope;
    /** CVD slope as % of 20-candle total volume (±20% = strong signal) */
    public double cvdPct;
    /** "BULLISH" / "BEARISH" / "NEUTRAL" */
    public String cvdTrend;
    /** Score contribution from CVD */
    public int    cvdScore;

    // ── EMA(21) — Triple stack alignment ─────────────────────────────────────
    /** EMA(21) on 1m for triple-stack alignment check */
    public double ema21;
    /** Score when EMA5 > EMA13 > EMA21 (bull) or EMA5 < EMA13 < EMA21 (bear) */
    public int    ema21Score;

    // ── Market Structure (1m mini-pivots) ────────────────────────────────────
    /** "BULL_TREND" / "BEAR_TREND" / "BREAKOUT_UP" / "BREAKOUT_DOWN" / "CONSOLIDATION" */
    public String marketStructure1m;
    /** Score from HH/HL/LH/LL pivot analysis on 1m (pivotStrength=3) */
    public int    marketStructureScore;

    // ── Chart data ───────────────────────────────────────────────────────────
    /** Last 100 1m candles for candlestick chart */
    public List<CandleDTO> candles;

    // ── Meta ─────────────────────────────────────────────────────────────────
    public String reasoning;
    public LocalDateTime timestamp;
    public String error;
}
