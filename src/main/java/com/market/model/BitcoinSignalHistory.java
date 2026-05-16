package com.market.model;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "bitcoin_signal_history",
       indexes = @Index(name = "idx_bsh_detected", columnList = "detectedAt DESC"))
public class BitcoinSignalHistory extends PanacheEntity {

    @Column(nullable = false)
    public Instant detectedAt;

    @Column(nullable = false, length = 8)
    public String direction;

    public int confidence;
    public double price;

    // ── RSI ──────────────────────────────────────────────────────────────────
    public double rsi;

    // ── EMA ──────────────────────────────────────────────────────────────────
    public double ema9;
    public double ema21;

    // ── MACD ─────────────────────────────────────────────────────────────────
    public double macdLine;
    public double macdSignal;
    public double macdHistogram;

    // ── Bollinger Bands ───────────────────────────────────────────────────────
    public double bollUpper;
    public double bollMid;
    public double bollLower;
    public double bollPosition;

    // ── ADX / DI ─────────────────────────────────────────────────────────────
    public double adx;
    public double plusDI;
    public double minusDI;

    // ── Stochastic ────────────────────────────────────────────────────────────
    public double stochK;
    public double stochD;

    // ── Volume / OBV ─────────────────────────────────────────────────────────
    public double obvSlope;

    // ── ATR ──────────────────────────────────────────────────────────────────
    public double atr;

    // ── Auto-trade link ──────────────────────────────────────────────────────
    public boolean tradeTriggered;

    @Column(length = 500)
    public String skipReason;

    @Column(length = 1000)
    public String reasoning;

    // ── Finders ──────────────────────────────────────────────────────────────

    public static List<BitcoinSignalHistory> findRecent(int limit) {
        return find("ORDER BY detectedAt DESC").page(0, limit).list();
    }

    public static List<BitcoinSignalHistory> findByDirection(String direction, int limit) {
        return find("direction = ?1 ORDER BY detectedAt DESC", direction).page(0, limit).list();
    }
}
