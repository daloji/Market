package com.market.service;

import com.market.model.ScalpingSignal;
import com.market.model.ScalpingTrade;
import com.market.model.ScalpingTradeLog;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Automatic scalping service for BTC/USDT Futures based on 1m signals.
 *
 * Compared to BinanceAutoTradeService (swing/intraday on 1h), this service:
 *   - Uses ScalpingAnalysisService (1m candles, RSI7/EMA5-13/MACD fast)
 *   - Tighter default TP/SL: TP=0.3%, SL=0.15% (configurable)
 *   - Smaller default amount: 20 USDT per trade
 *   - No signal confirmation (scalping reacts fast)
 *   - Short cooldown: 5 minutes after closing a position
 *   - Single TP level (simpler, faster execution)
 *   - Completely isolated — does NOT interfere with BinanceAutoTradeService
 *
 * Called every minute by MarketScheduler when enabled.
 */
@ApplicationScoped
public class BinanceScalpingTradeService {

    private static final Logger LOG = Logger.getLogger(BinanceScalpingTradeService.class);

    private static final long   COOLDOWN_MS          = 10L * 60 * 1000;   // 10 min (was 5)
    private static final long   LOSS_STREAK_COOL_MS  = 30L * 60 * 1000;   // 30 min after streak
    private static final int    LOSS_STREAK_LIMIT    = 2;                  // pause after 2 SL
    private static final double DEFAULT_AMOUNT   = 50.0;
    private static final int    DEFAULT_LEVERAGE = 10;
    private static final double DEFAULT_TP_PCT   = 0.3;
    private static final double DEFAULT_SL_PCT   = 0.15;
    private static final int    DEFAULT_MIN_CONF = 65;

    @Inject ScalpingAnalysisService  scalpingService;
    @Inject BinanceFuturesService    futuresService;
    @Inject TelegramAlertService     telegramService;
    @Inject BinanceTradeCoordinator  coordinator;

    // ── Runtime state ─────────────────────────────────────────────────────────
    private final AtomicBoolean           enabled  = new AtomicBoolean(false);
    private final AtomicInteger           minConf  = new AtomicInteger(0);
    private final AtomicReference<Double> amount   = new AtomicReference<>(0.0);
    private final AtomicInteger           leverage = new AtomicInteger(0);
    private final AtomicReference<Double> tpPct    = new AtomicReference<>(0.0);
    private final AtomicReference<Double> slPct    = new AtomicReference<>(0.0);

    private volatile ScalpResult lastResult;
    private volatile Instant     lastTradeAt;

    /** Consecutive SL hits since last TP or manual reset. */
    private volatile int     consecutiveLosses  = 0;
    /** If set, no new trade before this timestamp (loss-streak cooldown). */
    private volatile Instant lossStreakCoolUntil = null;

    // Active position tracking
    private volatile String  activeDir;
    private volatile double  activeSlPrice;
    private volatile double  activeTpPrice;   // TP1 (primary — 60% of position)
    private volatile double  activeTp1Price;
    private volatile double  activeTp2Price;  // secondary — 40% remaining
    private volatile boolean activeTp1Hit;    // true once TP1 is reached
    private volatile double  activeQty;
    private volatile double  activeQty40;     // remaining qty after TP1 hit
    private volatile double  activeTp1Pnl;   // PnL locked in at TP1 partial close
    private volatile double  activeFees;        // commissions accumulées (entry + TP1)
    private volatile long    activeTp1CloseMs;  // timestamp après fetch TP1 fills (pour filtre reconcile)
    private volatile double  activeEntryPrice;
    private volatile int     activeConf;
    private volatile Instant activeOpenedAt;

    // Trade history (in-memory cache + DB persistence)
    private final java.util.Deque<ScalpTrade> tradeHistory =
        new java.util.concurrent.ConcurrentLinkedDeque<>();
    private static final int MAX_HISTORY = 100;

    // ── Startup: load history from DB ─────────────────────────────────────────

    @Transactional
    void onStart(@Observes StartupEvent ev) {
        List<ScalpingTrade> saved = ScalpingTrade.findRecent(MAX_HISTORY);
        // Add oldest first so deque order is chronological
        for (int i = saved.size() - 1; i >= 0; i--) {
            ScalpingTrade e = saved.get(i);
            ScalpTrade t    = new ScalpTrade();
            t.direction   = e.direction;
            t.entryPrice  = e.entryPrice;
            t.exitPrice   = e.exitPrice;
            t.tpPrice     = e.tpPrice;
            t.slPrice     = e.slPrice;
            t.confidence  = e.confidence;
            t.pnl         = e.pnl;
            t.status      = e.status;
            t.tp1Hit      = e.tp1Hit;
            t.tp1Pnl      = e.tp1Pnl;
            t.fees        = e.fees;
            t.pnlNet      = e.pnlNet;
            t.amountUsdt  = e.amountUsdt;
            t.leverage    = e.leverage;
            t.openedAt    = e.openedAt;
            t.closedAt    = e.closedAt;
            t.dbId        = e.id;
            tradeHistory.addLast(t);
        }
        // Restore active position only if Binance confirms the position still exists
        ScalpingTrade open = ScalpingTrade.findOpenTrade();
        if (open != null) {
            boolean binanceConfirmed = false;
            double  binanceQty       = 0;
            if (futuresService.isConfigured()) {
                try {
                    String posJson = futuresService.getPositionRisk("BTCUSDT");
                    com.fasterxml.jackson.databind.JsonNode arr =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(posJson);
                    if (arr.isArray()) {
                        for (com.fasterxml.jackson.databind.JsonNode n : arr) {
                            double amt = Math.abs(n.path("positionAmt").asDouble(0));
                            if (amt > 0.0001) {
                                binanceConfirmed = true;
                                binanceQty       = amt;
                                break;
                            }
                        }
                    }
                } catch (Exception ex) {
                    LOG.warnf("[Scalping] Impossible de vérifier la position Binance au démarrage: %s", ex.getMessage());
                    // Cannot confirm → assume closed to avoid phantom position
                }
            }
            if (binanceConfirmed) {
                activeDir        = open.direction;
                activeEntryPrice = open.entryPrice;
                activeTpPrice    = open.tpPrice;
                activeSlPrice    = open.slPrice;
                activeOpenedAt   = open.openedAt;
                activeQty        = binanceQty;
                activeConf       = open.confidence;
                coordinator.forceAcquire(BinanceTradeCoordinator.Owner.SCALPING);
                LOG.infof("[Scalping] Position active restaurée depuis DB: %s @ %.1f (qty=%.4f)",
                    activeDir, activeEntryPrice, activeQty);
            } else {
                // Position closed on Binance while app was down → mark as MANUAL in DB
                LOG.infof("[Scalping] Position OPEN en DB (id=%d) introuvable sur Binance → marquée MANUAL", open.id);
                open.status   = "MANUAL";
                open.closedAt = Instant.now();
                // Update in-memory history entry too
                for (ScalpTrade t : tradeHistory) {
                    if (t.dbId != null && t.dbId.equals(open.id)) {
                        t.status   = "MANUAL";
                        t.closedAt = open.closedAt;
                        break;
                    }
                }
            }
        }
        LOG.infof("[Scalping] %d trades chargés depuis la base", saved.size());
    }

    // ── Enable / Disable / Config ─────────────────────────────────────────────

    public void enable()    { enabled.set(true);  LOG.info("[Scalping] ✅ Auto-scalping activé"); }
    public void disable()   { enabled.set(false); LOG.info("[Scalping] 🛑 Auto-scalping désactivé"); }
    public boolean isEnabled() { return enabled.get(); }

    public int    getMinConfidence() { return minConf.get()  > 0 ? minConf.get()  : DEFAULT_MIN_CONF; }
    public double getAmountUsdt()    { return amount.get()   > 0 ? amount.get()   : DEFAULT_AMOUNT; }
    public int    getLeverage()      { return leverage.get() > 0 ? leverage.get() : DEFAULT_LEVERAGE; }
    public double getTpPct()         { return tpPct.get()    > 0 ? tpPct.get()    : DEFAULT_TP_PCT; }
    public double getSlPct()         { return slPct.get()    > 0 ? slPct.get()    : DEFAULT_SL_PCT; }

    public void setMinConfidence(int v)    { minConf.set(v); }
    public void setAmountUsdt(double v)    { amount.set(v); }
    public void setLeverage(int v)         { leverage.set(v); }
    public void setTpPct(double v)         { tpPct.set(v); }
    public void setSlPct(double v)         { slPct.set(v); }

    public ScalpResult lastResult() { return lastResult; }

    public List<ScalpTrade> history() {
        java.util.List<ScalpTrade> list = new java.util.ArrayList<>(tradeHistory);
        java.util.Collections.reverse(list); // newest first
        return list;
    }

    // ── Main entry point ──────────────────────────────────────────────────────

    public ScalpResult checkAndTrade() {
        if (!futuresService.isConfigured()) {
            return last(ScalpResult.skipped("Binance API non configurée"));
        }

        ScalpingSignal sig = scalpingService.getSignal();
        if (sig.error != null) {
            return last(ScalpResult.skipped("Signal error: " + sig.error));
        }

        double price = sig.currentPrice;

        // ── Monitor active position ───────────────────────────────────────────
        if (activeDir != null) {
            // Always verify Binance still holds the position — detects external closes
            // (TP/SL algo orders, liquidation, manual close on Binance UI)
            try {
                String posJson = futuresService.getPositionRisk("BTCUSDT");
                com.fasterxml.jackson.databind.JsonNode arr =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(posJson);
                boolean binanceHasPos = false;
                if (arr.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode n : arr) {
                        if (Math.abs(n.path("positionAmt").asDouble(0)) > 0.0001) {
                            binanceHasPos = true; break;
                        }
                    }
                }
                if (!binanceHasPos) {
                    return last(reconcileClosedPosition(price));
                }
            } catch (Exception ex) {
                LOG.warnf("[Scalping] Vérif Binance position échouée: %s — surveillance interne maintenue", ex.getMessage());
            }

            boolean isLong = "LONG".equals(activeDir);
            boolean slHit  = isLong ? price <= activeSlPrice : price >= activeSlPrice;

            if (slHit) {
                return last(closePosition("SL", price));
            }

            // TP monitoring — Java sends market orders (avoids Binance OCO cancellation of TP2).
            // Falls back to legacy single-TP when activeTp2Price==0.
            double tp1Check = activeTp1Price > 0 ? activeTp1Price : activeTpPrice;
            if (!activeTp1Hit && tp1Check > 0) {
                boolean tp1Reached = isLong ? price >= tp1Check : price <= tp1Check;
                if (tp1Reached) {
                    if (activeTp2Price == 0) {
                        return last(closePosition("TP", price));
                    }
                    return last(closePartial(price));
                }
            } else if (activeTp1Hit) {
                boolean tp2Reached = isLong ? price >= activeTp2Price : price <= activeTp2Price;
                if (tp2Reached) {
                    return last(closePosition("TP2", price));
                }
            }

            if (activeTp1Hit) {
                return last(ScalpResult.skipped(String.format(
                    "Position %s — TP1✅ trailing vers TP2=%.1f SL=%.1f",
                    activeDir, activeTp2Price, activeSlPrice)));
            }
            double tp1Display = activeTp1Price > 0 ? activeTp1Price : activeTpPrice;
            if (activeTp2Price > 0) {
                return last(ScalpResult.skipped(String.format(
                    "Position %s active @ %.1f — TP1=%.1f TP2=%.1f SL=%.1f",
                    activeDir, activeEntryPrice, tp1Display, activeTp2Price, activeSlPrice)));
            }
            return last(ScalpResult.skipped(String.format(
                "Position %s active @ %.1f — TP=%.1f SL=%.1f",
                activeDir, activeEntryPrice, tp1Display, activeSlPrice)));
        }

