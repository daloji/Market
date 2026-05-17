package com.market.service;

import com.market.model.BitcoinSignal;
import com.market.model.Trade;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;

/**
 * Manages Bitcoin trades (simulation and real) with server-side persistence.
 * Trades survive page reloads and are updated every 15 s by the scheduler.
 */
@ApplicationScoped
public class TradeService {

    private static final Logger LOG = Logger.getLogger(TradeService.class);

    @Inject
    CryptoAnalysisService cryptoService;

    // ─── Open ──────────────────────────────────────────────────────────────────

    @Transactional
    public Trade openTrade(double amount, Trade.Direction direction, int leverage,
                           double entryPrice, double feeRate, double atr,
                           double customSl, double customTp,
                           String tradeType, String broker, String symbol, String note) {
        return openTrade(amount, 0, direction, leverage, entryPrice, feeRate, atr,
                customSl, customTp, tradeType, broker, symbol, note);
    }

    @Transactional
    public Trade openTrade(double amount, double quantity, Trade.Direction direction, int leverage,
                           double entryPrice, double feeRate, double atr,
                           double customSl, double customTp,
                           String tradeType, String broker, String symbol, String note) {

        double sign = direction == Trade.Direction.LONG ? 1 : -1;
        double liqFactor = direction == Trade.Direction.LONG
                ? (1.0 - 1.0 / leverage)
                : (1.0 + 1.0 / leverage);

        Trade trade = new Trade();
        trade.direction  = direction;
        trade.amount     = amount;
        trade.quantity   = quantity;
        trade.leverage   = leverage;
        trade.entryPrice = entryPrice;
        trade.feeRate    = feeRate;
        trade.sl  = customSl > 0 ? customSl : entryPrice - sign * 1.5 * atr;
        trade.tp1 = customTp > 0 ? customTp : entryPrice + sign * 1.5 * atr;
        trade.tp2 = entryPrice + sign * 3.0 * atr;
        trade.tp3 = entryPrice + sign * 4.5 * atr;
        trade.liq = entryPrice * liqFactor;
        trade.openedAt   = Instant.now();
        trade.status     = Trade.Status.OPEN;
        trade.currentPrice = entryPrice;
        trade.tradeType  = (tradeType != null && !tradeType.isBlank()) ? tradeType : "SIMULATION";
        trade.broker     = broker;
        trade.symbol     = (symbol != null && !symbol.isBlank()) ? symbol : "BTC/USDT";
        trade.note       = note;
        trade.persist();
        LOG.infof("[%s] Trade opened: %s ×%d at %.2f SL=%.2f TP=%.2f qty=%.4f (id=%d)",
                trade.tradeType, direction, leverage, entryPrice, trade.sl, trade.tp1, quantity, trade.id);
        return trade;
    }

    // ─── Close ─────────────────────────────────────────────────────────────────

    /**
     * Close a trade. For REAL trades, optionally use a custom exit price to
     * recalculate the final P&L before closing.
     */
    @Transactional
    public Trade closeTrade(Long id, String reason, double closePrice) {
        Trade trade = Trade.findById(id);
        if (trade == null || trade.status == Trade.Status.CLOSED) return trade;

        // For real trades: recalculate P&L using actual exit price if provided
        if ("REAL".equals(trade.tradeType) && closePrice > 0) {
            computePnl(trade, closePrice);
        }

        trade.status      = Trade.Status.CLOSED;
        trade.closedAt    = Instant.now();
        trade.closeReason = reason;
        LOG.infof("[%s] Trade closed: id=%d reason=%s finalPnlNet=%.2f",
                trade.tradeType, id, reason, trade.pnlNet);
        return trade;
    }

    // ─── Queries ───────────────────────────────────────────────────────────────

    public List<Trade> getActiveTrades()    { return Trade.findActive(); }
    public List<Trade> getActiveReal()      { return Trade.findActiveReal(); }
    public List<Trade> getClosedTrades()    { return Trade.findClosed(500); }
    public List<Trade> getClosedReal()      { return Trade.findClosedReal(500); }
    public List<Trade> getAllClosed()       { return Trade.findAllClosed(1000); }
    public Trade       getById(Long id)     { return Trade.findById(id); }

    // ─── Background update (called by scheduler every 15 s) ───────────────────

    @Transactional
    public void updateAllTrades() {
        List<Trade> open = Trade.findAllOpen();
        if (open.isEmpty()) return;

        double currentPrice;
        try {
            BitcoinSignal sig = cryptoService.getSignal();
            currentPrice = sig.currentPrice;
        } catch (Exception e) {
            LOG.warn("Cannot fetch BTC price for trade update: " + e.getMessage());
            return;
        }

        if (currentPrice <= 0) {
            LOG.warn("Skipping trade update — invalid BTC price: " + currentPrice);
            return;
        }

        updateTradesAtPrice(open, currentPrice);
        LOG.debugf("Updated %d open trade(s) — BTC=%.2f", (Object) open.size(), currentPrice);
    }

    /** Package-private: update a batch of trades at a given price (also used by tests). */
    void updateTradesAtPrice(List<Trade> trades, double price) {
        for (Trade t : trades) updateTrade(t, price);
    }

    // ─── Private helpers ───────────────────────────────────────────────────────

    private void computePnl(Trade t, double current) {
        double btcMove = t.direction == Trade.Direction.LONG
                ? current - t.entryPrice
                : t.entryPrice - current;
        t.currentPrice = current;
        t.pnlUsd    = t.amount * (btcMove / t.entryPrice) * t.leverage;
        t.pnlPct    = t.pnlUsd / t.amount * 100.0;
        t.feesTotal = t.amount * t.leverage * t.feeRate * 2;
        t.pnlNet    = t.pnlUsd - t.feesTotal;
    }

    private void updateTrade(Trade t, double current) {
        computePnl(t, current);

        // Real trades: no auto-close; user closes manually in their broker
        if ("REAL".equals(t.tradeType)) return;

        // ── Simulation auto-close logic ────────────────────────────────────────
        if (t.tp1 > 0) {
            boolean tpHit = t.direction == Trade.Direction.LONG
                    ? current >= t.tp1 : current <= t.tp1;
            if (tpHit) {
                computePnl(t, t.tp1); // P&L at exact TP price (no slippage in simulation)
                t.status      = Trade.Status.CLOSED;
                t.closedAt    = Instant.now();
                t.closeReason = "TP_HIT";
                LOG.infof("Trade %d TP hit at %.2f (tp=%.2f) pnlNet=%.2f",
                        t.id, current, t.tp1, t.pnlNet);
                return;
            }
        }
        if (t.sl > 0) {
            boolean slHit = t.direction == Trade.Direction.LONG
                    ? current <= t.sl : current >= t.sl;
            if (slHit) {
                computePnl(t, t.sl); // P&L at exact SL price
                t.status      = Trade.Status.CLOSED;
                t.closedAt    = Instant.now();
                t.closeReason = "SL_HIT";
                LOG.warnf("Trade %d SL hit at %.2f (sl=%.2f) pnlNet=%.2f",
                        t.id, current, t.sl, t.pnlNet);
                return;
            }
        }
        boolean liquidated = t.direction == Trade.Direction.LONG
                ? current <= t.liq : current >= t.liq;
        if (liquidated) {
            computePnl(t, t.liq); // P&L at exact liquidation price
            t.status      = Trade.Status.CLOSED;
            t.closedAt    = Instant.now();
            t.closeReason = "LIQUIDATION";
            LOG.warnf("Trade %d liquidated at %.2f pnlNet=%.2f", t.id, current, t.pnlNet);
        }
    }

}
