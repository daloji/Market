package com.market.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Full intraday signal for BTC/USDT futures with x10 leverage.
 * Returned by /api/crypto/btc/signal.
 */
public class BitcoinSignal {

    /** LONG, SHORT or WAIT */
    public String direction;

    /** Confidence 0–100 */
    public int confidence;

    public double currentPrice;

    // ── Entry / Targets / Stop ───────────────────────────────────────────────
    public double entryPrice;
    public double tp1;          // Target 1  (R/R 1:1)
    public double tp2;          // Target 2  (R/R 1:2)
    public double tp3;          // Target 3  (R/R 1:3)
    public double stopLoss;
    public double liquidationPrice; // Theoretical liquidation (x10 = ±10%)

    /** Leverage used for P&L display */
    public int leverage = 10;

    // ── P&L on POSITION (with leverage, in %) ────────────────────────────────
    public double tp1PnlPct;
    public double tp2PnlPct;
    public double tp3PnlPct;
    public double slPnlPct;

    // ── Indicators ───────────────────────────────────────────────────────────
    public double rsi;
    public double ema9;
    public double ema21;
    public double macdLine;
    public double macdSignal;
    public double macdHistogram;
    public double bollingerUpper;
    public double bollingerMid;
    public double bollingerLower;
    public double bollingerPosition; // 0 = at lower band, 1 = at upper band
    public double atr;               // Average True Range (1h)

    // ADX (14)
    public double adx;
    public double plusDI;
    public double minusDI;

    // Stochastic (14, 3)
    public double stochK;
    public double stochD;

    // OBV slope (positive = rising, negative = falling)
    public double obvSlope;

    // ── Multi-timeframe ──────────────────────────────────────────────────────
    /** Structural trend on 4h: "BULL", "BEAR" or "NEUTRAL" */
    public String tf4hBias;
    public double tf4hEma9;
    public double tf4hEma21;
    public double tf4hRsi;
    public int    tf4hScore;   // contribution to final score (±25)

    /** Entry timing on 5m: "UP", "DOWN" or "NEUTRAL" */
    public String tf5mMomentum;
    public double tf5mEma9;
    public double tf5mEma21;
    public double tf5mMacdHist;
    public int    tf5mScore;   // contribution to final score (±15)

    // ── Market Structure ──────────────────────────────────────────────────────
    /** BULL_TREND / BEAR_TREND / BREAKOUT_UP / BREAKOUT_DOWN / CONSOLIDATION */
    public String  marketStructure;
    public boolean msHH;
    public boolean msHL;
    public boolean msLH;
    public boolean msLL;
    public double  msPivotHigh;
    public double  msPivotLow;
    public double  msResistance;
    public double  msSupport;
    public int     msScore;
    public String  msDescription;

    // ── Futures Volumetrics ───────────────────────────────────────────────────
    /** Current funding rate (0.0001 = 0.01%) — from Binance premiumIndex */
    public double  fundingRate;
    /** "EXTREME_LONG" / "MODERATE_LONG" / "NEUTRAL" / "MODERATE_SHORT" / "EXTREME_SHORT" */
    public String  fundingBias;

    /** Current open interest in BTC */
    public double  openInterest;
    /** "RISING" / "FALLING" / "NEUTRAL" */
    public String  oiTrend;

    /**
     * Net volume delta over last 5 candles (positive = net buying, negative = net selling).
     * Computed from kline column [9] (takerBuyBaseVolume).
     */
    public double  volumeDelta;
    /** "POSITIVE" / "NEGATIVE" / "NEUTRAL" */
    public String  volumeDeltaTrend;

    /** Aggregate score contribution from volumetrics (±15) */
    public int     volScore;

    // ── Volatility Filter (P4) ────────────────────────────────────────────────
    /** ATR as % of current price — measure of raw volatility */
    public double  atrPct;
    /** "LOW" (squeeze) / "NORMAL" / "HIGH" / "EXTREME" (&gt;3%) */
    public String  volatilityRegime;
    /** True if last candle range &gt; 3×ATR — likely news / flash event */
    public boolean extremeCandle;
    /** Bollinger Band width as % of mid price */
    public double  bbWidth;
    /** "SQUEEZE" (&lt;1%) / "NORMAL" / "EXPANSION" (&gt;4%) */
    public String  bbState;
    /** Score contribution from volatility filter (0 or negative — only penalises) */
    public int     volFilterScore;

    // ── Chart data ───────────────────────────────────────────────────────────
    /** Last 100 1h candles for candlestick chart */
    public List<CandleDTO> candles;

    // ── Meta ─────────────────────────────────────────────────────────────────
    public String reasoning;
    public LocalDateTime timestamp;
    public String error;
}
