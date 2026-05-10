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

    // ── Chart data ───────────────────────────────────────────────────────────
    /** Last 100 1h candles for candlestick chart */
    public List<CandleDTO> candles;

    // ── Meta ─────────────────────────────────────────────────────────────────
    public String reasoning;
    public LocalDateTime timestamp;
    public String error;
}
