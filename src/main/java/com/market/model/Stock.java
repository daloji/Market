package com.market.model;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.List;

@Entity
@Table(name = "stocks")
public class Stock extends PanacheEntity {

    @Column(unique = true, nullable = false)
    public String symbol;

    public String name;

    @Column(nullable = false)
    public boolean active = true;

    public static Stock findBySymbol(String symbol) {
        return find("symbol", symbol.toUpperCase()).firstResult();
    }

    public static List<Stock> findActive() {
        return list("active", true);
    }
}
