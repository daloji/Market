package com.market.model;

/**
 * Result of market structure detection (swing pivots, HH/HL/LH/LL, breakout).
 * Produced by TechnicalAnalysisService.detectMarketStructure().
 */
public class MarketStructureResult {

    public enum Type {
        BULL_TREND,     // HH + HL confirmed
        BEAR_TREND,     // LH + LL confirmed
        BREAKOUT_UP,    // close > last pivot high
        BREAKOUT_DOWN,  // close < last pivot low
        CONSOLIDATION   // no clear pattern
    }

    public Type    type;
    public boolean hh;          // Higher High
    public boolean hl;          // Higher Low
    public boolean lh;          // Lower High
    public boolean ll;          // Lower Low
    public double  lastPivotHigh;
    public double  lastPivotLow;
    public double  resistance;  // nearest resistance level
    public double  support;     // nearest support level
    public int     score;       // contribution to raw_score (±20)
    public String  description;

    public MarketStructureResult() {}
}
