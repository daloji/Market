package com.market.provider;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Tries data providers in priority order and returns the first successful result:
 * <ol>
 *   <li>Yahoo Finance (always on, no API key)</li>
 *   <li>Twelve Data (if {@code market.datasource.twelvedata.api-key} is set)</li>
 *   <li>Alpha Vantage (if {@code market.datasource.alphavantage.api-key} is set)</li>
 * </ol>
 */
@ApplicationScoped
public class StockDataAggregator {

    private static final Logger LOG = Logger.getLogger(StockDataAggregator.class);

    @Inject YahooFinanceProvider yahoo;
    @Inject TwelveDataProvider   twelveData;
    @Inject AlphaVantageProvider alphaVantage;

    public List<DailyQuote> fetchQuotes(String symbol, int days) {
        for (StockDataProvider provider : List.of(yahoo, twelveData, alphaVantage)) {
            if (!provider.isEnabled()) continue;
            try {
                List<DailyQuote> quotes = provider.fetchQuotes(symbol, days);
                if (quotes != null && !quotes.isEmpty()) {
                    LOG.debugf("[%s] %d quotes for %s", provider.getName(), quotes.size(), symbol);
                    return quotes;
                }
                LOG.warnf("[%s] Empty result for %s — trying next source", provider.getName(), symbol);
            } catch (Exception e) {
                LOG.warnf("[%s] Failed for %s (%s) — trying next source",
                        provider.getName(), symbol, e.getMessage());
            }
        }
        LOG.errorf("All data sources failed for %s", symbol);
        return List.of();
    }
}
