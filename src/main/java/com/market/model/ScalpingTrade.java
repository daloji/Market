package com.market.model;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "scalping_trade")
public class ScalpingTrade extends PanacheEntity {

    public String  direction;   // LONG | SHORT
    public double  entryPrice;
    public double  exitPrice;
    public double  tpPrice;
    public double  slPrice;
    public int     confidence;
    public double  pnl;
    public String  status;      // OPEN | TP | SL | MANUAL

    public Instant openedAt;
    public Instant closedAt;

    // ── Finders ────────────────────────────────────────────────────────────────

    public static List<ScalpingTrade> findRecent(int limit) {
        return find("ORDER BY openedAt DESC").page(0, limit).list();
    }

    public static ScalpingTrade findOpenTrade() {
        return find("status", "OPEN").firstResult();
    }
}
