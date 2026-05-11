package com.market.service;

import com.market.model.Trade;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for TradeService, exercising the full Panache/H2 stack.
 * Each test cleans up its own trades in @AfterEach.
 */
@QuarkusTest
class TradeServiceTest {

    @Inject
    TradeService svc;

    private final List<Long> createdIds = new ArrayList<>();

    @AfterEach
    @Transactional
    void cleanup() {
        for (Long id : createdIds) {
            Trade t = Trade.findById(id);
            if (t != null) t.delete();
        }
        createdIds.clear();
    }

    // ─── openTrade ─────────────────────────────────────────────────────────────

    @Test
    void openTrade_simulation_defaults() {
        Trade t = svc.openTrade(100, Trade.Direction.LONG, 10,
                50000, 0.001, 500, 0, 0,
                null, null, null, null);
        createdIds.add(t.id);

        assertNotNull(t.id);
        assertEquals("SIMULATION", t.tradeType);
        assertEquals("BTC/USDT", t.symbol);
        assertEquals(Trade.Status.OPEN, t.status);
        assertEquals(Trade.Direction.LONG, t.direction);
        assertEquals(10, t.leverage);
        assertEquals(50000, t.entryPrice, 0.01);
    }

    @Test
    void openTrade_real_withBrokerAndSymbol() {
        Trade t = svc.openTrade(200, Trade.Direction.SHORT, 5,
                48000, 0.0005, 400, 0, 0,
                "REAL", "Binance", "ETH/USDT", "test note");
        createdIds.add(t.id);

        assertEquals("REAL", t.tradeType);
        assertEquals("Binance", t.broker);
        assertEquals("ETH/USDT", t.symbol);
        assertEquals("test note", t.note);
        assertEquals(Trade.Direction.SHORT, t.direction);
    }

    @Test
    void openTrade_customSlTp_usedWhenProvided() {
        Trade t = svc.openTrade(100, Trade.Direction.LONG, 10,
                50000, 0.001, 500,
                49000, 55000,   // custom SL=49000, TP=55000
                null, null, null, null);
        createdIds.add(t.id);

        assertEquals(49000, t.sl, 0.01);
        assertEquals(55000, t.tp1, 0.01);
    }

    @Test
    void openTrade_noCustomSlTp_usesAtr() {
        double entry = 50000, atr = 500;
        Trade t = svc.openTrade(100, Trade.Direction.LONG, 10,
                entry, 0.001, atr, 0, 0,
                null, null, null, null);
        createdIds.add(t.id);

        assertEquals(entry - atr, t.sl, 0.01);   // SL = entry - ATR
        assertEquals(entry + atr, t.tp1, 0.01);  // TP1 = entry + ATR
    }

    @Test
    void openTrade_short_slAboveEntry_tpBelowEntry() {
        double entry = 50000, atr = 500;
        Trade t = svc.openTrade(100, Trade.Direction.SHORT, 10,
                entry, 0.001, atr, 0, 0,
                null, null, null, null);
        createdIds.add(t.id);

        assertTrue(t.sl > entry, "SHORT SL should be above entry price");
        assertTrue(t.tp1 < entry, "SHORT TP1 should be below entry price");
    }

    // ─── closeTrade ────────────────────────────────────────────────────────────

    @Test
    void closeTrade_simulation_basicClose() {
        Trade t = svc.openTrade(100, Trade.Direction.LONG, 10,
                50000, 0.001, 500, 0, 0,
                null, null, null, null);
        createdIds.add(t.id);

        Trade closed = svc.closeTrade(t.id, "USER_CLOSED", 0);
        assertEquals(Trade.Status.CLOSED, closed.status);
        assertEquals("USER_CLOSED", closed.closeReason);
        assertNotNull(closed.closedAt);
    }

    @Test
    void closeTrade_real_withClosePrice_recalculatesPnl() {
        Trade t = svc.openTrade(100, Trade.Direction.LONG, 10,
                50000, 0.001, 500, 0, 0,
                "REAL", "Bybit", "BTC/USDT", null);
        createdIds.add(t.id);

        Trade closed = svc.closeTrade(t.id, "USER_CLOSED", 51000);
        assertEquals(Trade.Status.CLOSED, closed.status);
        // LONG: move = 51000-50000=1000, pnlUsd = 100*(1000/50000)*10 = 20
        assertEquals(20.0, closed.pnlUsd, 0.01);
    }

    @Test
    void closeTrade_alreadyClosed_noError() {
        Trade t = svc.openTrade(100, Trade.Direction.LONG, 5,
                50000, 0.001, 500, 0, 0,
                null, null, null, null);
        createdIds.add(t.id);

        svc.closeTrade(t.id, "USER_CLOSED", 0);
        Trade again = svc.closeTrade(t.id, "USER_CLOSED", 0);  // second close should be safe
        assertEquals(Trade.Status.CLOSED, again.status);
    }

    @Test
    void closeTrade_nonExistentId_returnsNull() {
        Trade result = svc.closeTrade(-9999L, "USER_CLOSED", 0);
        assertNull(result);
    }