        if (!enabled.get()) {
            return lastAndLog(sig, "disabled",
                ScalpResult.skipped("Auto-scalping désactivé"));
        }

        // ── Cooldown ──────────────────────────────────────────────────────────
        if (lastTradeAt != null) {
            long elapsed = Instant.now().toEpochMilli() - lastTradeAt.toEpochMilli();
            if (elapsed < COOLDOWN_MS) {
                return lastAndLog(sig, "cooldown",
                    ScalpResult.skipped(String.format(
                        "Cooldown — %d min restantes", (COOLDOWN_MS - elapsed) / 60_000 + 1)));
            }
        }

        // ── Loss-streak protection ────────────────────────────────────────────
        if (lossStreakCoolUntil != null && Instant.now().isBefore(lossStreakCoolUntil)) {
            long remaining = (lossStreakCoolUntil.toEpochMilli() - Instant.now().toEpochMilli()) / 60_000 + 1;
            return lastAndLog(sig, "loss_streak",
                ScalpResult.skipped(String.format(
                    "Pause série de pertes (%d SL consécutifs) — %d min restantes",
                    consecutiveLosses, remaining)));
        }

        // ── Signal check ──────────────────────────────────────────────────────
        String dir = sig.direction;
        if ("WAIT".equals(dir) || dir == null) {
            return lastAndLog(sig, "wait",
                ScalpResult.skipped("Signal WAIT — conf=" + sig.confidence));
        }
        if (sig.confidence < getMinConfidence()) {
            return lastAndLog(sig, "low_conf",
                ScalpResult.skipped(String.format(
                    "Confiance insuffisante: %d < %d", sig.confidence, getMinConfidence())));
        }

        // ── Coordinator: block if auto-trade holds the lock ───────────────────
        if (!coordinator.tryAcquire(BinanceTradeCoordinator.Owner.SCALPING)) {
            return lastAndLog(sig, "coordinator",
                ScalpResult.skipped("Trade classique actif — scalping en attente de clôture"));
        }

