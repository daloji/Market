package com.market.scheduler;

import com.market.model.Stock;
import com.market.service.FundamentalAnalysisService;
import com.market.service.RecommendationService;
import com.market.service.StockDataService;
import com.market.service.TradeService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

@ApplicationScoped
public class MarketScheduler {

    private static final Logger LOG = Logger.getLogger(MarketScheduler.class);

    @Inject StockDataService stockDataService;
    @Inject RecommendationService recommendationService;
    @Inject TradeService tradeService;
    @Inject FundamentalAnalysisService fundamentalAnalysisService;

    /**
     * Refreshes quotes and regenerates recommendations for all active stocks.
     * Runs every 5 minutes; the first execution is delayed 30 s to let the
     * initial {@link com.market.service.DataInitializer} startup job finish.
     */
    @Scheduled(every = "5m", delayed = "30s", identity = "market-analysis")
    public void analyzeMarket() {
        List<Stock> stocks = Stock.findActive();
        LOG.infof("Scheduled analysis started — %d stocks", stocks.size());

        for (Stock stock : stocks) {
            stockDataService.fetchAndStoreQuotes(stock.symbol);
            recommendationService.generateRecommendation(stock.symbol);
        }

        LOG.infof("Scheduled analysis done — %d stocks processed", stocks.size());
    }

    /**
     * Updates P&L for all open trades every 15 s using the latest BTC price.
     */
    @Scheduled(every = "15s", identity = "trade-update")
    public void updateTrades() {
        tradeService.updateAllTrades();
    }

    /**
     * Refreshes fundamental (valuation) data for all active stocks once a day at 08:05.
     * Calls analyzeAndStore() through the injected proxy so @Transactional is honoured.
     * A 2 s delay between network fetches respects Yahoo Finance rate limits.
     * Stocks already cached in memory for today are skipped automatically.
     */
    @Scheduled(cron = "0 5 8 * * ?", identity = "fundamental-daily-refresh")
    public void refreshFundamentals() {
        List<String> symbols = Stock.findActive().stream().map(s -> s.symbol).toList();
        LOG.infof("Daily fundamental refresh started — %d stocks", symbols.size());
        int fetched = 0;
        for (String symbol : symbols) {
            if (fundamentalAnalysisService.isCachedToday(symbol)) continue;
            try {
                fundamentalAnalysisService.analyzeAndStore(symbol); // through CDI proxy → @Transactional applied
                fetched++;
                Thread.sleep(2_000); // rate-limit guard between Yahoo Finance requests
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOG.warnf("refreshFundamentals: skipping %s — %s", symbol, e.getMessage());
            }
        }
        LOG.infof("Daily fundamental refresh done: %d fetched, %d from cache",
                  fetched, symbols.size() - fetched);
    }
}
