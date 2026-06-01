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
    public double  tpPrice;     // TP1 (60% of position, ATR-based)
    public double  tp2Price;    // TP2 (40% of position, 1.0×ATR)
    public double  slPrice;
    public int     confidence;
    public double  pnl;
    public String  status;      // OPEN | TP | TP2 | SL | MANUAL

    public Instant openedAt;
    public Instant closedAt;
    public boolean tp1Hit;   // true when TP1 partial close was executed
    public double  tp1Pnl;  // PnL captured at TP1 (60% of position)
    public double  fees;    // total Binance commissions (open + close fills)
    public double  pnlNet;  // pnl - fees (net result after commissions)

    // ── Finders ────────────────────────────────────────────────────────────────

    public static List<ScalpingTrade> findRecent(int limit) {
        return find("ORDER BY openedAt DESC").page(0, limit).list();
    }

    public static ScalpingTrade findOpenTrade() {
        return find("status", "OPEN").firstResult();
    }
}
