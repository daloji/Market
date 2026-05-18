package com.market.scheduler;

import com.market.model.Stock;
import com.market.service.BinanceAutoTradeService;
import com.market.service.BinanceScalpingTradeService;
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
    @Inject BinanceAutoTradeService    autoTradeService;
    @Inject BinanceScalpingTradeService scalpingService;

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
     * Checks BTC signal and auto-places Futures trades every 5 minutes when enabled.
     * Runs 60 s after startup (after initial signal cache is warmed up).
     */
    @Scheduled(every = "5m", delayed = "60s", identity = "btc-auto-trade")
    public void autoTrade() {
        if (!autoTradeService.isEnabled()) return;
        BinanceAutoTradeService.AutoTradeResult result = autoTradeService.checkAndTrade();
        if ("placed".equals(result.status)) {
            LOG.infof("[Scheduler] Auto-trade placé: %s conf=%d%% @ %.2f",
                    result.direction, result.confidence, result.entryPrice);
        } else if ("error".equals(result.status)) {
            LOG.warnf("[Scheduler] Auto-trade erreur: %s", result.message);
        }
    }

    /**
     * Checks BTC scalping signal (1m) and auto-places trades every minute when enabled.
     * Completely isolated from the 5m swing auto-trade scheduler.
     */
    @Scheduled(every = "1m", delayed = "90s", identity = "btc-scalping-auto-trade")
    public void scalpingAutoTrade() {
        if (!scalpingService.isEnabled()) return;
        BinanceScalpingTradeService.ScalpResult result = scalpingService.checkAndTrade();
        if ("placed".equals(result.status)) {
            LOG.infof("[Scalping] ✅ Trade placé: %s conf=%d%% @ %.2f",
                result.direction, result.confidence, result.entryPrice);
        } else if ("closed".equals(result.status)) {
            LOG.infof("[Scalping] 🔒 Position fermée (%s) @ %.2f PnL=%.2f%%",
                result.message, result.entryPrice, result.pnl);
        } else if ("error".equals(result.status)) {
            LOG.warnf("[Scalping] ❌ Erreur: %s", result.message);
        } else {
            LOG.debugf("[Scalping] ⏭ Skipped: %s", result.message);
        }
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
