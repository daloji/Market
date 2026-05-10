package com.market.model;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "recommendations")
public class StockRecommendation extends PanacheEntity {

    @Column(nullable = false)
    public String symbol;

    public String stockName;

    @Column(nullable = false)
    public LocalDateTime timestamp;

    @Enumerated(EnumType.STRING)
    public RecommendationSignal signal;

    /** Composite score 0–100: RSI (40pts) + Trend (40pts) + Volume (20pts). */
    public int score;

    public Double currentPrice;
    public Double rsi;
    public Double sma20;
    public Double sma50;

    // MACD (12, 26, 9)
    public Double macdLine;
    public Double macdSignal;
    public Double macdHistogram;

    // Bollinger Bands (20, ±2σ)
    public Double bollingerUpper;
    public Double bollingerLower;
    /** 0 = at lower band, 0.5 = middle, 1 = at upper band. */
    public Double bollingerPosition;

    @Column(length = 1000)
    public String reasons;

    public static StockRecommendation findLatestBySymbol(String symbol) {
        return find("symbol = ?1 order by timestamp desc", symbol.toUpperCase())
                .firstResult();
    }

    /** Returns the single latest recommendation for every tracked symbol. */
    public static List<StockRecommendation> findAllLatest() {
        return getEntityManager().createQuery(
                "SELECT r FROM StockRecommendation r " +
                "WHERE r.id IN (SELECT MAX(r2.id) FROM StockRecommendation r2 GROUP BY r2.symbol)",
                StockRecommendation.class
        ).getResultList();
    }

    /** Returns the latest recommendation per symbol filtered by signal. */
    public static List<StockRecommendation> findBySignal(RecommendationSignal signal) {
        return getEntityManager().createQuery(
                "SELECT r FROM StockRecommendation r " +
                "WHERE r.signal = :signal " +
                "AND r.id IN (SELECT MAX(r2.id) FROM StockRecommendation r2 GROUP BY r2.symbol)",
                StockRecommendation.class
        ).setParameter("signal", signal).getResultList();
    }
}
