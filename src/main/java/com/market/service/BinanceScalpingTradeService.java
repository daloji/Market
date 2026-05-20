package com.market.service;

import com.market.model.ScalpingSignal;
import com.market.model.ScalpingTrade;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private static final double DEFAULT_AMOUNT   = 20.0;
    private static final int    DEFAULT_LEVERAGE = 10;
    private static final double DEFAULT_TP_PCT   = 0.3;
    private static final double DEFAULT_SL_PCT   = 0.15;
    private static final int    DEFAULT_MIN_CONF = 65;

    @Inject ScalpingAnalysisService scalpingService;
    @Inject BinanceFuturesService   futuresService;
    @Inject TelegramAlertService    telegramService;

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
            t.direction  = e.direction;
            t.entryPrice = e.entryPrice;
            t.exitPrice  = e.exitPrice;
            t.tpPrice    = e.tpPrice;
            t.slPrice    = e.slPrice;
            t.confidence = e.confidence;
            t.pnl        = e.pnl;
            t.status     = e.status;
            t.openedAt   = e.openedAt;
            t.closedAt   = e.closedAt;
            t.dbId       = e.id;
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
            return last(ScalpResult.skipped("Auto-scalping désactivé"));
        }

        // ── Cooldown ──────────────────────────────────────────────────────────
        if (lastTradeAt != null) {
            long elapsed = Instant.now().toEpochMilli() - lastTradeAt.toEpochMilli();
            if (elapsed < COOLDOWN_MS) {
                return last(ScalpResult.skipped(String.format(
                    "Cooldown — %d min restantes", (COOLDOWN_MS - elapsed) / 60_000 + 1)));
            }
        }

        // ── Loss-streak protection ────────────────────────────────────────────
        if (lossStreakCoolUntil != null && Instant.now().isBefore(lossStreakCoolUntil)) {
            long remaining = (lossStreakCoolUntil.toEpochMilli() - Instant.now().toEpochMilli()) / 60_000 + 1;
            return last(ScalpResult.skipped(String.format(
                "Pause série de pertes (%d SL consécutifs) — %d min restantes",
                consecutiveLosses, remaining)));
        }

        // ── Signal check ──────────────────────────────────────────────────────
        String dir = sig.direction;
        if ("WAIT".equals(dir) || dir == null) {
            return last(ScalpResult.skipped("Signal WAIT — conf=" + sig.confidence));
        }
        if (sig.confidence < getMinConfidence()) {
            return last(ScalpResult.skipped(
                String.format("Confiance insuffisante: %d < %d", sig.confidence, getMinConfidence())));
        }

        // ── Check no existing position ────────────────────────────────────────
        try {
            String posJson = futuresService.getPositionRisk("BTCUSDT");
            com.fasterxml.jackson.databind.JsonNode arr =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(posJson);
            if (arr.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode n : arr) {
                    if (Math.abs(n.path("positionAmt").asDouble(0)) > 0.0001) {
                        return last(ScalpResult.skipped("Position Binance déjà ouverte"));
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

        // TP / SL prices — prefer ATR-based TP from signal (more precise than % config)
        double tp1Price = (sig.tp1 > 0)
            ? sig.tp1
            : ("LONG".equals(dir) ? r1(entry * (1 + tp / 100)) : r1(entry * (1 - tp / 100)));
        double tp2Price = (sig.tp2 > 0)
            ? sig.tp2
            : ("LONG".equals(dir) ? r1(entry * (1 + 2 * tp / 100)) : r1(entry * (1 - 2 * tp / 100)));
        double slPrice  = "LONG".equals(dir)
            ? r1(entry * (1 - sl / 100)) : r1(entry * (1 + sl / 100));

        // Split qty for internal TP tracking: 60% at TP1, 40% at TP2
        double qty60 = Math.max(0.001, Math.floor(qty * 0.6 * 1000) / 1000.0);
        double qty40 = Math.max(0.001, Math.floor((qty - qty60) * 1000) / 1000.0);

        StringBuilder log = new StringBuilder();
        log.append(String.format("[Scalping] %s @ %.2f conf=%d%% — ", dir, entry, sig.confidence));
        try {
            // 1. Cancel stale orders
            try { futuresService.cancelAllOrders(symbol); }
            catch (Exception e) { LOG.warnf("[Scalping] cancelAll: %s", e.getMessage()); }

            // 2. Set leverage
            futuresService.setLeverage(symbol, lev);

            // 3. Market entry
            String orderJson  = futuresService.placeMarketOrder(symbol, entrySide, qtyStr, posSide);
            double filledPx   = parsePrice(orderJson, entry);
            log.append(String.format("entrée %.2f. ", filledPx));
            LOG.infof("[Scalping] Market order placé: %s", orderJson);

            // Recalculate SL/TP on filled price
            tp1Price = (sig.tp1 > 0) ? sig.tp1
                : ("LONG".equals(dir) ? r1(filledPx * (1 + tp / 100)) : r1(filledPx * (1 - tp / 100)));
            tp2Price = (sig.tp2 > 0) ? sig.tp2
                : ("LONG".equals(dir) ? r1(filledPx * (1 + 2 * tp / 100)) : r1(filledPx * (1 - 2 * tp / 100)));
            slPrice = "LONG".equals(dir)
                ? r1(filledPx * (1 - sl / 100)) : r1(filledPx * (1 + sl / 100));

            // Wait for Binance to register the position before placing SL/TP
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}

            // 4. SL (full qty — reduces as TPs fill)
            String slStatus;
            try {
                String slResp = futuresService.placeCloseOrder(symbol, closeSide, "STOP_MARKET", slPrice, qtyStr, posSide);
                LOG.infof("[Scalping] SL order réponse: %s", slResp);
                slStatus = "✅ SL " + String.format(Locale.US, "%.1f", slPrice);
            } catch (Exception e) {
                LOG.warnf("[Scalping] SL Binance échoué: %s", e.getMessage());
                slStatus = "⚠ SL " + String.format(Locale.US, "%.1f", slPrice) + " | " + e.getMessage();
            }

            // 5. TP1/TP2 are managed by Java monitoring (closeWithMarket) — no Binance algo orders.
            //    Binance OCO would cancel TP2 when TP1 fires; Java side-steps this by sending
            //    explicit partial market orders at each TP level.

            // 6. Activate internal tracking
            activeDir        = dir;
            activeEntryPrice = filledPx;
            activeSlPrice    = slPrice;
            activeTp1Price   = tp1Price;
            activeTp2Price   = tp2Price;
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
            trade.openedAt   = activeOpenedAt;
            trade.status     = "OPEN";
            trade.dbId       = persistTrade(trade);
            tradeHistory.addLast(trade);
            if (tradeHistory.size() > MAX_HISTORY) tradeHistory.pollFirst();

            String summary = String.format("%s @ %.1f | %s | TP1=%.1f TP2=%.1f (Java)",
                dir, filledPx, slStatus, tp1Price, tp2Price);
            LOG.infof("[Scalping] %s", summary);
            telegramService.sendScalpingAlert(dir, filledPx, tp1Price, slPrice, sig.confidence);

            return ScalpResult.placed(dir, sig.confidence, filledPx, tp1Price, slPrice, summary);

        } catch (Exception e) {
            LOG.errorf("[Scalping] ❌ Erreur trade: %s", e.getMessage());
            return ScalpResult.error("Erreur trade: " + e.getMessage());
        }
    }

    /** Partially closes 60% of position at TP1 via market order; keeps 40% tracking for TP2. */
    private ScalpResult closePartial(double price) {
        boolean isLong = "LONG".equals(activeDir);
        double  qty60  = activeQty - activeQty40;
        String  closeSide = isLong ? "SELL" : "BUY";
        String  qtyStr    = String.format(Locale.US, "%.3f", qty60);
        String  posSide   = futuresService.isHedgeMode() ? activeDir : null;
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
        activeTp1Pnl = isLong
            ? (price - activeEntryPrice) * qty60
            : (activeEntryPrice - price) * qty60;
        LOG.infof("[Scalping] TP1 %s @ %.2f (%.3f BTC, PnL=%.2f$) — trailing 40%% vers TP2=%.1f",
            activeDir, price, qty60, activeTp1Pnl, activeTp2Price);
        telegramService.sendCloseAlert(activeDir, "TP1 scalping", price, activeTp1Pnl);
        activeTp1Hit  = true;
        activeQty     = activeQty40;
        activeTpPrice = activeTp2Price;
        return ScalpResult.skipped(String.format(
            "TP1 @ %.1f — trailing 40%% vers TP2 @ %.1f", price, activeTp2Price));
    }

    private ScalpResult closePosition(String reason, double price) {
        String closeSide = "LONG".equals(activeDir) ? "SELL" : "BUY";
        String qtyStr    = String.format(Locale.US, "%.3f", activeQty);
        String posSide   = futuresService.isHedgeMode() ? activeDir : null;
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
        // Include PnL from TP1 partial close (activeTp1Pnl=0 for full-position closes)
        double pnl = ("LONG".equals(activeDir)
            ? (price - activeEntryPrice) * activeQty
            : (activeEntryPrice - price) * activeQty)
            + activeTp1Pnl;
        String msg = String.format("[Scalping] %s %s @ %.2f (entrée %.2f, PnL=%.2f$)",
            reason, activeDir, price, activeEntryPrice, pnl);
        LOG.info(msg);
        telegramService.sendCloseAlert(activeDir, reason + " scalping", price, pnl);
        for (ScalpTrade t : tradeHistory) {
            if ("OPEN".equals(t.status) && t.direction.equals(activeDir)) {
                t.exitPrice = price;
                t.pnl       = pnl;
                t.closedAt  = Instant.now();
                t.status    = reason;
                updateTrade(t);
                break;
            }
        }
        lastTradeAt = Instant.now();
        // Track consecutive SL hits for streak protection
        if (reason != null && reason.contains("SL")) {
            consecutiveLosses++;
            if (consecutiveLosses >= LOSS_STREAK_LIMIT) {
                lossStreakCoolUntil = Instant.now().plusMillis(LOSS_STREAK_COOL_MS);
                LOG.warnf("[Scalping] ⚠ %d SL consécutifs — pause de 30 min (reprise: %s)",
                    consecutiveLosses, lossStreakCoolUntil);
                telegramService.sendCloseAlert(activeDir,
                    String.format("PAUSE %d SL consécutifs", consecutiveLosses), price, pnl);
            }
        } else {
            // TP or manual → reset streak
            consecutiveLosses  = 0;
            lossStreakCoolUntil = null;
        }
        clearActive();
        return ScalpResult.closed(reason, price, pnl);
    }

    @Transactional
    Long persistTrade(ScalpTrade t) {
        ScalpingTrade e = new ScalpingTrade();
        e.direction  = t.direction;
        e.entryPrice = t.entryPrice;
        e.tpPrice    = t.tpPrice;
        e.tp2Price   = t.tp2Price;
        e.slPrice    = t.slPrice;
        e.confidence = t.confidence;
        e.openedAt   = t.openedAt;
        e.status     = t.status;
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
        e.closedAt  = t.closedAt;
        e.status    = t.status;
    }

    private void clearActive() {
        activeDir        = null;
        activeEntryPrice = 0;
        activeSlPrice    = 0;
        activeTpPrice    = 0;
        activeTp1Price   = 0;
        activeTp2Price   = 0;
        activeTp1Hit     = false;
        activeTp1Pnl     = 0;
        activeQty        = 0;
        activeQty40      = 0;
        activeConf       = 0;
        activeOpenedAt   = null;
    }

    /**
     * Infers which order triggered the close based on exit price vs known TP/SL levels.
     * Used when Binance closes the position asynchronously before the Java monitor cycle sees it.
     * 0.05% tolerance accounts for slippage and mark-price vs last-price differences.
     */
    private String inferCloseReason(double price) {
        boolean isLong = "LONG".equals(activeDir);
        double tp1 = activeTp1Price > 0 ? activeTp1Price : activeTpPrice;
        double tp2 = activeTp2Price;
        double sl  = activeSlPrice;
        double tol = price * 0.0005;

        if (activeTp1Hit) {
            // TP1 already done — remaining position closed by TP2 or SL
            if (tp2 > 0 && (isLong ? price >= tp2 - tol : price <= tp2 + tol)) return "TP2";
            if (sl  > 0 && (isLong ? price <= sl  + tol : price >= sl  - tol)) return "SL";
        } else {
            if (tp1 > 0 && (isLong ? price >= tp1 - tol : price <= tp1 + tol)) return "TP";
            if (sl  > 0 && (isLong ? price <= sl  + tol : price >= sl  - tol)) return "SL";
        }
        return "EXT_CLOSE";
    }

    /** Called when Binance no longer has the position (SL algo fired, liquidation, external close). */
    private ScalpResult reconcileClosedPosition(double price) {
        String reason = inferCloseReason(price);
        LOG.infof("[Scalping] ⚡ Position %s @ %.1f fermée côté Binance → %s",
            activeDir, activeEntryPrice, reason);
        // Include PnL from TP1 partial close if it already happened before SL
        double pnl = ("LONG".equals(activeDir)
            ? (price - activeEntryPrice) * activeQty
            : (activeEntryPrice - price) * activeQty)
            + activeTp1Pnl;
        telegramService.sendCloseAlert(activeDir, reason + " scalping", price, pnl);
        for (ScalpTrade t : tradeHistory) {
            if ("OPEN".equals(t.status) && t.direction.equals(activeDir)) {
                t.exitPrice = price;
                t.pnl       = pnl;
                t.closedAt  = Instant.now();
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
                    String.format("PAUSE %d SL consécutifs", consecutiveLosses), price, pnl);
            }
        } else {
            consecutiveLosses = 0;
            lossStreakCoolUntil = null;
        }
        clearActive();
        return ScalpResult.closed(reason, price, pnl);
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
        public String  status;   // "OPEN" | "TP" | "TP2" | "SL" | "MANUAL"
        public Instant openedAt;
        public Instant closedAt;
    }
}
