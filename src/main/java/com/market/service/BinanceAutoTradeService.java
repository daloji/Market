package com.market.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.market.model.BitcoinSignal;
import com.market.model.Trade;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
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

    /** Minimum interval between two consecutive auto-trades (30 min after position close). */
    private static final long COOLDOWN_MS = 30L * 60 * 1000;

    /** Number of consecutive confirmed cycles required before placing a trade (anti-faux-retournement). */
    private static final int REQUIRED_CONFIRMATIONS = 2;

    @Inject CryptoAnalysisService analysisService;
    @Inject BinanceFuturesService  futuresService;
    @Inject TradeService           tradeService;
    @Inject SignalHistoryService   signalHistoryService;
    @Inject TelegramAlertService   telegramAlertService;

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

    // Signal confirmation tracking (anti-faux-retournement)
    private volatile int    consecutiveSameDir = 0;
    private volatile String lastSignalDir      = null;

    // Active position SL/TP monitoring (instead of Binance conditional orders)
    private volatile String  activeDir;       // "LONG" or "SHORT", null = no active tracking
    private volatile double  activeSlPrice;   // SL trigger price
    private volatile double  activeTpPrice;   // TP trigger price
    private volatile double  activeQty;       // BTC qty to close

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
     * Main entry point — called by the scheduler every 5 seconds.
     * Priority 1: monitor SL/TP on active position.
     * Priority 2: evaluate signal and open a new trade.
     */
    public AutoTradeResult checkAndTrade() {
        if (!enabled.get())
            return store(AutoTradeResult.skipped("Auto-trade désactivé"));
        if (!futuresService.isConfigured())
            return store(AutoTradeResult.skipped("Clé API Binance Futures non configurée"));

        // Fetch current BTC signal (gives us current price + direction)
        BitcoinSignal signal;
        try {
            signal = analysisService.getSignal();
        } catch (Exception e) {
            return store(AutoTradeResult.error("Erreur signal: " + e.getMessage()));
        }

        if (signal.error != null)
            return store(AutoTradeResult.error("Signal BTC error: " + signal.error));

        // ── Priority 1: SL/TP monitoring on active position ───────────────────
        if (activeDir != null) {
            AutoTradeResult slTpResult = checkSlTp(signal.currentPrice);
            if (slTpResult != null) return store(slTpResult);
        }

        // ── Priority 2: new trade opportunity ────────────────────────────────
        String dir  = signal.direction;
        int    conf = signal.confidence;
        int    mc   = getMinConfidence();

        // Direction filter
        if (!"LONG".equals(dir) && !"SHORT".equals(dir))
            return store(AutoTradeResult.skipped(
                "Signal WAIT (confiance=" + conf + "%) — le signal doit atteindre ≥" + mc + "% pour LONG ou ≤" + (100 - mc) + "% pour SHORT, ET passer le filtre EMA de tendance"));

        // Confidence filter — symmetric + adaptive ADX boost:
        //   LONG  needs conf >= mc        (e.g. conf >= 60)
        //   SHORT needs conf <= (100-mc)  (e.g. conf <= 40)
        // When ADX < 25 (weak/ranging market), require +5 pts extra conviction.
        int effectiveMc = mc;
        if (signal.adx > 0 && signal.adx < 25) {
            effectiveMc = mc + 5; // anti-whipsaw: tighter threshold in low-trend environments
        }
        boolean confOk = "LONG".equals(dir) ? conf >= effectiveMc : conf <= (100 - effectiveMc);
        if (!confOk) {
            if ("LONG".equals(dir))
                return store(AutoTradeResult.skipped(
                    "LONG confiance " + conf + "% < seuil " + effectiveMc + "%" +
                    (effectiveMc > mc ? " (ADX=" + String.format(Locale.US, "%.1f", signal.adx) + " <25, +5 pts anti-whipsaw)" : "")));
            else
                return store(AutoTradeResult.skipped(
                    "SHORT confiance " + conf + "% > seuil " + (100 - effectiveMc) + "%" +
                    (effectiveMc > mc ? " (ADX=" + String.format(Locale.US, "%.1f", signal.adx) + " <25, +5 pts anti-whipsaw)" : "")));
        }

        // ── Hard filter 1: Market structure ───────────────────────────────────
        // Block trades during consolidation — high whipsaw risk in range.
        if ("CONSOLIDATION".equals(signal.marketStructure)) {
            AutoTradeResult r = AutoTradeResult.skipped(
                "Consolidation détectée — structure indéfinie, trade bloqué pour éviter whipsaw");
            signalHistoryService.record(signal, false, r.message);
            return store(r);
        }

        // ── Hard filter 2: 4h trend alignment ────────────────────────────────
        // Only block when 4h conviction is STRONG (|tf4hScore| >= 15 = diff > 0.3%).
        // Weak 4h bias (score ±8) means EMA barely diverged — not reliable enough to block.
        if (signal.tf4hBias != null && Math.abs(signal.tf4hScore) >= 15) {
            if ("LONG".equals(dir) && "BEAR".equals(signal.tf4hBias)) {
                AutoTradeResult r = AutoTradeResult.skipped(
                    "LONG bloqué — tendance 4h BEAR (EMA9=" +
                    String.format(Locale.US, "%.0f", signal.tf4hEma9) + " < EMA21=" +
                    String.format(Locale.US, "%.0f", signal.tf4hEma21) + ")");
                signalHistoryService.record(signal, false, r.message);
                return store(r);
            }
            if ("SHORT".equals(dir) && "BULL".equals(signal.tf4hBias)) {
                AutoTradeResult r = AutoTradeResult.skipped(
                    "SHORT bloqué — tendance 4h BULL (EMA9=" +
                    String.format(Locale.US, "%.0f", signal.tf4hEma9) + " > EMA21=" +
                    String.format(Locale.US, "%.0f", signal.tf4hEma21) + ")");
                signalHistoryService.record(signal, false, r.message);
                return store(r);
            }
        }

        // ── Hard filter 3: 5m momentum confirmation ───────────────────────────
        // Require 5m to at least not strongly oppose the direction.
        if (signal.tf5mMomentum != null) {
            if ("LONG".equals(dir) && "DOWN".equals(signal.tf5mMomentum)) {
                AutoTradeResult r = AutoTradeResult.skipped(
                    "LONG bloqué — 5m momentum DOWN (MACD hist=" +
                    String.format(Locale.US, "%.2f", signal.tf5mMacdHist) + ") — attendre retournement 5m");
                signalHistoryService.record(signal, false, r.message);
                return store(r);
            }
            if ("SHORT".equals(dir) && "UP".equals(signal.tf5mMomentum)) {
                AutoTradeResult r = AutoTradeResult.skipped(
                    "SHORT bloqué — 5m momentum UP (MACD hist=" +
                    String.format(Locale.US, "%.2f", signal.tf5mMacdHist) + ") — attendre retournement 5m");
                signalHistoryService.record(signal, false, r.message);
                return store(r);
            }
        }

        // ── Hard filter 4: Funding rate extrême ───────────────────────────────
        // Si le funding est extrêmement positif, trop de longs → risque de squeeze SHORT.
        // Si le funding est extrêmement négatif, trop de shorts → risque de squeeze LONG.
        if ("EXTREME_LONG".equals(signal.fundingBias) && "LONG".equals(dir)) {
            AutoTradeResult r = AutoTradeResult.skipped(
                String.format(Locale.US,
                    "LONG bloqué — funding rate extrême +%.4f%% (marché sur-acheté en levier, risque squeeze)",
                    signal.fundingRate * 100));
            signalHistoryService.record(signal, false, r.message);
            return store(r);
        }
        if ("EXTREME_SHORT".equals(signal.fundingBias) && "SHORT".equals(dir)) {
            AutoTradeResult r = AutoTradeResult.skipped(
                String.format(Locale.US,
                    "SHORT bloqué — funding rate extrême %.4f%% (marché sur-vendu en levier, risque long squeeze)",
                    signal.fundingRate * 100));
            signalHistoryService.record(signal, false, r.message);
            return store(r);
        }

        // ── Hard filter 5: Volatilité extrême ────────────────────────────────
        // ATR > 3% du prix = flash crash / news event / explosion — SL/TP intenables.
        if ("EXTREME".equals(signal.volatilityRegime)) {
            AutoTradeResult r = AutoTradeResult.skipped(
                String.format(Locale.US,
                    "Trade bloqué — volatilité EXTREME (ATR=%.2f%% du prix) — news/flash event probable",
                    signal.atrPct));
            signalHistoryService.record(signal, false, r.message);
            return store(r);
        }

        // Bougie extrême (range > 3×ATR) = event violent en cours — attendre stabilisation
        if (signal.extremeCandle) {
            AutoTradeResult r = AutoTradeResult.skipped(
                "Trade bloqué — bougie extrême détectée (range > 3×ATR) — event violent, attendre stabilisation");
            signalHistoryService.record(signal, false, r.message);
            return store(r);
        }

        // BB Squeeze → direction inconnue avant le breakout — ne pas entrer
        if ("SQUEEZE".equals(signal.bbState)) {
            AutoTradeResult r = AutoTradeResult.skipped(
                String.format(Locale.US,
                    "Trade bloqué — Bollinger Squeeze (BB width=%.1f%%) — breakout imminent, direction inconnue",
                    signal.bbWidth));
            signalHistoryService.record(signal, false, r.message);
            return store(r);
        }

        // Cooldown filter
        if (lastTradeAt != null) {
            long elapsed = Instant.now().toEpochMilli() - lastTradeAt.toEpochMilli();
            if (elapsed < COOLDOWN_MS) {
                long remain = (COOLDOWN_MS - elapsed) / 60_000;
                AutoTradeResult r = AutoTradeResult.skipped(
                    "Cooldown actif — prochain trade possible dans " + remain + " min (4h entre chaque trade)");
                signalHistoryService.record(signal, false, r.message);
                return store(r);
            }
        }

        // ── Filtre anti-empilement même direction ─────────────────────────────
        // Multi-positions = LONG + SHORT peuvent coexister, mais jamais 2 positions
        // dans la même direction (empêche l'overtrading directionnel).
        if (activeDir != null && activeDir.equals(dir)) {
            AutoTradeResult r = AutoTradeResult.skipped(
                "Position " + activeDir + " déjà ouverte — attendre fermeture avant d'ouvrir une nouvelle position dans la même direction");
            signalHistoryService.record(signal, false, r.message);
            return store(r);
        }

        // Positions Binance — synchronise le suivi local si une position orpheline est détectée
        // (pas de activeDir mais une position existe) pour que le monitoring SL/TP interne
        // couvre la dernière position.
        try {
            if (hasOpenPosition() && activeDir == null) {
                // Orphan position: init local SL/TP tracking from configured percentages
                double entry = signal.currentPrice;
                double slP   = getSlPct();
                double tpP   = getTpPct();
                activeDir     = "LONG".equals(dir) ? "LONG" : "SHORT";
                activeSlPrice = "LONG".equals(activeDir)
                        ? r1(entry * (1 - slP / 100))
                        : r1(entry * (1 + slP / 100));
                activeTpPrice = "LONG".equals(activeDir)
                        ? r1(entry * (1 + tpP / 100))
                        : r1(entry * (1 - tpP / 100));
                try {
                    String prJson = futuresService.getPositionRisk("BTCUSDT");
                    com.fasterxml.jackson.databind.JsonNode prArr = mapper.readTree(prJson);
                    if (prArr.isArray()) {
                        for (com.fasterxml.jackson.databind.JsonNode n : prArr) {
                            double a = Math.abs(n.path("positionAmt").asDouble(0));
                            if (a > 0.0001) { activeQty = a; break; }
                        }
                    }
                } catch (Exception ignored) {}
                LOG.infof("[AutoTrade] Position orpheline détectée — suivi SL/TP activé (%s SL=%.1f TP=%.1f)",
                        activeDir, activeSlPrice, activeTpPrice);
            }
        } catch (Exception e) {
            LOG.warnf("[AutoTrade] Impossible de vérifier la position: %s — on continue", e.getMessage());
        }

        // ── Signal confirmation (anti-faux-retournement) ──────────────────────
        // Require 2 consecutive cycles with same direction AND all filters passing.
        // Prevents single-candle fakeouts from triggering a trade.
        if (dir.equals(lastSignalDir)) {
            consecutiveSameDir++;
        } else {
            consecutiveSameDir = 1;
            lastSignalDir = dir;
        }
        if (consecutiveSameDir < REQUIRED_CONFIRMATIONS) {
            AutoTradeResult r = AutoTradeResult.skipped(
                dir + " confirmation " + consecutiveSameDir + "/" + REQUIRED_CONFIRMATIONS
                + " — attente " + REQUIRED_CONFIRMATIONS + " signaux consécutifs (anti-faux-retournement)");
            signalHistoryService.record(signal, false, r.message);
            return store(r);
        }

        AutoTradeResult result = execute(signal);
        signalHistoryService.record(signal, "placed".equals(result.status),
            "placed".equals(result.status) ? null : result.message);
        return store(result);
    }

    /**
     * Returns a full diagnostic of all conditions that must pass for a trade to be placed.
     * Useful to understand why no trade is being triggered.
     */
    public DiagResult diagnose() {
        DiagResult d = new DiagResult();
        d.enabled       = enabled.get();
        d.configured    = futuresService.isConfigured();
        d.minConfidence = getMinConfidence();
        d.effectiveMc   = d.minConfidence; // default, overridden below if ADX < 25
        d.slPct         = getSlPct();
        d.tpPct         = getTpPct();
        d.leverage      = getLeverage();
        d.amountUsdt    = getAmountUsdt();
        // Confirmation default (safe: don't block if signal not loaded yet)
        d.confirmCount    = consecutiveSameDir;
        d.confirmRequired = REQUIRED_CONFIRMATIONS;
        d.confirmOk       = consecutiveSameDir >= REQUIRED_CONFIRMATIONS;

        if (lastTradeAt != null) {
            long elapsed = Instant.now().toEpochMilli() - lastTradeAt.toEpochMilli();
            d.lastTradeMs        = lastTradeAt.toEpochMilli();
            d.cooldownRemainMin  = Math.max(0, (COOLDOWN_MS - elapsed) / 60_000);
            d.cooldownOk         = elapsed >= COOLDOWN_MS;
        } else {
            d.cooldownOk = true;
        }

        try {
            BitcoinSignal sig = analysisService.getSignal();
            if (sig.error != null) {
                d.signalError = sig.error;
            } else {
                d.signalDirection  = sig.direction;
                d.signalConfidence = sig.confidence;
                d.signalPrice      = sig.currentPrice;
                d.directionOk      = "LONG".equals(sig.direction) || "SHORT".equals(sig.direction);
                // LONG needs conf >= mc; SHORT needs conf <= (100-mc)
                d.confidenceOk = d.directionOk && (
                    "LONG".equals(sig.direction)  ? sig.confidence >= d.minConfidence :
                    "SHORT".equals(sig.direction) ? sig.confidence <= (100 - d.minConfidence) : false
                );

        // Hard filter states
                d.marketStructure   = sig.marketStructure;
                d.msOk              = !"CONSOLIDATION".equals(sig.marketStructure);

                d.tf4hBias          = sig.tf4hBias;
                d.tf4hScore         = sig.tf4hScore;
                d.tf4hOk = d.directionOk && (sig.tf4hBias == null
                    || Math.abs(sig.tf4hScore) < 15  // weak 4h bias → don't block
                    || !("LONG".equals(sig.direction) && "BEAR".equals(sig.tf4hBias))
                    && !("SHORT".equals(sig.direction) && "BULL".equals(sig.tf4hBias)));

                d.tf5mMomentum      = sig.tf5mMomentum;
                d.tf5mOk = d.directionOk && (sig.tf5mMomentum == null
                    || !("LONG".equals(sig.direction) && "DOWN".equals(sig.tf5mMomentum))
                    && !("SHORT".equals(sig.direction) && "UP".equals(sig.tf5mMomentum)));

                // Volatility filter states
                d.volatilityRegime = sig.volatilityRegime;
                d.extremeCandle    = sig.extremeCandle;
                d.bbState          = sig.bbState;
                d.atrPct           = sig.atrPct;
                d.volFilterOk      = !"EXTREME".equals(sig.volatilityRegime)
                        && !sig.extremeCandle
                        && !"SQUEEZE".equals(sig.bbState);

                // Adaptive confidence threshold
                d.adxValue          = sig.adx;
                d.adxBoostActive    = sig.adx > 0 && sig.adx < 25;
                d.effectiveMc       = d.adxBoostActive ? d.minConfidence + 5 : d.minConfidence;

                // Re-evaluate confidenceOk with effective threshold
                d.confidenceOk = d.directionOk && (
                    "LONG".equals(sig.direction)  ? sig.confidence >= d.effectiveMc :
                    "SHORT".equals(sig.direction) ? sig.confidence <= (100 - d.effectiveMc) : false
                );

                // Signal confirmation state — projects forward (simulates the next checkAndTrade cycle)
                // confirmOk = true if calling checkAndTrade() NOW would satisfy the confirmation requirement
                int projectedCount = sig.direction.equals(lastSignalDir)
                    ? consecutiveSameDir + 1  // same direction: would increment
                    : 1;                      // new direction: would reset to 1
                d.confirmCount    = consecutiveSameDir;
                d.confirmRequired = REQUIRED_CONFIRMATIONS;
                d.confirmOk       = projectedCount >= REQUIRED_CONFIRMATIONS;
            }
        } catch (Exception e) {
            d.signalError = e.getMessage();
        }

        try {
            d.hasOpenPosition = hasOpenPosition();
            d.positionOk = true; // multiple positions allowed — position check is informational only
        } catch (Exception e) {
            d.positionOk    = true;
            d.positionError = e.getMessage();
        }

        d.sameDirectionBlocked = activeDir != null && d.signalError == null
                && activeDir.equals(d.signalDirection);

        d.wouldTrade = d.enabled && d.configured && d.directionOk
                && d.confidenceOk && d.msOk && d.tf4hOk && d.tf5mOk
                && d.volFilterOk && d.cooldownOk && d.confirmOk
                && !d.sameDirectionBlocked;

        // Compute the first blocking reason
        if (!d.enabled)            d.blockingReason = "Auto-trade désactivé";
        else if (!d.configured)    d.blockingReason = "Clé API Binance Futures non configurée";
        else if (d.signalError != null) d.blockingReason = "Erreur signal: " + d.signalError;
        else if (!d.directionOk)   d.blockingReason = "Signal WAIT (direction=" + d.signalDirection + ", conf=" + d.signalConfidence + "%)";
        else if (!d.confidenceOk) {
            boolean isLong = "LONG".equals(d.signalDirection);
            d.blockingReason = isLong
                ? "LONG confiance " + d.signalConfidence + "% < seuil " + d.effectiveMc + "%" +
                  (d.adxBoostActive ? " (ADX=" + String.format(Locale.US, "%.1f", d.adxValue) + " <25, +5 anti-whipsaw)" : "")
                : "SHORT confiance " + d.signalConfidence + "% > seuil " + (100 - d.effectiveMc) + "%" +
                  (d.adxBoostActive ? " (ADX=" + String.format(Locale.US, "%.1f", d.adxValue) + " <25, +5 anti-whipsaw)" : "");
        }
        else if (!d.msOk)          d.blockingReason = "Consolidation — structure indéfinie, trade bloqué";
        else if (!d.tf4hOk)        d.blockingReason = "Tendance 4h forte oppose le signal (" + d.tf4hBias + ", score=" + d.tf4hScore + ") — ne pas trader contre la tendance dominante";
        else if (!d.tf5mOk)        d.blockingReason = "Momentum 5m oppose le signal (" + d.tf5mMomentum + ") — attendre retournement 5m";
        else if (!d.volFilterOk) {
            if ("EXTREME".equals(d.volatilityRegime))
                d.blockingReason = String.format(Locale.US, "Volatilité EXTREME (ATR=%.2f%%) — event violent, trade bloqué", d.atrPct);
            else if (d.extremeCandle)
                d.blockingReason = "Bougie extrême détectée (range > 3×ATR) — attendre stabilisation";
            else
                d.blockingReason = "Bollinger Squeeze — breakout imminent, direction inconnue";
        }
        else if (!d.cooldownOk)    d.blockingReason = "Cooldown actif — " + d.cooldownRemainMin + " min restantes (anti-overtrading, 30 min après fermeture)";
        else if (d.sameDirectionBlocked) d.blockingReason = "Position " + activeDir + " déjà ouverte — attendre fermeture avant nouvelle position " + activeDir;
        else if (!d.confirmOk)     d.blockingReason = d.signalDirection + " confirmation " + d.confirmCount + "/" + d.confirmRequired
                    + " — attente " + d.confirmRequired + " signaux consécutifs (anti-faux-retournement)";
        else                       d.blockingReason = null; // all clear

        return d;
    }

    public static class DiagResult {
        public boolean enabled;
        public boolean configured;
        public int     minConfidence;
        public double  slPct, tpPct;
        public int     leverage;
        public double  amountUsdt;
        // Signal
        public String  signalDirection;
        public int     signalConfidence;
        public double  signalPrice;
        public String  signalError;
        public boolean directionOk;
        public boolean confidenceOk;
        // Adaptive threshold (ADX anti-whipsaw)
        public double  adxValue;
        public boolean adxBoostActive;
        public int     effectiveMc;
        // Hard filters
        public String  marketStructure;
        public boolean msOk;
        public String  tf4hBias;
        public int     tf4hScore;
        public boolean tf4hOk;
        public String  tf5mMomentum;
        public boolean tf5mOk;
        // Volatility filter
        public String  volatilityRegime;
        public boolean extremeCandle;
        public String  bbState;
        public double  atrPct;
        public boolean volFilterOk;
        // Cooldown
        public boolean cooldownOk;
        public long    cooldownRemainMin;
        public long    lastTradeMs;
        // Position
        public boolean hasOpenPosition;
        public boolean positionOk;
        public String  positionError;
        // Signal confirmation (anti-faux-retournement)
        public int     confirmCount;
        public int     confirmRequired;
        public boolean confirmOk;
        // Anti-stacking (même direction)
        public boolean sameDirectionBlocked;
        // Summary
        public boolean wouldTrade;
        public String  blockingReason;
    }

    // ── Trade execution ────────────────────────────────────────────────────────

    /**
     * Monitors current price against active SL/TP levels.
     * If triggered, closes the position with a MARKET order (avoids Binance -4120 error).
     * Returns non-null only when a close action was taken or failed.
     */
    private AutoTradeResult checkSlTp(double price) {
        if (activeDir == null) return null;

        // If position was closed externally (manual close / liquidation), clear tracking
        try {
            if (!hasOpenPosition()) {
                LOG.info("[AutoTrade] Position fermée externement — suivi SL/TP réinitialisé");
                clearActiveState();
                return null;
            }
        } catch (Exception e) {
            LOG.warnf("[AutoTrade] Vérif position SL/TP: %s — on continue le suivi", e.getMessage());
        }

        boolean isLong = "LONG".equals(activeDir);
        boolean slHit  = isLong ? price <= activeSlPrice : price >= activeSlPrice;
        boolean tpHit  = isLong ? price >= activeTpPrice : price <= activeTpPrice;

        if (!slHit && !tpHit) return null;

        String reason    = tpHit ? "TP atteint" : "SL atteint";
        String closeSide = isLong ? "SELL" : "BUY";
        String qtyStr    = String.format(Locale.US, "%.3f", activeQty);

        try {
            futuresService.closeWithMarket("BTCUSDT", closeSide, qtyStr);
            String msg = reason + " @ " + String.format(Locale.US, "%.1f", price)
                    + " | SL=" + String.format(Locale.US, "%.1f", activeSlPrice)
                    + " TP=" + String.format(Locale.US, "%.1f", activeTpPrice);
            LOG.infof("[AutoTrade] 🔒 %s fermé: %s", activeDir, msg);

            // Telegram close notification
            double pnl = isLong
                ? (price - activeSlPrice) * activeQty  // rough estimate using entry~SL as ref
                : (activeSlPrice - price) * activeQty;
            // Use TP/SL price difference as P&L proxy
            double entryRef = isLong ? activeTpPrice / (1 + getTpPct() / 100) : activeTpPrice / (1 - getTpPct() / 100);
            pnl = isLong ? (price - entryRef) * activeQty : (entryRef - price) * activeQty;
            telegramAlertService.sendCloseAlert(activeDir, reason, price, pnl);

            clearActiveState();
            // Reset confirmation counter — require fresh signal confirmation after any close
            consecutiveSameDir = 0;
            lastSignalDir      = null;
            lastTradeAt = Instant.now(); // 30-min cooldown starts now
            return AutoTradeResult.closed(reason, msg);
        } catch (Exception e) {
            LOG.errorf("[AutoTrade] ❌ Erreur fermeture SL/TP: %s", e.getMessage());
            return AutoTradeResult.error("Erreur fermeture SL/TP: " + e.getMessage());
        }
    }

    private void clearActiveState() {
        activeDir      = null;
        activeSlPrice  = 0;
        activeTpPrice  = 0;
        activeQty      = 0;
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
        String qty    = String.format(Locale.US, "%.3f", Math.max(0.001, rawQty));

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
            String orderJson   = futuresService.placeMarketOrder(symbol, entrySide, qty);
            double filledPrice = parseAvgPrice(orderJson, entry);
            double filledQty   = parseExecutedQty(orderJson, Double.parseDouble(qty));
            log.append(dir).append(" ").append(qty).append(" BTC @ ")
               .append(String.format(Locale.US, "%.2f", filledPrice)).append(". ");

            // 4. Stop Loss sur Binance (best-effort, en cas d'erreur: suivi interne prend le relais)
            try {
                futuresService.placeCloseOrder(symbol, closeSide, "STOP_MARKET", sl, qty);
                log.append("SL Binance @ ").append(String.format(Locale.US, "%.1f", sl)).append(". ");
            } catch (Exception e) {
                LOG.warnf("[AutoTrade] SL Binance échoué (%s) — suivi interne activé", e.getMessage());
                log.append("SL interne @ ").append(String.format(Locale.US, "%.1f", sl)).append(". ");
            }

            // 5. Take Profit sur Binance (best-effort, en cas d'erreur: suivi interne prend le relais)
            try {
                futuresService.placeCloseOrder(symbol, closeSide, "TAKE_PROFIT_MARKET", tp1, qty);
                log.append("TP Binance @ ").append(String.format(Locale.US, "%.1f", tp1)).append(". ");
            } catch (Exception e) {
                LOG.warnf("[AutoTrade] TP Binance échoué (%s) — suivi interne activé", e.getMessage());
                log.append("TP interne @ ").append(String.format(Locale.US, "%.1f", tp1)).append(". ");
            }

            // 6. Active le suivi interne SL/TP (filet de sécurité même si les ordres Binance ont été placés)
            activeDir      = dir;
            activeSlPrice  = sl;
            activeTpPrice  = tp1;
            activeQty      = filledQty;

            // 5. Persist trade in local database
            Trade.Direction tradeDir = "LONG".equals(dir) ? Trade.Direction.LONG : Trade.Direction.SHORT;
            String reasoning = signal.reasoning;
            if (reasoning != null && reasoning.length() > 200) reasoning = reasoning.substring(0, 200);
            tradeService.openTrade(
                    amt_, tradeDir, lev_, filledPrice, 0.0004, atr,
                    sl, tp1, "REAL", "Binance Futures", "BTC/USDT",
                    "AutoTrade conf=" + conf + "% | " + reasoning);

            lastDirection = dir;
            lastTradeAt   = Instant.now();
            // Reset confirmation — require fresh signals after each trade
            consecutiveSameDir = 0;
            lastSignalDir      = null;

            LOG.infof("[AutoTrade] ✅ %s ×%d conf=%d%% qty=%s @ %.2f SL=%.1f TP=%.1f",
                    dir, lev_, conf, qty, filledPrice, sl, tp1);

            // Telegram notification
            telegramAlertService.sendTradeAlert(dir, conf, filledPrice, sl, tp1,
                    getSlPct(), getTpPct(), lev_, amt_);

            return AutoTradeResult.placed(dir, conf, filledPrice, sl, tp1,
                    getSlPct(), getTpPct(), lev_, amt_, log.toString());

        } catch (Exception e) {
            LOG.errorf("[AutoTrade] ❌ Échec placement: %s", e.getMessage());
            return AutoTradeResult.error("Échec placement: " + e.getMessage());
        }
    }

    /**
     * Closes the full open position on a symbol with a MARKET order.
     * Also cancels all open SL/TP orders and clears internal tracking state.
     */
    public Map<String, Object> closePosition(String symbol) throws Exception {
        String json   = futuresService.getPositionRisk(symbol);
        JsonNode arr  = mapper.readTree(json);
        double posAmt = 0;
        if (arr.isArray()) {
            for (JsonNode pos : arr) {
                double amt = pos.path("positionAmt").asDouble(0);
                if (Math.abs(amt) > 0.0001) { posAmt = amt; break; }
            }
        }
        if (Math.abs(posAmt) < 0.0001)
            throw new RuntimeException("Aucune position ouverte sur " + symbol);

        // Cancel SL/TP orders first (best-effort)
        try { futuresService.cancelAllOrders(symbol); }
        catch (Exception e) { LOG.warnf("[AutoTrade] cancelAll fermeture: %s", e.getMessage()); }

        String side = posAmt > 0 ? "SELL" : "BUY";
        String qty  = String.format(Locale.US, "%.3f", Math.abs(posAmt));
        String orderJson = futuresService.closeWithMarket(symbol, side, qty);
        double filledPrice = parseAvgPrice(orderJson, 0);

        clearActiveState();

        String dir = posAmt > 0 ? "LONG" : "SHORT";
        LOG.infof("[AutoTrade] 🔴 Position %s %s fermée manuellement @ %.2f", dir, qty, filledPrice);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("symbol",    symbol);
        result.put("direction", dir);
        result.put("quantity",  qty);
        result.put("price",     filledPrice);
        result.put("message",   "Position " + dir + " " + qty + " BTC fermée ✅");
        return result;
    }

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

    private double parseExecutedQty(String json, double fallback) {
        try {
            JsonNode n = mapper.readTree(json);
            double q = n.path("executedQty").asDouble(0);
            if (q > 0) return q;
            q = n.path("origQty").asDouble(0);
            if (q > 0) return q;
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

        static AutoTradeResult closed(String reason, String msg) {
            AutoTradeResult r = new AutoTradeResult();
            r.status = "closed"; r.direction = reason; r.message = msg;
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
