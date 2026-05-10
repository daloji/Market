package com.market.model;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.List;

@Entity
@Table(name = "stock_quotes",
    uniqueConstraints = @UniqueConstraint(columnNames = {"symbol", "date"}))
public class StockQuote extends PanacheEntity {

    @Column(nullable = false)
    public String symbol;

    @Column(nullable = false)
    public LocalDate date;

    public Double open;
    public Double high;
    public Double low;

    @Column(nullable = false)
    public Double close;

    public Long volume;

    public static StockQuote findBySymbolAndDate(String symbol, LocalDate date) {
        return find("symbol = ?1 and date = ?2", symbol, date).firstResult();
    }

    /** Returns quotes ordered most-recent first. */
    public static List<StockQuote> findRecentBySymbol(String symbol, int limit) {
        return find("symbol = ?1 order by date desc", symbol)
                .page(0, limit)
                .list();
    }
}
