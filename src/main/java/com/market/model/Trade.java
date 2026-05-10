package com.market.model;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "trade")
public class Trade extends PanacheEntity {

    public enum Status { OPEN, CLOSED }
    public enum Direction { LONG, SHORT }

    @Enumerated(EnumType.STRING)
    public Direction direction;

    public double amount;
    public int    leverage;
    public double entryPrice;
    public double feeRate;

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

    // ─── Queries ───────────────────────────────────────────────────────────────

    public static List<Trade> findActive() {
        return list("status", Status.OPEN);
    }

    public static List<Trade> findAll(int maxResults) {
        return find("ORDER BY openedAt DESC").page(0, maxResults).list();
    }
}
