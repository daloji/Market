package com.market.model;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "trade")
public class Trade extends PanacheEntity {

    public enum Status    { OPEN, CLOSED }
    public enum Direction { LONG, SHORT }

    @Enumerated(EnumType.STRING)
    public Direction direction;

    public double amount;
    public int    leverage;
    public double entryPrice;
    public double feeRate;
    /** BTC quantity actually filled (for REAL trades). */
    public double quantity;

    public double tp1;
    public double tp2;
    public double tp3;
    public double sl;
    public double liq;

    public Instant openedAt;
    public Instant closedAt;

    @Enumerated(EnumType.STRING)
    public Status status = Status.OPEN;

    // Updated by background scheduler
    public double currentPrice;
    public double pnlUsd;
    public double pnlNet;
    public double pnlPct;
    public double feesTotal;

    public String closeReason;

    // ─── Trade type ────────────────────────────────────────────────────────────
    /** "SIMULATION" (default) or "REAL" */
    public String tradeType = "SIMULATION";
    public String broker;                    // e.g. "Binance", "Bybit"
    public String symbol   = "BTC/USDT";
    @Column(length = 500)
    public String note;

    // ─── Queries ───────────────────────────────────────────────────────────────

    /** All OPEN trades of all types (used by scheduler). */
    public static List<Trade> findAllOpen() {
        return list("status", Status.OPEN);
    }

    /** Active SIMULATION trades. */
    public static List<Trade> findActive() {
        return list("status = ?1 AND tradeType = ?2", Status.OPEN, "SIMULATION");
    }

    /** Active REAL trades. */
    public static List<Trade> findActiveReal() {
        return list("status = ?1 AND tradeType = ?2", Status.OPEN, "REAL");
    }

    /** Closed SIMULATION trades, newest first. */
    public static List<Trade> findClosed(int maxResults) {
        return find("status = ?1 AND tradeType = ?2 ORDER BY closedAt DESC",
                Status.CLOSED, "SIMULATION").page(0, maxResults).list();
    }

    /** Closed REAL trades, newest first. */
    public static List<Trade> findClosedReal(int maxResults) {
        return find("status = ?1 AND tradeType = ?2 ORDER BY closedAt DESC",
                Status.CLOSED, "REAL").page(0, maxResults).list();
    }

    /** All closed trades (both types), newest first. */
    public static List<Trade> findAllClosed(int maxResults) {
        return find("status = ?1 ORDER BY closedAt DESC", Status.CLOSED).page(0, maxResults).list();
    }
}

