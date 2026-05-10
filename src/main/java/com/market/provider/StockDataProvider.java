package com.market.provider;

import java.util.List;

/**
 * Contract for any stock data source.
 * Implementations must be {@code @ApplicationScoped} CDI beans.
 */
public interface StockDataProvider {

    /** Human-readable name shown in logs. */
    String getName();

    /** Returns false if the provider is not configured (missing API key). */
    boolean isEnabled();

    /**
     * Fetches up to {@code days} daily OHLCV quotes for the given symbol,
     * ordered chronologically (oldest first).
     *
     * @throws Exception if the source is unreachable or returns an error
     */
    List<DailyQuote> fetchQuotes(String symbol, int days) throws Exception;
}
