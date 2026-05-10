package com.market.service;

import com.market.model.StockQuote;
import com.market.provider.DailyQuote;
import com.market.provider.StockDataAggregator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;

@ApplicationScoped
public class StockDataService {

    private static final Logger LOG = Logger.getLogger(StockDataService.class);

    @Inject
    StockDataAggregator aggregator;

    /**
     * Fetches up to 60 daily quotes for {@code symbol} from the best available
     * data source and upserts them into the {@code stock_quotes} table.
     */
    @Transactional
    public void fetchAndStoreQuotes(String symbol) {
        List<DailyQuote> quotes = aggregator.fetchQuotes(symbol, 60);

        if (quotes.isEmpty()) {
            LOG.warnf("No data available for %s from any source", symbol);
            return;
        }

        int saved = 0;
        for (DailyQuote q : quotes) {
            if (q.close == null) continue;

            StockQuote stored = StockQuote.findBySymbolAndDate(symbol, q.date);
            if (stored == null) {
                stored        = new StockQuote();
                stored.symbol = symbol;
                stored.date   = q.date;
            }
            stored.open   = q.open;
            stored.high   = q.high;
            stored.low    = q.low;
            stored.close  = q.close;
            stored.volume = q.volume;
            stored.persist();
            saved++;
        }

        LOG.infof("Stored %d quotes for %s", saved, symbol);
    }
}

