package com.market.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.market.model.BitcoinSignal;
import com.market.model.Trade;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Automatic Binance Futures trading service for BTC/USDT.
 *
 * When enabled, {@link #checkAndTrade()} is called every 5 minutes by the scheduler.
 * A trade is placed when ALL conditions are met:
 *   - direction is LONG or SHORT (not WAIT)
 *   - confidence >= minConfidence
 *   - no open BTC/USDT position on Binance
 *   - cooldown period elapsed since last trade
 *
 * Trade flow: cancel stale orders → set leverage → MARKET entry → STOP_MARKET (SL) → TAKE_PROFIT_MARKET (TP1)
 * All orders use closePosition=true and workingType=MARK_PRICE for reliability.
 */
@ApplicationScoped
public class BinanceAutoTradeService {

    private static final Logger LOG = Logger.getLogger(BinanceAutoTradeService.class);

    /** Minimum interval between two consecutive auto-trades. */
    private static final long COOLDOWN_MS = 4L * 60 * 60 * 1000; // 4 hours

    @Inject CryptoAnalysisService analysisService;
    @Inject BinanceFuturesService  futuresService;
    @Inject TradeService           tradeService;

    // Default config from application.properties
    @ConfigProperty(name = "market.binance.futures.auto-trade.min-confidence", defaultValue = "70")
    int defaultMinConfidence;

    @ConfigProperty(name = "market.binance.futures.auto-trade.amount-usdt", defaultValue = "50")
    double defaultAmountUsdt;

    @ConfigProperty(name = "market.binance.futures.auto-trade.leverage", defaultValue = "10")
    int defaultLeverage;

    /** Stop-loss distance in % from entry (e.g. 1.5 → SL at −1.5% for LONG). */
    @ConfigProperty(name = "market.binance.futures.auto-trade.sl-pct", defaultValue = "1.5")
    double defaultSlPct;

    /** Take-profit distance in % from entry (e.g. 3.0 → TP at +3% for LONG). */
    @ConfigProperty(name = "market.binance.futures.auto-trade.tp-pct", defaultValue = "3.0")
    double defaultTpPct;

    // Runtime-mutable settings (0 = not overridden, falls back to config default)
    private final AtomicBoolean           enabled = new AtomicBoolean(false);
    private final AtomicInteger           minConf = new AtomicInteger(0);
    private final AtomicReference<Double> amt     = new AtomicReference<>(0.0);
    private final AtomicInteger           lev     = new AtomicInteger(0);
    private final AtomicReference<Double> slPct   = new AtomicReference<>(0.0);
    private final AtomicReference<Double> tpPct   = new AtomicReference<>(0.0);

    // State tracking
    private volatile String          lastDirection;
    private volatile Instant         lastTradeAt;
    private volatile AutoTradeResult lastResult;

    private final ObjectMapper mapper = new ObjectMapper();

    // ── Public control ────────────────────────────────────────────────────────

    public void enable()               { enabled.set(true);  LOG.info("[AutoTrade] Activé");   }
    public void disable()              { enabled.set(false); LOG.info("[AutoTrade] Désactivé"); }
    public boolean isEnabled()         { return enabled.get(); }
    public AutoTradeResult lastResult(){ return lastResult; }

    public int    getMinConfidence()   { return minConf.get() > 0 ? minConf.get() : defaultMinConfidence; }
    public double getAmountUsdt()      { return amt.get() > 0     ? amt.get()     : defaultAmountUsdt;    }
    public int    getLeverage()        { return lev.get() > 0     ? lev.get()     : defaultLeverage;       }
    public double getSlPct()           { return slPct.get() > 0   ? slPct.get()   : defaultSlPct;          }
    public double getTpPct()           { return tpPct.get() > 0   ? tpPct.get()   : defaultTpPct;          }

    public void setMinConfidence(int v)   { minConf.set(v);  LOG.infof("[AutoTrade] minConf → %d%%", v);    }
    public void setAmountUsdt(double v)   { amt.set(v);      LOG.infof("[AutoTrade] amount → %.2f USDT", v); }
    public void setLeverage(int v)        { lev.set(v);      LOG.infof("[AutoTrade] leverage → ×%d", v);    }
    public void setSlPct(double v)        { slPct.set(v);    LOG.infof("[AutoTrade] SL → %.2f%%", v);       }
    public void setTpPct(double v)        { tpPct.set(v);    LOG.infof("[AutoTrade] TP → %.2f%%", v);       }

    // ── Core: check signal and trade ──────────────────────────────────────────

    /**
     * Main entry point — called by the scheduler every 5 minutes.
     * Evaluates the current BTC signal and places a trade if all conditions pass.
     */
    public AutoTradeResult checkAndTrade() {
        if (!enabled.get())
            return store(AutoTradeResult.skipped("Auto-trade désactivé"));
        if (!futuresService.isConfigured())
            return store(AutoTradeResult.skipped("Clé API Binance Futures non configurée"));

        // Fetch current BTC signal
        BitcoinSignal signal;
        try {
            signal = analysisService.getSignal();
        } catch (Exception e) {
            return store(AutoTradeResult.error("Erreur signal: " + e.getMessage()));
        }

        if (signal.error != null)
            return store(AutoTradeResult.error("Signal BTC error: " + signal.error));

        String dir  = signal.direction;
        int    conf = signal.confidence;
        int    mc   = getMinConfidence();

        // Direction filter
        if (!"LONG".equals(dir) && !"SHORT".equals(dir))
            return store(AutoTradeResult.skipped("Signal WAIT — confiance " + conf + "%"));

        // Confidence filter
        if (conf < mc)
            return store(AutoTradeResult.skipped("Confiance " + conf + "% < seuil " + mc + "%"));

        // Cooldown filter
        if (lastTradeAt != null) {
            long elapsed = Instant.now().toEpochMilli() - lastTradeAt.toEpochMilli();
            if (elapsed < COOLDOWN_MS) {
                long remain = (COOLDOWN_MS - elapsed) / 60_000;
                return store(AutoTradeResult.skipped("Cooldown actif — " + remain + " min restantes"));
            }
        }

        // Check for open position on Binance
        try {
            if (hasOpenPosition()) {
                return store(AutoTradeResult.skipped("Position déjà ouverte sur Binance"));
            }
        } catch (Exception e) {
            // Non-blocking: position check failure doesn't stop the trade
            LOG.warnf("[AutoTrade] Impossible de vérifier la position: %s — on continue", e.getMessage());
        }

        return store(execute(signal));
    }

    // ── Trade execution ────────────────────────────────────────────────────────

    private AutoTradeResult execute(BitcoinSignal signal) {
        String symbol = "BTCUSDT";
        double entry  = signal.currentPrice;
        String dir    = signal.direction;
        int    conf   = signal.confidence;
        double atr    = signal.atr;
        int    lev_   = getLeverage();
        double amt_   = getAmountUsdt();

        // BTC quantity = (margin × leverage) / price, minimum 0.001 BTC
        double rawQty = (amt_ * lev_) / entry;
        String qty    = String.format("%.3f", Math.max(0.001, rawQty));

        double sl  = signal.stopLoss;
        double tp1 = signal.tp1;

        // Fixed-pct SL/TP (configurable, more predictable than ATR-based)
        double slP = getSlPct();
        double tpP = getTpPct();
        if (slP > 0 && tpP > 0) {
            if ("LONG".equals(dir)) {
                sl  = r1(entry * (1 - slP / 100));
                tp1 = r1(entry * (1 + tpP / 100));
            } else {
                sl  = r1(entry * (1 + slP / 100));
                tp1 = r1(entry * (1 - tpP / 100));
            }
        }

        // LONG: BUY to open, SELL to close; SHORT: SELL to open, BUY to close
        String entrySide = "LONG".equals(dir) ? "BUY"  : "SELL";
        String closeSide = "LONG".equals(dir) ? "SELL" : "BUY";

        StringBuilder log = new StringBuilder();
        try {
            // 1. Cancel stale orders (best-effort, don't fail on error)
            try {
                futuresService.cancelAllOrders(symbol);
                log.append("Ordres annulés. ");
            } catch (Exception e) {
                LOG.warnf("[AutoTrade] cancelAll non bloquant: %s", e.getMessage());
            }

            // 2. Set leverage
            futuresService.setLeverage(symbol, lev_);
            log.append("Levier ×").append(lev_).append(" OK. ");

            // 3. Market entry order
            String orderJson  = futuresService.placeMarketOrder(symbol, entrySide, qty);
            double filledPrice = parseAvgPrice(orderJson, entry);
            log.append(dir).append(" ").append(qty).append(" BTC @ ")
               .append(String.format("%.2f", filledPrice)).append(". ");

            // 4. Stop Loss (STOP_MARKET, closePosition=true, MARK_PRICE)
            futuresService.placeCloseOrder(symbol, closeSide, "STOP_MARKET", sl);
            log.append("SL @ ").append(String.format("%.1f", sl)).append(". ");

            // 5. Take Profit TP1 (TAKE_PROFIT_MARKET, closePosition=true, MARK_PRICE)
            futuresService.placeCloseOrder(symbol, closeSide, "TAKE_PROFIT_MARKET", tp1);
            log.append("TP @ ").append(String.format("%.1f", tp1)).append(".");

            // 6. Persist trade in local database
            Trade.Direction tradeDir = "LONG".equals(dir) ? Trade.Direction.LONG : Trade.Direction.SHORT;
            String reasoning = signal.reasoning;
            if (reasoning != null && reasoning.length() > 200) reasoning = reasoning.substring(0, 200);
            tradeService.openTrade(
                    amt_, tradeDir, lev_, filledPrice, 0.0004, atr,
                    sl, tp1, "REAL", "Binance Futures", "BTC/USDT",
                    "AutoTrade conf=" + conf + "% | " + reasoning);

            lastDirection = dir;
            lastTradeAt   = Instant.now();

            LOG.infof("[AutoTrade] ✅ %s ×%d conf=%d%% qty=%s @ %.2f SL=%.1f TP=%.1f",
                    dir, lev_, conf, qty, filledPrice, sl, tp1);
            return AutoTradeResult.placed(dir, conf, filledPrice, sl, tp1,
                    getSlPct(), getTpPct(), lev_, amt_, log.toString());

        } catch (Exception e) {
            LOG.errorf("[AutoTrade] ❌ Échec placement: %s", e.getMessage());
            return AutoTradeResult.error("Échec placement: " + e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean hasOpenPosition() throws Exception {
        String json = futuresService.getPositionRisk("BTCUSDT");
        JsonNode arr = mapper.readTree(json);
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                if (Math.abs(n.path("positionAmt").asDouble(0)) > 0.0001) return true;
            }
        }
        return false;
    }

    private double r1(double v) { return Math.round(v * 10.0) / 10.0; }

    private double parseAvgPrice(String json, double fallback) {
        try {
            JsonNode n = mapper.readTree(json);
            double p = n.path("avgPrice").asDouble(0);
            if (p > 0) return p;
            p = n.path("price").asDouble(0);
            if (p > 0) return p;
        } catch (Exception ignored) {}
        return fallback;
    }

    private AutoTradeResult store(AutoTradeResult r) {
        lastResult = r;
        return r;
    }

    // ── Result DTO ─────────────────────────────────────────────────────────────

    public static class AutoTradeResult {
        public String  status;       // "placed" | "skipped" | "error"
        public String  direction;
        public int     confidence;
        public double  entryPrice;
        public double  sl;
        public double  tp1;
        public double  slPct;
        public double  tpPct;
        public int     leverage;
        public double  amountUsdt;
        public String  message;
        public long    timestamp = System.currentTimeMillis();

        static AutoTradeResult placed(String dir, int conf, double entry,
                                      double sl, double tp1, double slPct, double tpPct,
                                      int lev, double amt, String msg) {
            AutoTradeResult r = new AutoTradeResult();
            r.status = "placed"; r.direction = dir; r.confidence = conf;
            r.entryPrice = entry; r.sl = sl; r.tp1 = tp1;
            r.slPct = slPct; r.tpPct = tpPct;
            r.leverage = lev; r.amountUsdt = amt; r.message = msg;
            return r;
        }

        static AutoTradeResult skipped(String msg) {
            AutoTradeResult r = new AutoTradeResult();
            r.status = "skipped"; r.message = msg;
            return r;
        }

        static AutoTradeResult error(String msg) {
            AutoTradeResult r = new AutoTradeResult();
            r.status = "error"; r.message = msg;
            return r;
        }
    }
}