        // ── Check no existing position ────────────────────────────────────────
        try {
            String posJson = futuresService.getPositionRisk("BTCUSDT");
            com.fasterxml.jackson.databind.JsonNode arr =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(posJson);
            if (arr.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode n : arr) {
                    if (Math.abs(n.path("positionAmt").asDouble(0)) > 0.0001) {
                        coordinator.release(BinanceTradeCoordinator.Owner.SCALPING);
                        return lastAndLog(sig, "pos_exists",
                            ScalpResult.skipped("Position Binance déjà ouverte"));
                    }
                }
            }
        } catch (Exception e) {
            LOG.warnf("[Scalping] Impossible de vérifier la position: %s — on continue", e.getMessage());
        }

        return last(execute(sig, dir, price));
    }

    /**
     * Force-executes a trade in the given direction at the current market price.
     * Bypasses all signal/confidence/cooldown gates. For testing only.
     * @param dir "LONG" or "SHORT"
     */
    public ScalpResult forceExecute(String dir) {
        try {
            String posJson = futuresService.getPositionRisk("BTCUSDT");
            com.fasterxml.jackson.databind.JsonNode arr =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(posJson);
            double currentPrice = 0;
            if (arr.isArray() && arr.size() > 0) {
                currentPrice = arr.get(0).path("markPrice").asDouble(0);
            }
            if (currentPrice <= 0) {
                // fallback: fetch from premiumIndex
                String idx = futuresService.getPremiumIndex("BTCUSDT");
                currentPrice = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(idx).path("markPrice").asDouble(0);
            }
            ScalpingSignal fake = new ScalpingSignal();
            fake.direction    = dir.toUpperCase();
            fake.confidence   = 100;
            fake.currentPrice = currentPrice;
            return last(execute(fake, dir.toUpperCase(), currentPrice));
        } catch (Exception e) {
            return last(ScalpResult.error("forceExecute failed: " + e.getMessage()));
        }
    }

    // ── Trade execution ───────────────────────────────────────────────────────

    private ScalpResult execute(ScalpingSignal sig, String dir, double entry) {
        String symbol    = "BTCUSDT";
        int    lev       = getLeverage();
        double amt       = getAmountUsdt();
        double tp        = getTpPct();
        double sl        = getSlPct();

        double rawQty    = (amt * lev) / entry;
        double qty       = Math.max(0.001, Math.floor(rawQty * 1000) / 1000.0);
        String qtyStr    = String.format(Locale.US, "%.3f", qty);

        String  entrySide = "LONG".equals(dir) ? "BUY"  : "SELL";
        String  closeSide = "LONG".equals(dir) ? "SELL" : "BUY";
        boolean hedgeMode = futuresService.isHedgeMode();
        String  posSide   = hedgeMode ? dir : null;

        // TP / SL prices — ATR-based from signal when available (TP1=1×ATR, TP2=2×ATR, SL=0.6×ATR)
        double tp1Price = (sig.tp1 > 0)
            ? sig.tp1
            : ("LONG".equals(dir) ? r1(entry * (1 + tp / 100)) : r1(entry * (1 - tp / 100)));
        double tp2Price = (sig.tp2 > 0)
            ? sig.tp2
            : ("LONG".equals(dir) ? r1(entry * (1 + 2 * tp / 100)) : r1(entry * (1 - 2 * tp / 100)));
        double slPrice  = (sig.stopLoss > 0)
            ? sig.stopLoss
            : ("LONG".equals(dir) ? r1(entry * (1 - sl / 100)) : r1(entry * (1 + sl / 100)));

        // Split qty for internal TP tracking: 60% at TP1, 40% at TP2.
        // Fall back to single-TP when position too small to split (Binance min qty = 0.001 BTC).
        boolean canSplit = qty >= 0.002;
        double qty60 = canSplit ? Math.max(0.001, Math.floor(qty * 0.6 * 1000) / 1000.0) : qty;
        double qty40 = canSplit ? Math.max(0.001, Math.floor((qty - qty60) * 1000) / 1000.0) : 0;

        StringBuilder log = new StringBuilder();
        log.append(String.format("[Scalping] %s @ %.2f conf=%d%% — ", dir, entry, sig.confidence));
        try {
            // 1. Cancel stale orders
            try { futuresService.cancelAllOrders(symbol); }
            catch (Exception e) { LOG.warnf("[Scalping] cancelAll: %s", e.getMessage()); }

            // 2. Set leverage
            futuresService.setLeverage(symbol, lev);

            // 3. Market entry
            long entryMs = System.currentTimeMillis();
            String orderJson  = futuresService.placeMarketOrder(symbol, entrySide, qtyStr, posSide);
            double filledPx   = parsePrice(orderJson, entry);
            String entryOrderId = parseField(orderJson, "orderId");
            LOG.infof("[Scalping] Market order placé: %s", orderJson);

            // Wait for Binance to register the position before querying fill and placing SL/TP
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}

            // Binance often returns avgPrice=0 in the immediate market order response.
            // Query the order after the sleep to get the actual weighted average fill price.
            if (filledPx == entry && entryOrderId != null && !entryOrderId.isEmpty()) {
                try {
                    String orderStatus = futuresService.getOrder(symbol, entryOrderId);
                    double realFill = parsePrice(orderStatus, 0);
                    if (realFill > 0) {
                        LOG.infof("[Scalping] Fill réel récupéré: %.2f (signal: %.2f, écart: %+.2f)",
                            realFill, entry, realFill - entry);
                        filledPx = realFill;
                    }
                } catch (Exception e) {
                    LOG.warnf("[Scalping] Impossible de récupérer fill réel: %s — signal price utilisé", e.getMessage());
                }
            }
            log.append(String.format("entrée %.2f. ", filledPx));

            // Fetch entry fees
            FillData entryFill = fetchFillsAfter(entrySide, entryMs);
            activeFees = entryFill != null ? entryFill.fees : 0;

            // Recalculate TP/SL anchored on actual fill price, keeping ATR ratios (TP1=1.3×, TP2=2.6×, SL=0.8×)
            tp1Price = (sig.atr > 0)
                ? ("LONG".equals(dir) ? r1(filledPx + 1.3 * sig.atr) : r1(filledPx - 1.3 * sig.atr))
                : ("LONG".equals(dir) ? r1(filledPx * (1 + tp / 100)) : r1(filledPx * (1 - tp / 100)));
            tp2Price = (sig.atr > 0)
                ? ("LONG".equals(dir) ? r1(filledPx + 2.6 * sig.atr) : r1(filledPx - 2.6 * sig.atr))
                : ("LONG".equals(dir) ? r1(filledPx * (1 + 2 * tp / 100)) : r1(filledPx * (1 - 2 * tp / 100)));
            slPrice = (sig.atr > 0)
                ? ("LONG".equals(dir) ? r1(filledPx - 0.8 * sig.atr) : r1(filledPx + 0.8 * sig.atr))
                : ("LONG".equals(dir) ? r1(filledPx * (1 - sl / 100)) : r1(filledPx * (1 + sl / 100)));

            // Pre-check: if BTC moved past SL during the 1.5s wait, Binance rejects the algoOrder.
            // Widen SL beyond current mark price so the order is accepted and position stays protected.
            // Buffer = max(0.5×ATR, 0.1% mark) — larger than before to handle low-ATR / fast-spike combos.
            try {
                double markPx = futuresService.getMarkPrice(symbol);
                if (markPx > 0) {
                    boolean breached = "SHORT".equals(dir) ? markPx >= slPrice : markPx <= slPrice;
                    if (breached) {
                        double atrBuf = sig.atr > 0
                            ? Math.max(0.5 * sig.atr, r1(markPx * 0.001))
                            : r1(markPx * 0.002);
                        double widenedSl = "SHORT".equals(dir) ? r1(markPx + atrBuf) : r1(markPx - atrBuf);
                        LOG.warnf("[Scalping] Race SL breach — mark=%.1f, SL prévu=%.1f → élargi à %.1f",
                            markPx, slPrice, widenedSl);
                        slPrice = widenedSl;
                    }
                }
            } catch (Exception e) {
                LOG.warnf("[Scalping] Pré-vérif mark price échouée (SL original conservé): %s", e.getMessage());
            }

            // 4. SL — must succeed, otherwise close immediately (trading without SL is unacceptable)
            String slStatus;
            try {
                String slResp;
                try {
                    slResp = futuresService.placeCloseOrder(symbol, closeSide, "STOP_MARKET", slPrice, qtyStr, posSide);
                } catch (Exception e1) {
                    LOG.warnf("[Scalping] SL tentative 1 échouée: %s — retry dans 500ms", e1.getMessage());
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    // Re-fetch mark price before retry — BTC may have moved further past SL during the 500ms wait.
                    try {
                        double retryMark = futuresService.getMarkPrice(symbol);
                        if (retryMark > 0) {
                            boolean stillBreached = "SHORT".equals(dir) ? retryMark >= slPrice : retryMark <= slPrice;
                            if (stillBreached) {
                                double atrBuf = sig.atr > 0
                                    ? Math.max(0.5 * sig.atr, r1(retryMark * 0.001))
                                    : r1(retryMark * 0.002);
                                double widenedSl = "SHORT".equals(dir) ? r1(retryMark + atrBuf) : r1(retryMark - atrBuf);
                                LOG.warnf("[Scalping] SL retry re-widen — mark=%.1f → SL %.1f→%.1f",
                                    retryMark, slPrice, widenedSl);
                                slPrice = widenedSl;
                            }
                        }
                    } catch (Exception ew) {
                        LOG.warnf("[Scalping] Re-vérif mark price (retry) échouée: %s", ew.getMessage());
                    }
                    slResp = futuresService.placeCloseOrder(symbol, closeSide, "STOP_MARKET", slPrice, qtyStr, posSide);
                }
                LOG.infof("[Scalping] SL order réponse: %s", slResp);
                slStatus = "✅ SL " + String.format(Locale.US, "%.1f", slPrice);
            } catch (Exception e) {
                LOG.errorf("[Scalping] SL Binance échoué — fermeture urgence: %s", e.getMessage());
                double emergencyExitPx = filledPx;
                try {
                    futuresService.closeWithMarket(symbol, closeSide, qtyStr, posSide);
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    FillData ef = fetchFillsAfter(closeSide, System.currentTimeMillis() - 2000);
                    if (ef != null && ef.avgPrice > 0) emergencyExitPx = ef.avgPrice;
                } catch (Exception closeEx) {
                    LOG.errorf("[Scalping] Fermeture urgence échouée: %s", closeEx.getMessage());
                }
                double emergencyPnl = ("LONG".equals(dir)
                    ? (emergencyExitPx - filledPx)
                    : (filledPx - emergencyExitPx)) * qty;
                ScalpTrade errTrade = new ScalpTrade();
                errTrade.direction  = dir;
                errTrade.entryPrice = filledPx;
                errTrade.exitPrice  = emergencyExitPx;
                errTrade.tpPrice    = tp1Price;
                errTrade.tp2Price   = tp2Price;
                errTrade.slPrice    = slPrice;
                errTrade.confidence = sig.confidence;
                errTrade.openedAt   = Instant.now();
                errTrade.closedAt   = Instant.now();
                errTrade.pnl        = emergencyPnl;
                errTrade.fees       = activeFees;
                errTrade.pnlNet     = emergencyPnl - activeFees;
                errTrade.status     = "SL_FAILED";
                errTrade.dbId       = persistTrade(errTrade);
                tradeHistory.addLast(errTrade);
                if (tradeHistory.size() > MAX_HISTORY) tradeHistory.pollFirst();
                // Apply cooldown + loss streak so the bot doesn't immediately retry after SL failure
                lastTradeAt = Instant.now();
                consecutiveLosses++;
                if (consecutiveLosses >= LOSS_STREAK_LIMIT) {
                    lossStreakCoolUntil = Instant.now().plusMillis(LOSS_STREAK_COOL_MS);
                    LOG.warnf("[Scalping] ⚠ %d SL_FAILED consécutifs — pause de 30 min", consecutiveLosses);
                    telegramService.sendCloseAlert(dir,
                        String.format("SL_FAILED — pause %d SL consécutifs", consecutiveLosses),
                        emergencyExitPx, errTrade.pnlNet);
                }
                coordinator.release(BinanceTradeCoordinator.Owner.SCALPING);
                return ScalpResult.error("SL non placé — position fermée par sécurité: " + e.getMessage());
            }

            // 5. TP1 Binance (60% qty)
            String qty60Str = String.format(Locale.US, "%.3f", qty60);
            try {
                String tp1Resp = futuresService.placeCloseOrder(symbol, closeSide, "TAKE_PROFIT_MARKET", tp1Price, qty60Str, posSide);
                LOG.infof("[Scalping] TP1 order réponse: %s", tp1Resp);
            } catch (Exception e) {
                LOG.warnf("[Scalping] TP1 Binance échoué (Java monitoring actif): %s", e.getMessage());
            }

            // 6. TP2 Binance (40% qty) — algo orders indépendants, pas d'OCO, TP1 ne cancelle pas TP2
            if (canSplit && qty40 >= 0.001) {
                String qty40Str = String.format(Locale.US, "%.3f", qty40);
                try {
                    String tp2Resp = futuresService.placeCloseOrder(symbol, closeSide, "TAKE_PROFIT_MARKET", tp2Price, qty40Str, posSide);
                    LOG.infof("[Scalping] TP2 order réponse: %s", tp2Resp);
                } catch (Exception e) {
                    LOG.warnf("[Scalping] TP2 Binance échoué (Java monitoring actif): %s", e.getMessage());
                }
            }

            // 6. Activate internal tracking
            activeDir        = dir;
            activeEntryPrice = filledPx;
            activeSlPrice    = slPrice;
            activeTp1Price   = tp1Price;
            activeTp2Price   = canSplit ? tp2Price : 0;  // 0 → single-TP path (qty too small)
            activeTpPrice    = tp1Price;  // primary target for internal monitor
            activeTp1Hit     = false;
            activeTp1Pnl     = 0;
            activeQty        = qty;
            activeQty40      = qty40;
            activeConf       = sig.confidence;
            activeOpenedAt   = Instant.now();

            // Record open trade in history + DB
            ScalpTrade trade = new ScalpTrade();
            trade.direction  = dir;
            trade.entryPrice = filledPx;
            trade.tpPrice    = tp1Price;
            trade.tp2Price   = tp2Price;
            trade.slPrice    = slPrice;
            trade.confidence = sig.confidence;
            trade.amountUsdt = amt;
            trade.leverage   = lev;
            trade.openedAt   = activeOpenedAt;
            trade.status     = "OPEN";
            trade.dbId       = persistTrade(trade);
            tradeHistory.addLast(trade);
            if (tradeHistory.size() > MAX_HISTORY) tradeHistory.pollFirst();

            // Persist full signal snapshot for post-mortem analysis
            try {
                persistSignalLog(sig, "placed", null, trade.dbId, filledPx, tp1Price, tp2Price, slPrice);
            } catch (Exception logEx) {
                LOG.warnf("[Scalping] Log indicateurs non persisté: %s", logEx.getMessage());
            }

            String summary = canSplit
                ? String.format("%s @ %.1f | %s | TP1=%.1f TP2=%.1f (Java)", dir, filledPx, slStatus, tp1Price, tp2Price)
                : String.format("%s @ %.1f | %s | TP=%.1f (single, qty min)", dir, filledPx, slStatus, tp1Price);
            LOG.infof("[Scalping] %s", summary);
            telegramService.sendScalpingAlert(dir, filledPx, tp1Price, canSplit ? tp2Price : 0,
                slPrice, sig.confidence, lev, amt);

            return ScalpResult.placed(dir, sig.confidence, filledPx, tp1Price, slPrice, summary);

        } catch (Exception e) {
            LOG.errorf("[Scalping] ❌ Erreur trade: %s", e.getMessage());
            return ScalpResult.error("Erreur trade: " + e.getMessage());
        }
    }

    /** For testing: simulates TP1 hit on active position (real Binance order). */
    public ScalpResult simTp1() {
        if (activeDir == null) return ScalpResult.error("Aucune position active");
        if (activeTp1Hit)      return ScalpResult.error("TP1 déjà déclenché");
        if (activeTp2Price == 0) return ScalpResult.error("Single-TP actif (canSplit=false) — utilise trigger");
        return last(closePartial(activeTp1Price > 0 ? activeTp1Price : activeTpPrice));
    }

    /** For testing: simulates TP2 hit on active position after TP1 (real Binance order). */
    public ScalpResult simTp2() {
        if (activeDir == null)  return ScalpResult.error("Aucune position active");
        if (!activeTp1Hit)      return ScalpResult.error("TP1 pas encore déclenché — appelle sim-tp1 d'abord");
        return last(closePosition("TP2", activeTp2Price > 0 ? activeTp2Price : activeTpPrice));
    }

    /** Partially closes 60% of position at TP1 via market order; keeps 40% tracking for TP2. */
    private ScalpResult closePartial(double price) {
        boolean isLong    = "LONG".equals(activeDir);
        double  qty60     = activeQty - activeQty40;
        String  closeSide = isLong ? "SELL" : "BUY";
        String  qtyStr    = String.format(Locale.US, "%.3f", qty60);
        String  posSide   = futuresService.isHedgeMode() ? activeDir : null;
        long    closeMs   = System.currentTimeMillis();
        try {
            futuresService.closeWithMarket("BTCUSDT", closeSide, qtyStr, posSide);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("-2022") || msg.contains("-4003")) {
                LOG.infof("[Scalping] TP1 déjà fermé côté Binance (%s @ %.2f) — code: %s",
                    activeDir, price, msg.contains("-2022") ? "-2022" : "-4003");
            } else {
                LOG.errorf("[Scalping] ❌ TP1 fermeture partielle échouée: %s", msg);
                return ScalpResult.error("TP1 fermeture partielle échouée: " + msg);
            }
        }
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        FillData fill = fetchFillsAfter(closeSide, closeMs);
        double actualPrice;
        if (fill != null && fill.avgPrice > 0) {
            actualPrice = fill.avgPrice;
        } else {
            // No fills in narrow window after closeMs — SL may have fired before TP1 detection.
            // Widen the search to the full trade window starting from activeOpenedAt.
            FillData broader = activeOpenedAt != null
                ? fetchFillsAfter(closeSide, activeOpenedAt.toEpochMilli())
                : null;
            if (broader != null && broader.avgPrice > 0) {
                fill = broader;
                actualPrice = broader.avgPrice;
                LOG.infof("[Scalping] TP1 fill récupéré fenêtre élargie: %.2f (depuis openedAt)", actualPrice);
            } else {
                actualPrice = price;
                LOG.warnf("[Scalping] TP1 aucun fill trouvé — fallback trigger %.2f", price);
            }
        }

        // If fill is on the wrong side of entry, SL fired before Java detected TP1.
        // Treat the whole position as an SL close using the real fill price.
        boolean wrongDirection = isLong ? actualPrice < activeEntryPrice : actualPrice > activeEntryPrice;
        if (wrongDirection) {
            LOG.warnf("[Scalping] ⚠ TP1 fill en perte %.2f (entry=%.2f) — SL détecté, fermeture totale",
                actualPrice, activeEntryPrice);
            double closeFees = fill != null ? fill.fees : 0;
            double totalFees = activeFees + closeFees;
            double fullQty   = activeQty;
            double pnl       = (isLong ? (actualPrice - activeEntryPrice) : (activeEntryPrice - actualPrice)) * fullQty;
            double pnlNet    = pnl - totalFees;
            for (ScalpTrade t : tradeHistory) {
                if ("OPEN".equals(t.status) && activeDir.equals(t.direction)) {
                    t.exitPrice = actualPrice; t.pnl = pnl; t.fees = totalFees;
                    t.pnlNet = pnlNet; t.closedAt = Instant.now();
                    t.tp1Hit = false; t.tp1Pnl = 0; t.status = "SL";
                    updateTrade(t); break;
                }
            }
            lastTradeAt = Instant.now();
            consecutiveLosses++;
            if (consecutiveLosses >= LOSS_STREAK_LIMIT) {
                lossStreakCoolUntil = Instant.now().plusMillis(LOSS_STREAK_COOL_MS);
                LOG.warnf("[Scalping] ⚠ %d SL consécutifs — pause de 30 min", consecutiveLosses);
                telegramService.sendCloseAlert(activeDir,
                    String.format("PAUSE %d SL consécutifs", consecutiveLosses), actualPrice, pnlNet);
            }
            telegramService.sendCloseAlert(activeDir, "SL scalping", actualPrice, pnlNet);
            clearActive();
            sendTradeSummary();
            return ScalpResult.closed("SL", actualPrice, pnlNet);
        }

        activeFees        += fill != null ? fill.fees : 0;
        activeTp1CloseMs   = System.currentTimeMillis();
        activeTp1Pnl = isLong
            ? (actualPrice - activeEntryPrice) * qty60
            : (activeEntryPrice - actualPrice) * qty60;
        LOG.infof("[Scalping] TP1 %s @ %.2f fill=%.2f (%.3f BTC, PnL=%.2f$) — trailing 40%% vers TP2=%.1f",
            activeDir, price, actualPrice, qty60, activeTp1Pnl, activeTp2Price);
        telegramService.sendCloseAlert(activeDir, "TP1 scalping", actualPrice, activeTp1Pnl);
        activeTp1Hit  = true;
        activeQty     = activeQty40;
        activeTpPrice = activeTp2Price;
        for (ScalpTrade t : tradeHistory) {
            if ("OPEN".equals(t.status) && activeDir.equals(t.direction)) {
                t.tp1Hit = true;
                t.tp1Pnl = activeTp1Pnl;
                updateTrade(t);
                break;
            }
        }
        return ScalpResult.skipped(String.format(
            "TP1 @ %.1f fill=%.1f — trailing 40%% vers TP2 @ %.1f", price, actualPrice, activeTp2Price));
    }

    private ScalpResult closePosition(String reason, double price) {
        String closeSide = "LONG".equals(activeDir) ? "SELL" : "BUY";
        String qtyStr    = String.format(Locale.US, "%.3f", activeQty);
        String posSide   = futuresService.isHedgeMode() ? activeDir : null;
        long   closeMs   = System.currentTimeMillis();
        try {
            futuresService.closeWithMarket("BTCUSDT", closeSide, qtyStr, posSide);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            // -2022: ReduceOnly rejected — position already closed by algo order
            // -4003: Quantity <= 0 — no open position on Binance (e.g. after restart)
            if (msg.contains("-2022") || msg.contains("-4003")) {
                LOG.infof("[Scalping] Position déjà fermée côté Binance (%s @ %.2f) — code: %s",
                    reason, price, msg.contains("-2022") ? "-2022" : "-4003");
            } else {
                LOG.errorf("[Scalping] ❌ Fermeture échouée: %s", msg);
                return ScalpResult.error("Fermeture échouée: " + msg);
            }
        }
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        FillData fill = fetchFillsAfter(closeSide, closeMs);
        double actualPrice;
        if (fill != null && fill.avgPrice > 0) {
            actualPrice = fill.avgPrice;
        } else {
            // No fills in narrow window — SL/TP may have fired before Java detection.
            // Widen search: from TP1 close time if TP1 already hit, else from trade open.
            long broaderSince = (activeTp1Hit && activeTp1CloseMs > 0) ? activeTp1CloseMs
                : (activeOpenedAt != null ? activeOpenedAt.toEpochMilli() : closeMs - 300_000);
            FillData broader = fetchFillsAfter(closeSide, broaderSince);
            if (broader != null && broader.avgPrice > 0) {
                fill = broader;
                actualPrice = broader.avgPrice;
                LOG.infof("[Scalping] %s fill récupéré fenêtre élargie: %.2f", reason, actualPrice);
            } else {
                actualPrice = price;
                LOG.warnf("[Scalping] %s aucun fill trouvé — fallback trigger %.2f", reason, price);
            }
        }
        double closeFees   = fill != null ? fill.fees : 0;
        double totalFees   = activeFees + closeFees;
        double pnl = ("LONG".equals(activeDir)
            ? (actualPrice - activeEntryPrice) * activeQty
            : (activeEntryPrice - actualPrice) * activeQty)
            + activeTp1Pnl;
        double pnlNet = pnl - totalFees;
        LOG.infof("[Scalping] %s %s @ %.2f fill=%.2f (entrée %.2f, PnL=%.2f$ net=%.2f$ frais=%.3f$)",
            reason, activeDir, price, actualPrice, activeEntryPrice, pnl, pnlNet, totalFees);
        telegramService.sendCloseAlert(activeDir, reason + " scalping", actualPrice, pnlNet);
        for (ScalpTrade t : tradeHistory) {
            if ("OPEN".equals(t.status) && t.direction.equals(activeDir)) {
                t.exitPrice = actualPrice;
                t.pnl       = pnl;
                t.fees      = totalFees;
                t.pnlNet    = pnlNet;
                t.closedAt  = Instant.now();
                t.tp1Hit    = activeTp1Hit;
                t.tp1Pnl    = activeTp1Pnl;
                t.status    = (activeTp1Hit && "TP2".equals(reason)) ? "TP1+TP2" : reason;
                updateTrade(t);
                break;
            }
        }
        lastTradeAt = Instant.now();
        // Cancel residual SL algo order on Binance (e.g. after TP2 closes position)
        try { futuresService.cancelAllOrders("BTCUSDT"); }
        catch (Exception ex) { LOG.warnf("[Scalping] cancelAll post-close: %s", ex.getMessage()); }
        // Track consecutive SL hits for streak protection
        if (reason != null && reason.contains("SL")) {
            consecutiveLosses++;
            if (consecutiveLosses >= LOSS_STREAK_LIMIT) {
                lossStreakCoolUntil = Instant.now().plusMillis(LOSS_STREAK_COOL_MS);
                LOG.warnf("[Scalping] ⚠ %d SL consécutifs — pause de 30 min (reprise: %s)",
                    consecutiveLosses, lossStreakCoolUntil);
                telegramService.sendCloseAlert(activeDir,
                    String.format("PAUSE %d SL consécutifs", consecutiveLosses), actualPrice, pnlNet);
            }
        } else {
            // TP or manual → reset streak
            consecutiveLosses  = 0;
            lossStreakCoolUntil = null;
        }
        clearActive();
        sendTradeSummary();
        return ScalpResult.closed(reason, actualPrice, pnlNet);
    }

    // ── Signal analysis logging ───────────────────────────────────────────────

    /** Last {@code limit} signal logs, optionally filtered by outcome. */
    public List<ScalpingTradeLog> signalLogs(int limit, String outcome) {
        int cap = Math.min(limit, 500);
        if (outcome != null && !outcome.isBlank() && !"all".equalsIgnoreCase(outcome)) {
            return ScalpingTradeLog.findRecentByOutcome(outcome, cap);
        }
        return ScalpingTradeLog.findRecent(cap);
    }

    /** Signal log for a specific log id. */
    public ScalpingTradeLog signalLogById(long id) {
        return ScalpingTradeLog.findById(id);
    }

    /** Signal log linked to a placed trade. */
    public ScalpingTradeLog signalLogByTradeId(long tradeId) {
        return ScalpingTradeLog.findByTradeId(tradeId);
    }

    /** Convenience wrapper: catches all exceptions so logging never disrupts the trade flow. */
    private void tryLogAnalysis(ScalpingSignal sig, String outcome, String detail) {
        if (sig == null) return;
        try {
            persistSignalLog(sig, outcome, detail, null, 0, 0, 0, 0);
        } catch (Exception e) {
            LOG.warnf("[Scalping] Signal log non persisté (%s): %s", outcome, e.getMessage());
        }
    }

    /** Helper: logs the analysis then calls last(r) and returns. */
    private ScalpResult lastAndLog(ScalpingSignal sig, String outcome, ScalpResult r) {
        tryLogAnalysis(sig, outcome, r.message);
        return last(r);
    }

    @Transactional
    void persistSignalLog(ScalpingSignal sig, String outcome, String detail,
                          Long tradeId, double entryPx, double tp1, double tp2, double sl) {
        ScalpingTradeLog log = new ScalpingTradeLog();
        log.loggedAt      = Instant.now();
        log.outcome       = outcome;
        log.outcomeDetail = detail != null ? detail.substring(0, Math.min(detail.length(), 500)) : null;
        log.tradeId       = tradeId;
        // Signal
        log.direction     = sig.direction;
        log.confidence    = sig.confidence;
        log.currentPrice  = sig.currentPrice;
        // Signal-computed levels
        log.sigTp1        = sig.tp1;
        log.sigTp2        = sig.tp2;
        log.sigStopLoss   = sig.stopLoss;
        // Placed levels (only set when outcome=placed)
        log.entryPrice    = entryPx;
        log.placedTp1     = tp1;
        log.placedTp2     = tp2;
        log.placedSl      = sl;
        // Volatility
        log.atr           = sig.atr;
        log.atrPct        = sig.atrPct;
        // Pillar scores
        log.pillar1Score  = sig.pillar1Score;
        log.pillar2Score  = sig.pillar2Score;
        log.pillar3Score  = sig.pillar3Score;
        // TF alignment
        log.longTfCount        = sig.longTfCount;
        log.shortTfCount       = sig.shortTfCount;
        log.trend15m           = sig.trend15m;
        log.bias5m             = sig.bias5m;
        log.supertrendDirection = sig.supertrendDirection;
        // 1m indicators
        log.rsi              = sig.rsi7;
        log.macdHistogram    = sig.macdHistogram;
        log.adx              = sig.adx;
        log.plusDI           = sig.plusDI;
        log.minusDI          = sig.minusDI;
        log.marketRegime     = sig.marketRegime;
        log.stochK           = sig.stochK;
        log.stochD           = sig.stochD;
        log.vwap             = sig.vwap;
        log.cvdPct           = sig.cvdPct;
        log.cvdTrend         = sig.cvdTrend;
        log.marketStructure1m = sig.marketStructure1m;
        log.bbState          = sig.bbState;
        log.bbWidth          = sig.bbWidth;
        log.volumeDeltaPct   = sig.volumeDeltaPct;
        log.volumeDeltaTrend = sig.volumeDeltaTrend;
        log.volumeRatio      = sig.volumeRatio;
        // EMAs 1m
        log.ema8    = sig.ema5;   // ema5 field = EMA(8) in v4
        log.ema13   = sig.ema13;
        log.ema21   = sig.ema21;
        // 5m / 15m
        log.ema9_5m   = sig.ema9_5m;
        log.ema21_5m  = sig.ema21_5m;
        log.rsi14_5m  = sig.rsi14_5m;
        log.ema20_15m = sig.ema20_15m;
        log.ema50_15m = sig.ema50_15m;
        log.rsi14_15m = sig.rsi14_15m;
        // Full reasoning
        log.reasoning = sig.reasoning != null
            ? sig.reasoning.substring(0, Math.min(sig.reasoning.length(), 3990))
            : null;
        log.persist();
    }

    @Transactional
    Long persistTrade(ScalpTrade t) {
        ScalpingTrade e = new ScalpingTrade();
        e.direction   = t.direction;
        e.entryPrice  = t.entryPrice;
        e.tpPrice     = t.tpPrice;
        e.tp2Price    = t.tp2Price;
        e.slPrice     = t.slPrice;
        e.confidence  = t.confidence;
        e.openedAt    = t.openedAt;
        e.status      = t.status;
        e.exitPrice   = t.exitPrice;
        e.closedAt    = t.closedAt;
        e.pnl         = t.pnl;
        e.fees        = t.fees;
        e.pnlNet      = t.pnlNet;
        e.amountUsdt  = t.amountUsdt;
        e.leverage    = t.leverage;
        e.persist();
        return e.id;
    }

    @Transactional
    void updateTrade(ScalpTrade t) {
        if (t.dbId == null) return;
        ScalpingTrade e = ScalpingTrade.findById(t.dbId);
        if (e == null) return;
        e.exitPrice = t.exitPrice;
        e.pnl       = t.pnl;
        e.fees      = t.fees;
        e.pnlNet    = t.pnlNet;
        e.closedAt  = t.closedAt;
        e.status    = t.status;
        e.tp1Hit    = t.tp1Hit;
        e.tp1Pnl    = t.tp1Pnl;
    }

    private void clearActive() {
        coordinator.release(BinanceTradeCoordinator.Owner.SCALPING);
        activeDir        = null;
        activeEntryPrice = 0;
        activeSlPrice    = 0;
        activeTpPrice    = 0;
        activeTp1Price   = 0;
        activeTp2Price   = 0;
        activeTp1Hit     = false;
        activeTp1Pnl     = 0;
        activeFees       = 0;
        activeTp1CloseMs = 0;
        activeQty        = 0;
        activeQty40      = 0;
        activeConf       = 0;
        activeOpenedAt   = null;
    }

    private FillData fetchFillsAfter(String side, long sinceMs) {
        try {
            String json = futuresService.getRecentUserTrades("BTCUSDT", 10);
            com.fasterxml.jackson.databind.JsonNode trades =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            double totalQty = 0, totalValue = 0, totalFees = 0;
            for (com.fasterxml.jackson.databind.JsonNode t : trades) {
                if (!side.equals(t.path("side").asText("")) || t.path("time").asLong(0) < sinceMs) continue;
                double qty = t.path("qty").asDouble(0);
                double px  = t.path("price").asDouble(0);
                if (qty <= 0 || px <= 0) continue;
                totalQty   += qty;
                totalValue += px * qty;
                totalFees  += t.path("commission").asDouble(0);
            }
            if (totalQty > 0) return new FillData(totalValue / totalQty, totalFees);
        } catch (Exception ex) {
            LOG.warnf("[Scalping] fetchFillsAfter(%s) échoué: %s", side, ex.getMessage());
        }
        return null;
    }

    /**
     * Infers which order triggered the close based on exit price vs known TP/SL levels.
     * Uses midpoint classification: whichever target (TP or SL) the fill is closest to wins.
     * This avoids overlapping tolerance windows when TP-SL distance < slippage tolerance.
     */
    private String inferCloseReason(double price) {
        boolean isLong = "LONG".equals(activeDir);
        double tp1 = activeTp1Price > 0 ? activeTp1Price : activeTpPrice;
        double tp2 = activeTp2Price;
        double sl  = activeSlPrice;

        if (activeTp1Hit) {
            // TP1 already done — remaining position closed by TP2 or SL
            if (tp2 > 0 && sl > 0) {
                double mid = (tp2 + sl) / 2.0;
                return (isLong ? price >= mid : price <= mid) ? "TP2" : "SL";
            }
            if (sl > 0) return (isLong ? price <= sl : price >= sl) ? "SL" : "EXT_CLOSE";
        } else {
            if (tp1 > 0 && sl > 0) {
                double mid = (tp1 + sl) / 2.0;
                return (isLong ? price >= mid : price <= mid) ? "TP" : "SL";
            }
            if (sl > 0) return (isLong ? price <= sl : price >= sl) ? "SL" : "EXT_CLOSE";
            if (tp1 > 0) return (isLong ? price >= tp1 : price <= tp1) ? "TP" : "EXT_CLOSE";
        }
        return "EXT_CLOSE";
    }

    /** Called when Binance no longer has the position (SL algo fired, liquidation, external close). */
    private ScalpResult reconcileClosedPosition(double markPrice) {
        String closeSide = "LONG".equals(activeDir) ? "SELL" : "BUY";
        // Filter fills: after TP1 close time if TP1 already hit (avoids double-counting TP1 fees)
        // Otherwise after trade open time.
        long sinceMs = (activeTp1Hit && activeTp1CloseMs > 0)
            ? activeTp1CloseMs
            : (activeOpenedAt != null ? activeOpenedAt.toEpochMilli() : System.currentTimeMillis() - 300_000);
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        FillData closeFill = fetchFillsAfter(closeSide, sinceMs);
        double price       = (closeFill != null && closeFill.avgPrice > 0) ? closeFill.avgPrice : markPrice;
        double closeFees   = closeFill != null ? closeFill.fees : 0;
        double totalFees   = activeFees + closeFees;
        String reason      = inferCloseReason(price);
        LOG.infof("[Scalping] ⚡ Position %s @ %.1f fermée côté Binance → %s (fill=%.2f mark=%.2f frais=%.3f$)",
            activeDir, activeEntryPrice, reason, price, markPrice, totalFees);
        double pnl    = ("LONG".equals(activeDir)
            ? (price - activeEntryPrice) * activeQty
            : (activeEntryPrice - price) * activeQty)
            + activeTp1Pnl;
        double pnlNet = pnl - totalFees;
        telegramService.sendCloseAlert(activeDir, reason + " scalping", price, pnlNet);
        for (ScalpTrade t : tradeHistory) {
            if ("OPEN".equals(t.status) && t.direction.equals(activeDir)) {
                t.exitPrice = price;
                t.pnl       = pnl;
                t.fees      = totalFees;
                t.pnlNet    = pnlNet;
                t.closedAt  = Instant.now();
                t.tp1Hit    = activeTp1Hit;
                t.tp1Pnl    = activeTp1Pnl;
                t.status    = reason;
                updateTrade(t);
                break;
            }
        }
        lastTradeAt = Instant.now();
        if ("SL".equals(reason)) {
            consecutiveLosses++;
            if (consecutiveLosses >= LOSS_STREAK_LIMIT) {
                lossStreakCoolUntil = Instant.now().plusMillis(LOSS_STREAK_COOL_MS);
                LOG.warnf("[Scalping] ⚠ %d SL consécutifs — pause de 30 min (reprise: %s)",
                    consecutiveLosses, lossStreakCoolUntil);
                telegramService.sendCloseAlert(activeDir,
                    String.format("PAUSE %d SL consécutifs", consecutiveLosses), price, pnlNet);
            }
        } else {
            consecutiveLosses = 0;
            lossStreakCoolUntil = null;
        }
        clearActive();
        sendTradeSummary();
        return ScalpResult.closed(reason, price, pnlNet);
    }

    /**
     * Manually reconcile internal state with Binance.
     * Call via POST /api/scalping/sync when a position appears stuck open in the UI.
     */
    public ScalpResult syncWithBinance() {
        if (activeDir == null) {
            return ScalpResult.skipped("Aucune position active à synchroniser");
        }
        if (!futuresService.isConfigured()) {
            return ScalpResult.error("Binance API non configurée");
        }
        ScalpingSignal sig = scalpingService.getSignal();
        double price = (sig != null && sig.currentPrice > 0) ? sig.currentPrice : activeEntryPrice;
        try {
            String posJson = futuresService.getPositionRisk("BTCUSDT");
            com.fasterxml.jackson.databind.JsonNode arr =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(posJson);
            boolean binanceHasPos = false;
            if (arr.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode n : arr) {
                    if (Math.abs(n.path("positionAmt").asDouble(0)) > 0.0001) {
                        binanceHasPos = true; break;
                    }
                }
            }
            if (!binanceHasPos) {
                return reconcileClosedPosition(price);
            }
            return ScalpResult.skipped(
                String.format("Position %s confirmée sur Binance (qty=%.4f) — aucune sync nécessaire",
                    activeDir, activeQty));
        } catch (Exception ex) {
            return ScalpResult.error("Impossible de vérifier Binance: " + ex.getMessage());
        }
    }

    /**
     * Reconciles closed local trades against Binance fills for the last {@code days} days.
     * Detects: missing fills, price mismatches, PnL gaps, orphan Binance fills.
     * GET /api/scalping/reconcile-history?days=7
     */
    @Transactional
    public HistoryReconcileReport reconcileTradeHistory(int days) {
        if (!futuresService.isConfigured()) {
            HistoryReconcileReport err = new HistoryReconcileReport();
            err.status = "ERREUR";
            err.summary = "Binance API non configurée";
            return err;
        }

        long sinceMs = Instant.now().minusSeconds((long) days * 86400L).toEpochMilli();
        Instant since = Instant.ofEpochMilli(sinceMs);

        List<ScalpingTrade> local = ScalpingTrade.findClosedAfter(since);
        List<BinanceFill>   fills = fetchBinanceFillsFrom(sinceMs, 500);

        List<HistoryDiscrepancy> disc = new ArrayList<>();
        Set<Integer> usedFills = new HashSet<>();

        for (ScalpingTrade t : local) {
            if (t.closedAt == null) continue;

            String openSide  = "LONG".equals(t.direction) ? "BUY"  : "SELL";
            String closeSide = "LONG".equals(t.direction) ? "SELL" : "BUY";
            long   openMs    = t.openedAt.toEpochMilli();
            long   closeMs   = t.closedAt.toEpochMilli();
            long   ENTRY_WIN = 5 * 60_000L;    // ±5 min pour le fill d'entrée
            long   EXIT_WIN  = 10 * 60_000L;   // closedAt + 10 min pour le fill de sortie

            // ── Entry fills ───────────────────────────────────────────────────
            List<BinanceFill> entryFills = new ArrayList<>();
            for (BinanceFill f : fills) {
                if (openSide.equals(f.side)
                        && Math.abs(f.realizedPnl) < 1.0
                        && f.time >= openMs - ENTRY_WIN
                        && f.time <= openMs + ENTRY_WIN) {
                    entryFills.add(f);
                }
            }

            double binanceEntryPrice = 0;
            if (entryFills.isEmpty()) {
                disc.add(mkDisc(t.id, "ERROR", "MISSING_ENTRY_FILL", "BINANCE",
                    String.format(Locale.US, "[trade #%d %s] Aucun fill d'entrée %s trouvé sur Binance autour de %s",
                        t.id, t.direction, openSide, t.openedAt),
                    String.format(Locale.US, "%.1f", t.entryPrice), "—", t.openedAt, t.closedAt));
            } else {
                for (BinanceFill f : entryFills) usedFills.add(f.index);
                double sumQty = 0, sumVal = 0;
                for (BinanceFill f : entryFills) { sumQty += f.qty; sumVal += f.price * f.qty; }
                binanceEntryPrice = sumQty > 0 ? sumVal / sumQty : 0;
                if (binanceEntryPrice > 0 && t.entryPrice > 0) {
                    double pct = Math.abs(binanceEntryPrice - t.entryPrice) / t.entryPrice * 100.0;
                    if (pct > 0.3) {
                        disc.add(mkDisc(t.id, pct > 0.8 ? "ERROR" : "WARNING",
                            "ENTRY_PRICE_MISMATCH", "LOCAL",
                            String.format(Locale.US, "[trade #%d %s] Prix entrée LOCAL=%.1f ≠ BINANCE=%.1f (écart %.2f%%)",
                                t.id, t.direction, t.entryPrice, binanceEntryPrice, pct),
                            String.format(Locale.US, "%.1f", t.entryPrice),
                            String.format(Locale.US, "%.1f", binanceEntryPrice),
                            t.openedAt, t.closedAt));
                    }
                }
            }

            // ── Exit fills ────────────────────────────────────────────────────
            List<BinanceFill> exitFills = new ArrayList<>();
            for (BinanceFill f : fills) {
                if (closeSide.equals(f.side)
                        && Math.abs(f.realizedPnl) >= 0.01
                        && f.time >= openMs - 2 * 60_000L
                        && f.time <= closeMs + EXIT_WIN) {
                    exitFills.add(f);
                }
            }

            if (exitFills.isEmpty()) {
                disc.add(mkDisc(t.id, "ERROR", "MISSING_EXIT_FILL", "BINANCE",
                    String.format(Locale.US, "[trade #%d %s] Aucun fill de sortie %s trouvé sur Binance (exitPrice=%.1f)",
                        t.id, t.direction, closeSide, t.exitPrice),
                    String.format(Locale.US, "%.1f", t.exitPrice), "—", t.openedAt, t.closedAt));
            } else {
                for (BinanceFill f : exitFills) usedFills.add(f.index);

                // PnL brut (somme realizedPnl de tous les fills de sortie)
                double binancePnl = 0;
                for (BinanceFill f : exitFills) binancePnl += f.realizedPnl;
                double pnlDiff = Math.abs(binancePnl - t.pnl);
                if (pnlDiff > 1.5) {
                    disc.add(mkDisc(t.id, "WARNING", "PNL_MISMATCH", "LOCAL",
                        String.format(Locale.US, "[trade #%d %s] PnL brut LOCAL=%.2f$ ≠ BINANCE=%.2f$ (écart %.2f$)",
                            t.id, t.direction, t.pnl, binancePnl, pnlDiff),
                        String.format(Locale.US, "%.2f$", t.pnl),
                        String.format(Locale.US, "%.2f$", binancePnl),
                        t.openedAt, t.closedAt));
                }

                // Frais totaux (entrée + sortie)
                double entryFees = 0;
                for (BinanceFill f : entryFills) entryFees += f.commission;
                double exitFees = 0;
                for (BinanceFill f : exitFills) exitFees += f.commission;
                double binanceFees = entryFees + exitFees;
                if (t.fees > 0) {
                    double feeDiff = Math.abs(binanceFees - t.fees);
                    if (feeDiff > 0.5) {
                        disc.add(mkDisc(t.id, "WARNING", "FEE_MISMATCH", "LOCAL",
                            String.format(Locale.US, "[trade #%d %s] Frais LOCAL=%.4f$ ≠ BINANCE=%.4f$ (écart %.4f$)",
                                t.id, t.direction, t.fees, binanceFees, feeDiff),
                            String.format(Locale.US, "%.4f$", t.fees),
                            String.format(Locale.US, "%.4f$", binanceFees),
                            t.openedAt, t.closedAt));
                    }
                }

                // Prix de sortie — uniquement si trade simple (pas de TP1 split)
                if (!t.tp1Hit && exitFills.size() == 1 && t.exitPrice > 0) {
                    BinanceFill ef = exitFills.get(0);
                    double pct = Math.abs(ef.price - t.exitPrice) / t.exitPrice * 100.0;
                    if (pct > 0.3) {
                        disc.add(mkDisc(t.id, pct > 0.8 ? "ERROR" : "WARNING",
                            "EXIT_PRICE_MISMATCH", "LOCAL",
                            String.format(Locale.US, "[trade #%d %s] Prix sortie LOCAL=%.1f ≠ BINANCE=%.1f (écart %.2f%%)",
                                t.id, t.direction, t.exitPrice, ef.price, pct),
                            String.format(Locale.US, "%.1f", t.exitPrice),
                            String.format(Locale.US, "%.1f", ef.price),
                            t.openedAt, t.closedAt));
                    }
                }
            }
        }

        // ── Orphan Binance fills (non liés à un trade local) ─────────────────
        for (BinanceFill f : fills) {
            if (!usedFills.contains(f.index) && Math.abs(f.realizedPnl) >= 0.5) {
                disc.add(mkDisc(-1L, "WARNING", "ORPHAN_FILL", "BINANCE",
                    String.format(Locale.US, "Fill Binance sans trade local correspondant: %s %.4f BTC @ %.1f, realizedPnl=%.2f$",
                        f.side, f.qty, f.price, f.realizedPnl),
                    "—",
                    String.format(Locale.US, "%s %.4f BTC @ %.1f (PnL=%.2f$)", f.side, f.qty, f.price, f.realizedPnl),
                    Instant.ofEpochMilli(f.time), null));
            }
        }

        long errors   = 0, warnings = 0;
        for (HistoryDiscrepancy d : disc) {
            if ("ERROR".equals(d.severity))   errors++;
            else if ("WARNING".equals(d.severity)) warnings++;
        }

        HistoryReconcileReport r = new HistoryReconcileReport();
        r.period           = days + " jour(s)";
        r.localClosedCount = local.size();
        r.binanceFillCount = fills.size();
        r.errorsCount      = (int) errors;
        r.warningsCount    = (int) warnings;
        r.discrepancies    = disc;
        r.status           = disc.isEmpty() ? "OK"
                             : (errors > 0 ? "ERREURS_DÉTECTÉES" : "AVERTISSEMENTS");
        r.summary          = String.format(
            "%d trade(s) local · %d fill(s) Binance · %d erreur(s) · %d avertissement(s) %s",
            local.size(), fills.size(), (int) errors, (int) warnings,
            disc.isEmpty() ? "✅" : "⚠");
        LOG.infof("[Scalping] reconcileHistory(%dd): %s", days, r.summary);
        return r;
    }

    private List<BinanceFill> fetchBinanceFillsFrom(long sinceMs, int limit) {
        try {
            String json = futuresService.getUserTradesFrom("BTCUSDT", sinceMs, limit);
            com.fasterxml.jackson.databind.JsonNode arr =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            List<BinanceFill> result = new ArrayList<>();
            int idx = 0;
            for (com.fasterxml.jackson.databind.JsonNode n : arr) {
                BinanceFill f = new BinanceFill();
                f.index       = idx++;
                f.side        = n.path("side").asText();
                f.price       = n.path("price").asDouble(0);
                f.qty         = n.path("qty").asDouble(0);
                f.commission  = Math.abs(n.path("commission").asDouble(0));
                f.realizedPnl = n.path("realizedPnl").asDouble(0);
                f.time        = n.path("time").asLong(0);
                if (f.price > 0 && f.qty > 0) result.add(f);
            }
            return result;
        } catch (Exception e) {
            LOG.warnf("[Scalping] reconcileHistory: fetch fills Binance échoué: %s", e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    private static HistoryDiscrepancy mkDisc(long tradeId, String severity, String type,
            String origin, String description, String localVal, String binanceVal,
            Instant openedAt, Instant closedAt) {
        HistoryDiscrepancy d = new HistoryDiscrepancy();
        d.tradeId     = tradeId;
        d.severity    = severity;
        d.type        = type;
        d.origin      = origin;
        d.description = description;
        d.localValue  = localVal;
        d.binanceValue= binanceVal;
        d.openedAt    = openedAt  != null ? openedAt.toString()  : null;
        d.closedAt    = closedAt  != null ? closedAt.toString()  : null;
        return d;
    }

    public Map<String, Object> statusMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled",       enabled.get());
        m.put("configured",    futuresService.isConfigured());
        m.put("minConfidence", getMinConfidence());
        m.put("amountUsdt",    getAmountUsdt());
        m.put("leverage",      getLeverage());
        m.put("tpPct",         getTpPct());
        m.put("slPct",         getSlPct());
        m.put("activeDir",     activeDir);
        double tp1Disp = activeTp1Price > 0 ? activeTp1Price : activeTpPrice;
        m.put("activeEntry",   activeDir != null ? activeEntryPrice : null);
        m.put("activeTp",      activeDir != null ? tp1Disp         : null);  // backward compat
        m.put("activeTp1",     activeDir != null ? activeTp1Price   : null);
        m.put("activeTp2",     activeDir != null ? activeTp2Price   : null);
        m.put("activeTp1Hit",  activeDir != null ? activeTp1Hit     : null);
        m.put("activeSl",      activeDir != null ? activeSlPrice    : null);
        m.put("activeQty",     activeDir != null ? activeQty        : null);
        m.put("activeConf",    activeDir != null ? activeConf       : null);
        m.put("activeOpenedAt",activeDir != null && activeOpenedAt != null
                               ? activeOpenedAt.toString() : null);
        m.put("lastResult",    lastResult != null ? lastResult : Map.of());
        m.put("consecutiveLosses", consecutiveLosses);
        BinanceTradeCoordinator.Owner coordOwner = coordinator.getOwner();
        m.put("coordinatorOwner", coordOwner != null ? coordOwner.name() : null);
        m.put("coordinatorBlocked", coordOwner != null && coordOwner != BinanceTradeCoordinator.Owner.SCALPING);
        if (lossStreakCoolUntil != null && Instant.now().isBefore(lossStreakCoolUntil))
            m.put("lossStreakCooldown", Math.max(0,
                lossStreakCoolUntil.toEpochMilli() - Instant.now().toEpochMilli()) / 1000);
        if (lastTradeAt != null)
            m.put("cooldownRemaining", Math.max(0,
                COOLDOWN_MS - (Instant.now().toEpochMilli() - lastTradeAt.toEpochMilli())) / 1000);
        // Wallet balances (non-blocking — empty map if API unavailable)
        if (futuresService.isConfigured())
            m.put("wallet", futuresService.getUsdtBalances());
        return m;
    }

    /**
     * Returns a diagnostic checklist of all conditions that block or allow a scalping trade.
     * Each condition has: label, ok (boolean), detail (string).
     */
    public ScalpDiag diagnose() {
        ScalpDiag d = new ScalpDiag();
        d.enabled       = enabled.get();
        d.configured    = futuresService.isConfigured();
        d.minConfidence = getMinConfidence();

        // ── Cooldown ──────────────────────────────────────────────────────────
        if (lastTradeAt != null) {
            long elapsed = Instant.now().toEpochMilli() - lastTradeAt.toEpochMilli();
            d.cooldownOk         = elapsed >= COOLDOWN_MS;
            d.cooldownRemainMin  = (int) Math.max(0, (COOLDOWN_MS - elapsed) / 60_000 + 1);
        } else {
            d.cooldownOk = true;
        }

        // ── Active position ───────────────────────────────────────────────────
        d.hasActivePosition = activeDir != null;
        d.activeDir         = activeDir;

        // ── Signal ────────────────────────────────────────────────────────────
        try {
            ScalpingSignal sig = scalpingService.getSignal();
            if (sig.error != null) {
                d.signalError = sig.error;
            } else {
                d.signalDirection  = sig.direction;
                d.signalConfidence = sig.confidence;
                d.signalPrice      = sig.currentPrice;

                d.directionOk   = "LONG".equals(sig.direction) || "SHORT".equals(sig.direction);
                d.confidenceOk  = d.directionOk && sig.confidence >= getMinConfidence();

                // Individual indicator checks
                d.rsiOk       = sig.rsi7 < 35 || sig.rsi7 > 65;
                d.rsiValue    = sig.rsi7;
                d.rsiDetail   = sig.rsi7 < 30 ? "Oversold (" + String.format(Locale.US,"%.1f", sig.rsi7) + ")"
                              : sig.rsi7 > 70  ? "Overbought (" + String.format(Locale.US,"%.1f", sig.rsi7) + ")"
                              : sig.rsi7 < 35  ? "Bas (" + String.format(Locale.US,"%.1f", sig.rsi7) + ")"
                              : sig.rsi7 > 65  ? "Haut (" + String.format(Locale.US,"%.1f", sig.rsi7) + ")"
                              : "Neutre (" + String.format(Locale.US,"%.1f", sig.rsi7) + ")";

                boolean emaAligned = d.directionOk &&
                    (("LONG".equals(sig.direction)  && sig.currentPrice > sig.ema5 && sig.ema5 > sig.ema13) ||
                     ("SHORT".equals(sig.direction) && sig.currentPrice < sig.ema5 && sig.ema5 < sig.ema13));
                boolean emaPartial = d.directionOk &&
                    (("LONG".equals(sig.direction)  && sig.currentPrice > sig.ema13) ||
                     ("SHORT".equals(sig.direction) && sig.currentPrice < sig.ema13));
                d.emaOk     = emaAligned || emaPartial;
                d.emaDetail = emaAligned ? "Cross aligné (×10 pts)"
                            : emaPartial ? "Partiel (+10 pts)"
                            : "Opposé au signal";

                d.macdOk     = (sig.macdHistogram > 0 && "LONG".equals(sig.direction)) ||
                               (sig.macdHistogram < 0 && "SHORT".equals(sig.direction));
                d.macdDetail = String.format(Locale.US, "Hist %+.2f", sig.macdHistogram);

                d.volDeltaOk     = ("LONG".equals(sig.direction)  && sig.volumeDeltaPct > 52) ||
                                   ("SHORT".equals(sig.direction) && sig.volumeDeltaPct < 48);
                d.volDeltaDetail = String.format(Locale.US, "%.1f%% (%s)", sig.volumeDeltaPct, sig.volumeDeltaTrend);

                d.stochOk     = ("LONG".equals(sig.direction)  && sig.stochK < 20) ||
                                ("SHORT".equals(sig.direction) && sig.stochK > 80);
                d.stochDetail = String.format(Locale.US, "K=%.1f D=%.1f", sig.stochK, sig.stochD);

                d.bbOk     = !"SQUEEZE".equals(sig.bbState);
                d.bbDetail = sig.bbState + " (" + String.format(Locale.US,"%.2f", sig.bbWidth) + "%)";

                d.scoreDetail = String.format("RSI=%+d EMA=%+d MACD=%+d Vol=%+d → conf=%d/100",
                    sig.rsiScore, sig.emaScore, sig.macdScore, sig.volScore, sig.confidence);
            }
        } catch (Exception e) {
            d.signalError = e.getMessage();
        }

        // ── Binance open position ─────────────────────────────────────────────
        if (futuresService.isConfigured()) {
            try {
                String posJson = futuresService.getPositionRisk("BTCUSDT");
                com.fasterxml.jackson.databind.JsonNode arr =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(posJson);
                d.binancePosOk = true;
                if (arr.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode n : arr) {
                        if (Math.abs(n.path("positionAmt").asDouble(0)) > 0.0001) {
                            d.binancePosOk = false;
                            d.binancePosDetail = "Position Binance ouverte: " + n.path("positionAmt").asText();
                            break;
                        }
                    }
                }
                if (d.binancePosOk) d.binancePosDetail = "Aucune position ouverte";
            } catch (Exception e) {
                d.binancePosOk     = true; // non-blocking if API check fails
                d.binancePosDetail = "Vérification impossible: " + e.getMessage();
            }
        } else {
            d.binancePosOk     = false;
            d.binancePosDetail = "API non configurée";
        }

        // ── Would trade? ──────────────────────────────────────────────────────
        d.wouldTrade = d.enabled && d.configured && !d.hasActivePosition
                && d.cooldownOk && d.directionOk && d.confidenceOk && d.binancePosOk;

        if      (!d.enabled)            d.blockingReason = "Auto-scalping désactivé";
        else if (!d.configured)         d.blockingReason = "Clé API Binance non configurée";
        else if (d.hasActivePosition)   d.blockingReason = "Position interne active (" + d.activeDir + ") — attendre SL/TP";
        else if (!d.cooldownOk)         d.blockingReason = "Cooldown " + d.cooldownRemainMin + " min restante(s) après dernier trade";
        else if (d.signalError != null) d.blockingReason = "Erreur signal: " + d.signalError;
        else if (!d.directionOk)        d.blockingReason = "Signal WAIT — pas de direction claire (conf=" + d.signalConfidence + "%)";
        else if (!d.confidenceOk)       d.blockingReason = "Confiance " + d.signalConfidence + "% < seuil " + d.minConfidence + "%";
        else if (!d.binancePosOk)       d.blockingReason = d.binancePosDetail;
        else                            d.blockingReason = null;

        return d;
    }

    public static class ScalpDiag {
        // Gate checks
        public boolean enabled;
        public boolean configured;
        public boolean cooldownOk;
        public int     cooldownRemainMin;
        public boolean hasActivePosition;
        public String  activeDir;
        public boolean binancePosOk;
        public String  binancePosDetail;
        public boolean directionOk;
        public boolean confidenceOk;
        public int     minConfidence;
        public int     signalConfidence;
        public String  signalDirection;
        public double  signalPrice;
        public String  signalError;
        // Indicator checks
        public boolean rsiOk;
        public double  rsiValue;
        public String  rsiDetail;
        public boolean emaOk;
        public String  emaDetail;
        public boolean macdOk;
        public String  macdDetail;
        public boolean volDeltaOk;
        public String  volDeltaDetail;
        public boolean stochOk;
        public String  stochDetail;
        public boolean bbOk;
        public String  bbDetail;
        public String  scoreDetail;
        // Summary
        public boolean wouldTrade;
        public String  blockingReason;
    }

    private static class FillData {
        final double avgPrice;
        final double fees;
        FillData(double p, double f) { this.avgPrice = p; this.fees = f; }
    }

    // ── Trade summary ─────────────────────────────────────────────────────────

    public void sendTradeSummary() {
        if (!telegramService.isEnabled()) return;
        java.util.List<ScalpTrade> closed = new java.util.ArrayList<>();
        for (ScalpTrade t : tradeHistory) {
            if (!"OPEN".equals(t.status) && t.closedAt != null) closed.add(t);
        }
        if (closed.isEmpty()) return;

        long wins     = closed.stream().filter(t -> t.pnlNet > 0).count();
        long losses   = closed.size() - wins;
        double total  = closed.stream().mapToDouble(t -> t.pnlNet).sum();
        double avg    = total / closed.size();
        double best   = closed.stream().mapToDouble(t -> t.pnlNet).max().orElse(0);
        double worst  = closed.stream().mapToDouble(t -> t.pnlNet).min().orElse(0);
        double wr     = wins * 100.0 / closed.size();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US,
            "📊 *Bilan scalping — %d trades*\n" +
            "Win rate : %.0f%% | ✅ %d TP | ❌ %d SL\n" +
            "PnL cumulé : %s$%.2f | Moy : %s$%.2f\n" +
            "Meilleur : +$%.2f | Pire : $%.2f\n",
            closed.size(), wr, wins, losses,
            total >= 0 ? "+" : "", total,
            avg >= 0 ? "+" : "", avg,
            best, worst));

        int n = Math.min(5, closed.size());
        sb.append("\n*5 derniers :*\n");
        for (int i = closed.size() - n; i < closed.size(); i++) {
            ScalpTrade t = closed.get(i);
            String d = "LONG".equals(t.direction) ? "🟢 L" : "🔴 S";
            String e = t.pnlNet >= 0 ? "✅" : "❌";
            sb.append(String.format(Locale.US, "%s %s %s$%.2f [%s]\n",
                d, e, t.pnlNet >= 0 ? "+" : "", t.pnlNet, t.status));
        }
        telegramService.sendMessage(sb.toString());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ScalpResult last(ScalpResult r) { lastResult = r; return r; }

    private double parsePrice(String json, double fallback) {
        try {
            com.fasterxml.jackson.databind.JsonNode n =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            double v = n.path("avgPrice").asDouble(0);
            return v > 0 ? v : fallback;
        } catch (Exception e) { return fallback; }
    }

    private String parseField(String json, String field) {
        try {
            com.fasterxml.jackson.databind.JsonNode n =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            String v = n.path(field).asText(null);
            return (v != null && !v.isEmpty() && !"null".equals(v)) ? v : null;
        } catch (Exception e) { return null; }
    }

    private double r1(double v) { return Math.round(v * 10.0) / 10.0; }

    // ── Result DTO ────────────────────────────────────────────────────────────

    public static class ScalpResult {
        public String status;       // "placed" | "closed" | "skipped" | "error"
        public String direction;
        public int    confidence;
        public double entryPrice;
        public double tpPrice;
        public double slPrice;
        public double pnl;
        public String message;

        static ScalpResult placed(String dir, int conf, double entry, double tp, double sl, String msg) {
            ScalpResult r = new ScalpResult();
            r.status = "placed"; r.direction = dir; r.confidence = conf;
            r.entryPrice = entry; r.tpPrice = tp; r.slPrice = sl; r.message = msg; return r;
        }
        static ScalpResult closed(String reason, double price, double pnl) {
            ScalpResult r = new ScalpResult();
            r.status = "closed"; r.entryPrice = price; r.pnl = pnl; r.message = reason; return r;
        }
        static ScalpResult skipped(String msg) {
            ScalpResult r = new ScalpResult(); r.status = "skipped"; r.message = msg; return r;
        }
        static ScalpResult error(String msg) {
            ScalpResult r = new ScalpResult(); r.status = "error"; r.message = msg; return r;
        }
    }

    // ── Analytics ─────────────────────────────────────────────────────────────

    /**
     * Analyse empirique des trades passés : win rate par indicateur, heure, pilier, gate WAIT.
     * GET /api/scalping/analytics?days=30
     */
    @Transactional
    public AnalyticsReport analyzeHistory(int days) {
        Instant since = Instant.now().minusSeconds((long) days * 86400L);

        // ── Collect placed trades with known outcome ───────────────────────────
        List<ScalpingTradeLog> placedLogs = ScalpingTradeLog.findPlacedAfter(since);

        Map<Long, ScalpingTrade> tradeMap = new HashMap<>();
        for (ScalpingTradeLog l : placedLogs) {
            if (l.tradeId == null || tradeMap.containsKey(l.tradeId)) continue;
            ScalpingTrade t = ScalpingTrade.findById(l.tradeId);
            if (t != null && t.closedAt != null) tradeMap.put(l.tradeId, t);
        }

        List<Entry> entries = new ArrayList<>();
        for (ScalpingTradeLog l : placedLogs) {
            ScalpingTrade t = l.tradeId != null ? tradeMap.get(l.tradeId) : null;
            if (t != null) entries.add(new Entry(l, t));
        }

        // ── WAIT breakdown — calculé avant le early-return pour être toujours disponible ──
        List<ScalpingTradeLog> waitLogs = ScalpingTradeLog.findWaitAfter(since);
        WaitBreakdown wb = buildWaitBreakdown(waitLogs);

        AnalyticsReport r = new AnalyticsReport();
        r.period        = days + " jour(s)";
        r.totalTrades   = entries.size();
        r.waitBreakdown = wb;

        if (entries.isEmpty()) {
            r.status    = "PAS_DE_DONNÉES";
            r.topInsight = "Aucun trade fermé sur cette période. Augmentez la fenêtre avec ?days=N.";
            r.byConfidence = r.byAdx = r.byHour = r.byTfAlignment = r.byRsiZone =
                r.byCvd = r.byVolumeRatio = r.byOutcomeType =
                r.byPillar1 = r.byPillar2 = r.byPillar3 = new ArrayList<>();
            return r;
        }

        long wins = 0;
        double totalPnl = 0;
        for (Entry e : entries) {
            if (e.t.pnlNet > 0) wins++;
            totalPnl += e.t.pnlNet;
        }
        r.winCount   = (int) wins;
        r.lossCount  = entries.size() - r.winCount;
        r.winRate    = wins * 100.0 / entries.size();
        r.avgPnlNet  = totalPnl / entries.size();
        r.totalPnlNet= totalPnl;

        // ── Per-bucket win rates ───────────────────────────────────────────────
        r.byConfidence   = bucket(entries, e -> {
            int c = e.l.confidence;
            return c < 65 ? "60-64" : c < 70 ? "65-69" : c < 75 ? "70-74"
                 : c < 80 ? "75-79" : c < 90 ? "80-89" : "90+";
        }, true);

        r.byAdx = bucket(entries, e -> {
            double a = e.l.adx;
            return a < 22 ? "<22" : a < 25 ? "22-25" : a < 28 ? "25-28"
                 : a < 32 ? "28-32" : a < 38 ? "32-38" : "38+";
        }, true);

        r.byHour = bucket(entries, e -> {
            int h = e.l.loggedAt.atZone(java.time.ZoneOffset.UTC).getHour();
            return h < 4 ? "00-04h" : h < 8 ? "04-08h" : h < 12 ? "08-12h"
                 : h < 16 ? "12-16h" : h < 20 ? "16-20h" : "20-24h";
        }, true);

        r.byTfAlignment = bucket(entries, e -> {
            int aligned = Math.max(e.l.longTfCount, e.l.shortTfCount);
            return aligned + "/3 TFs";
        }, true);

        r.byRsiZone = bucket(entries, e -> {
            double rsi = e.l.rsi;
            return rsi < 30 ? "RSI <30" : rsi < 40 ? "RSI 30-40" : rsi < 52 ? "RSI 40-52"
                 : rsi < 60 ? "RSI 52-60" : rsi < 72 ? "RSI 60-72" : "RSI 72+";
        }, true);

        r.byCvd = bucket(entries, e -> {
            double cvd = e.l.cvdPct;
            return cvd > 15 ? "CVD bull fort" : cvd > 5 ? "CVD bull léger"
                 : cvd < -15 ? "CVD bear fort" : cvd < -5 ? "CVD bear léger" : "CVD neutre";
        }, true);

        r.byVolumeRatio = bucket(entries, e -> {
            double vr = e.l.volumeRatio;
            return vr < 1.0 ? "Vol <1.0×" : vr < 1.3 ? "Vol 1.0-1.3×"
                 : vr < 2.0 ? "Vol 1.3-2.0×" : "Vol 2.0×+";
        }, true);

        r.byOutcomeType = bucket(entries, e -> e.t.status, false);

        r.byPillar1 = bucket(entries, e -> {
            int p = Math.abs(e.l.pillar1Score);
            return p < 10 ? "P1 <10" : p < 20 ? "P1 10-20" : p < 30 ? "P1 20-30" : "P1 30-40";
        }, true);
        r.byPillar2 = bucket(entries, e -> {
            int p = Math.abs(e.l.pillar2Score);
            return p < 10 ? "P2 <10" : p < 20 ? "P2 10-20" : p < 30 ? "P2 20-30" : "P2 30+";
        }, true);
        r.byPillar3 = bucket(entries, e -> {
            int p = Math.abs(e.l.pillar3Score);
            return p < 8 ? "P3 <8" : p < 15 ? "P3 8-15" : p < 22 ? "P3 15-22" : "P3 22+";
        }, true);

        // ── Top insight ───────────────────────────────────────────────────────
        r.topInsight = buildInsight(r, entries);
        r.status = "OK";
        return r;
    }

    /** Classifier une liste d'entries en buckets et calculer le win rate de chacun. */
    private interface Bucketer { String bucket(Entry e); }

    private List<AnalyticsBucket> bucket(List<Entry> entries, Bucketer fn, boolean sortByLabel) {
        Map<String, List<Entry>> groups = new LinkedHashMap<>();
        for (Entry e : entries) {
            String key = fn.bucket(e);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
        }
        List<AnalyticsBucket> result = new ArrayList<>();
        for (Map.Entry<String, List<Entry>> me : groups.entrySet()) {
            List<Entry> g = me.getValue();
            AnalyticsBucket b = new AnalyticsBucket();
            b.label = me.getKey();
            b.count = g.size();
            long w = 0; double pnl = 0;
            for (Entry e : g) { if (e.t.pnlNet > 0) w++; pnl += e.t.pnlNet; }
            b.winRate    = b.count == 0 ? 0 : Math.round(w * 1000.0 / b.count) / 10.0;
            b.avgPnlNet  = Math.round(pnl / b.count * 100) / 100.0;
            b.totalPnlNet= Math.round(pnl * 100) / 100.0;
            result.add(b);
        }
        if (sortByLabel) result.sort(Comparator.comparing(b -> b.label));
        else             result.sort((a, b) -> Integer.compare(b.count, a.count));
        return result;
    }

    private WaitBreakdown buildWaitBreakdown(List<ScalpingTradeLog> logs) {
        WaitBreakdown wb = new WaitBreakdown();
        wb.total = logs.size();
        for (ScalpingTradeLog l : logs) {
            String r = l.reasoning != null ? l.reasoning.toLowerCase() : "";
            if      (r.contains("ttm") || r.contains("squeeze"))           wb.ttmSqueeze++;
            else if (r.contains("atr trop bas") || r.contains("adaptatif"))wb.atrGate++;
            else if (r.contains("adx") && r.contains("range"))             wb.adxGate++;
            else if (r.contains("tfs insuffisants") || r.contains("insuffisants"))wb.tfAlignment++;
            else if (r.contains("vol<") || r.contains("vol <"))            wb.volumeGate++;
            else if (r.contains("ms1m") || r.contains("market structure")) wb.marketStructure++;
            else if (r.contains("score insuffisant"))                       wb.scoreTooLow++;
            else                                                            wb.other++;
        }
        return wb;
    }

    private String buildInsight(AnalyticsReport r, List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Bilan: %d trades · WR %.1f%% · PnL net total %.2f$ · moy %.2f$/trade. ",
            r.totalTrades, r.winRate, r.totalPnlNet, r.avgPnlNet));

        // Meilleure heure
        r.byHour.stream().filter(b -> b.count >= 2)
            .max(Comparator.comparingDouble(b -> b.winRate))
            .ifPresent(best -> sb.append(String.format(Locale.US,
                "Meilleure heure UTC: %s → %.1f%% WR (%d trades). ", best.label, best.winRate, best.count)));

        // ADX insight — est-ce que 25-28 est comparable à 28-32 ?
        AnalyticsBucket adx2528 = r.byAdx.stream().filter(b -> "25-28".equals(b.label)).findFirst().orElse(null);
        AnalyticsBucket adx2832 = r.byAdx.stream().filter(b -> "28-32".equals(b.label)).findFirst().orElse(null);
        if (adx2528 != null && adx2832 != null && adx2528.count >= 2 && adx2832.count >= 2) {
            if (adx2528.winRate >= adx2832.winRate - 8) {
                sb.append(String.format(Locale.US,
                    "⚠ ADX 25-28 WR=%.1f%% vs 28-32 WR=%.1f%% → seuil 28 peut-être trop strict. ",
                    adx2528.winRate, adx2832.winRate));
            }
        }

        // Pilier avec meilleure corrélation WR
        String bestPillar = null; double bestDelta = 0;
        List<List<AnalyticsBucket>> pillars = List.of(r.byPillar1, r.byPillar2, r.byPillar3);
        String[] pillarNames = {"Pilier1", "Pilier2", "Pilier3"};
        for (int i = 0; i < 3; i++) {
            List<AnalyticsBucket> p = pillars.get(i);
            if (p.size() < 2) continue;
            double maxWr = p.stream().filter(b -> b.count >= 2).mapToDouble(b -> b.winRate).max().orElse(0);
            double minWr = p.stream().filter(b -> b.count >= 2).mapToDouble(b -> b.winRate).min().orElse(0);
            double delta = maxWr - minWr;
            if (delta > bestDelta) { bestDelta = delta; bestPillar = pillarNames[i]; }
        }
        if (bestPillar != null && bestDelta > 15) {
            sb.append(String.format(Locale.US,
                "%s a la plus forte corrélation avec l'outcome (écart WR %.0f%%). ", bestPillar, bestDelta));
        }

        // Worst outcome type
        r.byOutcomeType.stream().filter(b -> b.count >= 2 && b.avgPnlNet < 0)
            .min(Comparator.comparingDouble(b -> b.avgPnlNet))
            .ifPresent(worst -> sb.append(String.format(Locale.US,
                "Outcome le plus coûteux: %s (moy %.2f$, %d fois). ", worst.label, worst.avgPnlNet, worst.count)));

        return sb.toString();
    }

    // ── History reconciliation DTOs ───────────────────────────────────────────

    /** One detected discrepancy between local DB and Binance fills. */
    public static class HistoryDiscrepancy {
        public long   tradeId;      // local DB id; -1 = orphan Binance fill
        public String severity;     // ERROR | WARNING
        public String type;         // MISSING_ENTRY_FILL | MISSING_EXIT_FILL |
                                    // ENTRY_PRICE_MISMATCH | EXIT_PRICE_MISMATCH |
                                    // PNL_MISMATCH | FEE_MISMATCH | ORPHAN_FILL
        public String origin;       // LOCAL (our data wrong) | BINANCE (fill absent)
        public String description;  // human-readable explanation
        public String localValue;   // value stored locally
        public String binanceValue; // value found on Binance
        public String openedAt;
        public String closedAt;
    }

    /** Full reconciliation report returned by reconcileTradeHistory(). */
    public static class HistoryReconcileReport {
        public String period;
        public int    localClosedCount;
        public int    binanceFillCount;
        public int    errorsCount;
        public int    warningsCount;
        public List<HistoryDiscrepancy> discrepancies;
        public String status;   // OK | AVERTISSEMENTS | ERREURS_DÉTECTÉES | ERREUR
        public String summary;
    }

    /** Internal: parsed Binance user-trade fill. */
    private static class BinanceFill {
        int    index;
        String side;
        double price;
        double qty;
        double commission;
        double realizedPnl;
        long   time;
    }

    // ── Analytics DTOs ────────────────────────────────────────────────────────

    public static class AnalyticsReport {
        public String  period;
        public String  status;
        public int     totalTrades;
        public int     winCount;
        public int     lossCount;
        public double  winRate;        // %
        public double  avgPnlNet;      // $ par trade
        public double  totalPnlNet;    // $ cumulé
        public String  topInsight;

        public List<AnalyticsBucket> byConfidence;
        public List<AnalyticsBucket> byAdx;
        public List<AnalyticsBucket> byHour;
        public List<AnalyticsBucket> byTfAlignment;
        public List<AnalyticsBucket> byRsiZone;
        public List<AnalyticsBucket> byCvd;
        public List<AnalyticsBucket> byVolumeRatio;
        public List<AnalyticsBucket> byOutcomeType;
        public List<AnalyticsBucket> byPillar1;
        public List<AnalyticsBucket> byPillar2;
        public List<AnalyticsBucket> byPillar3;
        public WaitBreakdown         waitBreakdown;
    }

    /** Win rate + PnL pour un bucket d'indicateur donné. */
    public static class AnalyticsBucket {
        public String label;
        public int    count;
        public double winRate;     // %
        public double avgPnlNet;   // $
        public double totalPnlNet; // $
    }

    /** Distribution des raisons de blocage des signaux WAIT. */
    public static class WaitBreakdown {
        public int total;
        public int ttmSqueeze;
        public int atrGate;
        public int adxGate;
        public int tfAlignment;
        public int volumeGate;
        public int marketStructure;
        public int scoreTooLow;
        public int other;
    }

    /** Internal pair (log + trade outcome) for analytics computations. */
    private static class Entry {
        final ScalpingTradeLog  l;
        final ScalpingTrade     t;
        Entry(ScalpingTradeLog l, ScalpingTrade t) { this.l = l; this.t = t; }
    }

    // ── Trade history DTO ─────────────────────────────────────────────────────

    public static class ScalpTrade {
        public Long    dbId;        // DB primary key (null if not persisted)
        public String  direction;
        public double  entryPrice;
        public double  exitPrice;
        public double  tpPrice;     // TP1 (60% of position)
        public double  tp2Price;    // TP2 (40% of position)
        public double  slPrice;
        public int     confidence;
        public double  pnl;
        public String  status;      // "OPEN" | "TP" | "TP1+TP2" | "SL" | "MANUAL"
        public boolean tp1Hit;      // true when TP1 partial close was executed
        public double  tp1Pnl;     // PnL captured at TP1 (60% of position)
        public double  fees;
        public double  pnlNet;
        public double  amountUsdt; // margin placed (USDT)
        public int     leverage;   // leverage used
        public Instant openedAt;
        public Instant closedAt;
    }
}