    // ─── P&L computation ───────────────────────────────────────────────────────

    @Test
    void pnl_long_profit() {
        // LONG 100$ ×10 at 50000, close at 51000 → move=1000/50000=2%, pnl=100*10*2%=20$
        Trade t = svc.openTrade(100, Trade.Direction.LONG, 10,
                50000, 0.001, 0, 0, 0,
                "REAL", null, null, null);
        createdIds.add(t.id);

        Trade closed = svc.closeTrade(t.id, "USER_CLOSED", 51000);
        assertEquals(20.0, closed.pnlUsd, 0.01);
        assertTrue(closed.pnlNet > 0, "Net P&L should be positive after profitable LONG");
    }

    @Test
    void pnl_long_loss() {
        // LONG 100$ ×10 at 50000, close at 49000 → move=-1000/50000=-2%, pnl=100*10*(-2%)=-20$
        Trade t = svc.openTrade(100, Trade.Direction.LONG, 10,
                50000, 0.001, 0, 0, 0,
                "REAL", null, null, null);
        createdIds.add(t.id);

        Trade closed = svc.closeTrade(t.id, "USER_CLOSED", 49000);
        assertEquals(-20.0, closed.pnlUsd, 0.01);
        assertTrue(closed.pnlNet < 0, "Net P&L should be negative after losing LONG");
    }

    @Test
    void pnl_short_profit() {
        // SHORT 100$ ×10 at 50000, close at 49000 → move=50000-49000=1000, pnl=+20$
        Trade t = svc.openTrade(100, Trade.Direction.SHORT, 10,
                50000, 0.001, 0, 0, 0,
                "REAL", null, null, null);
        createdIds.add(t.id);

        Trade closed = svc.closeTrade(t.id, "USER_CLOSED", 49000);
        assertEquals(20.0, closed.pnlUsd, 0.01);
    }

    @Test
    void pnl_short_loss() {
        // SHORT 100$ ×10 at 50000, close at 51000 → move=50000-51000=-1000, pnl=-20$
        Trade t = svc.openTrade(100, Trade.Direction.SHORT, 10,
                50000, 0.001, 0, 0, 0,
                "REAL", null, null, null);
        createdIds.add(t.id);

        Trade closed = svc.closeTrade(t.id, "USER_CLOSED", 51000);
        assertEquals(-20.0, closed.pnlUsd, 0.01);
    }

    @Test
    void pnl_fees_deductedFromNet() {
        // feeRate=0.001, amount=100, leverage=10 → feesTotal = 100*10*0.001*2 = 2$
        Trade t = svc.openTrade(100, Trade.Direction.LONG, 10,
                50000, 0.001, 0, 0, 0,
                "REAL", null, null, null);
        createdIds.add(t.id);

        Trade closed = svc.closeTrade(t.id, "USER_CLOSED", 51000);
        assertEquals(2.0, closed.feesTotal, 0.01);
        assertEquals(closed.pnlUsd - closed.feesTotal, closed.pnlNet, 0.01);
    }

    // ─── Query methods ─────────────────────────────────────────────────────────

    @Test
    void getActiveTrades_returnsOnlySimulation() {
        Trade sim  = svc.openTrade(100, Trade.Direction.LONG, 5, 50000, 0.001, 0, 0, 0,
                "SIMULATION", null, null, null);
        Trade real = svc.openTrade(100, Trade.Direction.LONG, 5, 50000, 0.001, 0, 0, 0,
                "REAL", "Binance", "BTC/USDT", null);
        createdIds.add(sim.id);
        createdIds.add(real.id);

        List<Trade> active = svc.getActiveTrades();
        assertTrue(active.stream().allMatch(t -> "SIMULATION".equals(t.tradeType)),
                "getActiveTrades should return only SIMULATION trades");
    }

    @Test
    void getActiveReal_returnsOnlyReal() {
        Trade sim  = svc.openTrade(100, Trade.Direction.LONG, 5, 50000, 0.001, 0, 0, 0,
                "SIMULATION", null, null, null);
        Trade real = svc.openTrade(100, Trade.Direction.LONG, 5, 50000, 0.001, 0, 0, 0,
                "REAL", "Bybit", "BTC/USDT", null);
        createdIds.add(sim.id);
        createdIds.add(real.id);

        List<Trade> active = svc.getActiveReal();
        assertTrue(active.stream().allMatch(t -> "REAL".equals(t.tradeType)),
                "getActiveReal should return only REAL trades");
    }

    @Test
    void getById_existingTrade_returnsTrade() {
        Trade t = svc.openTrade(100, Trade.Direction.LONG, 5, 50000, 0.001, 0, 0, 0,
                null, null, null, null);
        createdIds.add(t.id);

        Trade found = svc.getById(t.id);
        assertNotNull(found);
        assertEquals(t.id, found.id);
    }

    @Test
    void getById_nonExistent_returnsNull() {
        assertNull(svc.getById(-9999L));
    }
}
