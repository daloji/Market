package com.market.model;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Persists the result of a fundamental (valuation) analysis for one stock.
 * Refreshed at most once per day (Alpha Vantage free tier: 25 req/day).
 */
@Entity
@Table(name = "fundamental_data")
public class FundamentalData extends PanacheEntity {

    @Column(nullable = false)
    public String symbol;

    @Column(nullable = false)
    public LocalDateTime fetchedAt;

    // ── Company info ─────────────────────────────────────────────────────────
    public String sector;
    public String industry;

    // ── Valuation ratios ─────────────────────────────────────────────────────
    public Double peRatio;
    public Double forwardPE;
    public Double pegRatio;
    public Double priceToBook;
    public Double priceToSales;
    public Double evToEbitda;

    // ── Profitability ─────────────────────────────────────────────────────────
    public Double profitMargin;
    public Double operatingMargin;
    public Double returnOnEquity;
    public Double returnOnAssets;

    // ── Growth ────────────────────────────────────────────────────────────────
    public Double earningsGrowth;
    public Double revenueGrowth;

    // ── Other ────────────────────────────────────────────────────────────────
    public Double beta;
    public Double dividendYield;
    public Double analystTargetPrice;

    // Analyst consensus counts
    public Integer analystStrongBuy;
    public Integer analystBuy;
    public Integer analystHold;
    public Integer analystSell;
    public Integer analystStrongSell;

    public Double weekHigh52;
    public Double weekLow52;

    // ── Valuation verdict ────────────────────────────────────────────────────
    /** Composite fundamental score 0–100. */
    public int valuationScore;

    /** UNDERVALUED / FAIRLY_VALUED / OVERVALUED */
    @Enumerated(EnumType.STRING)
    public ValuationVerdict verdict;

    @Column(length = 1000)
    public String reasons;

    /** Source of the data: "YAHOO" or "ALPHAVANTAGE" (null = legacy rows). */
    public String dataSource;

    // ── Finders ──────────────────────────────────────────────────────────────
    public static FundamentalData findLatestBySymbol(String symbol) {
        return find("symbol = ?1 order by fetchedAt desc", symbol.toUpperCase())
                .firstResult();
    }

    /** Returns the latest fundamental data per symbol (all cached results). */
    public static java.util.List<FundamentalData> findAllLatest() {
        return getEntityManager().createQuery(
                "SELECT f FROM FundamentalData f " +
                "WHERE f.id IN (SELECT MAX(f2.id) FROM FundamentalData f2 GROUP BY f2.symbol)",
                FundamentalData.class
        ).getResultList();
    }
}
