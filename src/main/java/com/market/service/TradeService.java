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
 * Manages Bitcoin simulation trades with server-side persistence.
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
                           double entryPrice, double feeRate, double atr) {

        double sign = direction == Trade.Direction.LONG ? 1 : -1;
        double liqFactor = direction == Trade.Direction.LONG
                ? (1.0 - 1.0 / leverage)
                : (1.0 + 1.0 / leverage);

        Trade trade = new Trade();
        trade.direction  = direction;
        trade.amount     = amount;
        trade.leverage   = leverage;
        trade.entryPrice = entryPrice;
        trade.feeRate    = feeRate;
        trade.tp1        = entryPrice + sign * 1 * atr;
        trade.tp2        = entryPrice + sign * 2 * atr;
        trade.tp3        = entryPrice + sign * 3 * atr;
        trade.sl         = entryPrice - sign * 1 * atr;
        trade.liq        = entryPrice * liqFactor;
        trade.openedAt   = Instant.now();
        trade.status     = Trade.Status.OPEN;
        trade.currentPrice = entryPrice;
        trade.persist();
        LOG.infof("Trade opened: %s ×%d at %.2f (id=%d)", direction, leverage, entryPrice, trade.id);
        return trade;
    }

    // ─── Close ─────────────────────────────────────────────────────────────────

    @Transactional
    public Trade closeTrade(Long id, String reason) {
        Trade trade = Trade.findById(id);
        if (trade == null || trade.status == Trade.Status.CLOSED) return trade;
        trade.status      = Trade.Status.CLOSED;
        trade.closedAt    = Instant.now();
        trade.closeReason = reason;
        LOG.infof("Trade closed: id=%d reason=%s finalPnlNet=%.2f", id, reason, trade.pnlNet);
        return trade;
    }

    // ─── Active list ───────────────────────────────────────────────────────────

    public List<Trade> getActiveTrades() {
        return Trade.findActive();
    }

    public List<Trade> getAllTrades() {
        return Trade.findAll(100);
    }

    public Trade getById(Long id) {
        return Trade.findById(id);
    }

    // ─── Background update (called by scheduler every 15 s) ───────────────────

    @Transactional
    public void updateAllTrades() {
        List<Trade> open = Trade.findActive();
        if (open.isEmpty()) return;

        double currentPrice;
        try {
            BitcoinSignal sig = cryptoService.getSignal();
            currentPrice = sig.currentPrice;
        } catch (Exception e) {
            LOG.warn("Cannot fetch BTC price for trade update: " + e.getMessage());
            return;
        }

        for (Trade t : open) {
            updateTrade(t, currentPrice);
        }
        LOG.debugf("Updated %d open trade(s) — BTC=%.2f", (Object) open.size(), currentPrice);
    }

    // ─── Private helpers ───────────────────────────────────────────────────────

    private void updateTrade(Trade t, double current) {
        t.currentPrice = current;

        double btcMove = t.direction == Trade.Direction.LONG
                ? current - t.entryPrice
                : t.entryPrice - current;

        t.pnlUsd   = t.amount * (btcMove / t.entryPrice) * t.leverage;
        t.pnlPct   = t.pnlUsd / t.amount * 100.0;
        t.feesTotal = t.amount * t.leverage * t.feeRate * 2;
        t.pnlNet   = t.pnlUsd - t.feesTotal;

        // Auto-close on liquidation
        boolean liquidated = t.direction == Trade.Direction.LONG
                ? current <= t.liq
                : current >= t.liq;
        if (liquidated) {
            t.status      = Trade.Status.CLOSED;
            t.closedAt    = Instant.now();
            t.closeReason = "LIQUIDATION";
            LOG.warnf("Trade %d liquidated at %.2f", t.id, current);
        }
    }
}
