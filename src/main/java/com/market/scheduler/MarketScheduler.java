package com.market.scheduler;

import com.market.model.Stock;
import com.market.service.BinanceAutoTradeService;
import com.market.service.BinanceScalpingTradeService;
import com.market.service.FundamentalAnalysisService;
import com.market.service.RecommendationService;
import com.market.service.StockDataService;
import com.market.service.TelegramAlertService;
import com.market.service.TradeService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class MarketScheduler {

    private static final Logger LOG = Logger.getLogger(MarketScheduler.class);

    @Inject StockDataService             stockDataService;
    @Inject RecommendationService        recommendationService;
    @Inject TradeService                 tradeService;
    @Inject FundamentalAnalysisService   fundamentalAnalysisService;
    @Inject BinanceAutoTradeService      autoTradeService;
    @Inject BinanceScalpingTradeService  scalpingService;
    @Inject TelegramAlertService         telegramService;

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
     * Polls Telegram every 5 s for incoming commands from the authorized chat.
     * Supported commands: /start /stop /status /bilan /help
     */
    @Scheduled(every = "5s", identity = "telegram-commands")
    public void processTelegramCommands() {
        if (!telegramService.isEnabled()) return;
        List<String> messages = telegramService.pollCommands();
        for (String raw : messages) {
            // Strip optional @BotName suffix (Telegram adds it in groups)
            String cmd = raw.contains("@") ? raw.substring(0, raw.indexOf('@')) : raw;
            cmd = cmd.toLowerCase().trim();
            LOG.infof("[Telegram] Commande reçue: %s", cmd);
            switch (cmd) {
                case "/stop"   -> {
                    scalpingService.disable();
                    telegramService.sendMessage("🛑 *Scalping arrêté*\nAuto-trading désactivé.");
                }
                case "/start"  -> {
                    scalpingService.enable();
                    telegramService.sendMessage("✅ *Scalping démarré*\nAuto-trading activé.");
                }
                case "/status", "/s" -> telegramService.sendMessage(buildStatusMessage());
                case "/bilan"        -> scalpingService.sendTradeSummary();
                case "/help"         -> telegramService.sendMessage(
                    "📋 *Commandes disponibles :*\n" +
                    "/start — Activer le scalping\n" +
                    "/stop — Arrêter le scalping\n" +
                    "/status — État actuel + wallet\n" +
                    "/bilan — Résumé des trades\n" +
                    "/help — Cette aide");
                default -> {
                    if (cmd.startsWith("/")) {
                        telegramService.sendMessage(
                            "❓ Commande inconnue : `" + cmd + "`\nTape /help pour la liste.");
                    }
                }
            }
        }
    }

    private String buildStatusMessage() {
        Map<String, Object> s = scalpingService.statusMap();
        boolean enabled   = Boolean.TRUE.equals(s.get("enabled"));
        String  activeDir = (String) s.get("activeDir");

        StringBuilder sb = new StringBuilder();
        sb.append(enabled ? "✅ *Scalping actif*\n" : "🛑 *Scalping arrêté*\n");

        if (activeDir != null) {
            String emoji = "LONG".equals(activeDir) ? "🟢" : "🔴";
            double entry = num(s.get("activeEntry"));
            double tp1   = num(s.get("activeTp1"));
            double tp2   = num(s.get("activeTp2"));
            double sl    = num(s.get("activeSl"));
            Object conf  = s.get("activeConf");
            sb.append(String.format(java.util.Locale.US,
                "%s Position %s — entrée $%,.1f | conf %s%%\n" +
                "TP1 : $%,.1f · TP2 : $%,.1f · SL : $%,.1f\n",
                emoji, activeDir, entry, conf != null ? conf : "?", tp1, tp2, sl));
        } else {
            sb.append("Aucune position ouverte\n");
        }

        Object cooldown = s.get("cooldownRemaining");
        if (cooldown instanceof Number n && n.longValue() > 0) {
            sb.append(String.format("⏱ Cooldown : %d min restantes\n", n.longValue() / 60 + 1));
        }

        @SuppressWarnings("unchecked")
        Map<String, Double> wallet = (Map<String, Double>) s.get("wallet");
        if (wallet != null) {
            double avail = wallet.getOrDefault("availableBalance", 0.0);
            double total = wallet.getOrDefault("walletBalance", 0.0);
            sb.append(String.format(java.util.Locale.US,
                "💰 Wallet : $%.2f dispo / $%.2f total", avail, total));
        }

        return sb.toString();
    }

    private double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
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
