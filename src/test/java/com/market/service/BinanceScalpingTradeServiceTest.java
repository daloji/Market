package com.market.service;

import com.market.model.ScalpingSignal;
import com.market.model.ScalpingTrade;
import com.market.model.ScalpingTradeLog;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit + integration tests for BinanceScalpingTradeService.
 *
 * BinanceFuturesService, ScalpingAnalysisService and TelegramAlertService are mocked
 * so no real HTTP calls are made.  State is fully reset in @BeforeEach via reflection.
 *
 * Key scenarios tested:
 *  - Gate conditions (not configured, disabled, WAIT signal, cooldown, existing position)
 *  - LONG trade → SL order is STOP_MARKET SELL, TP order is TAKE_PROFIT_MARKET SELL
 *  - SHORT trade → SL order is STOP_MARKET BUY,  TP order is TAKE_PROFIT_MARKET BUY
 *  - All orders use the new algo endpoint (verified through placeCloseOrder call)
 *  - SL placement failure is fatal — position is emergency-closed and error returned
 *  - cancelAllOrders is called before every market entry
 */
@QuarkusTest
class BinanceScalpingTradeServiceTest {

    @Inject
    BinanceScalpingTradeService svc;

    @InjectMock
    ScalpingAnalysisService scalpingService;

    @InjectMock
    BinanceFuturesService futuresService;

    @InjectMock
    TelegramAlertService telegramService;

    // ── Setup ─────────────────────────────────────────────────────────────────

    @BeforeEach
    void setup() throws Exception {
        svc.isEnabled(); // force CDI init
        BinanceScalpingTradeService real = realBean();

        // Disable so gate checks can be tested individually
        svc.disable();

        // Reset volatile state via reflection
        setField(real, "lastTradeAt",      null);
        setField(real, "activeDir",        null);
        setField(real, "activeEntryPrice", 0.0);
        setField(real, "activeSlPrice",    0.0);
        setField(real, "activeTpPrice",    0.0);
        setField(real, "activeTp1Price",   0.0);
        setField(real, "activeTp2Price",   0.0);
        setField(real, "activeTp1Hit",     false);
        setField(real, "activeTp1Pnl",    0.0);
        setField(real, "activeQty",        0.0);
        setField(real, "activeQty40",      0.0);
        setField(real, "consecutiveLosses", 0);
        setField(real, "lossStreakCoolUntil", null);

        // Predictable config
        svc.setMinConfidence(65);
        svc.setAmountUsdt(20.0);
        svc.setLeverage(10);
        svc.setTpPct(0.3);
        svc.setSlPct(0.15);

        // Default mocks — happy path
        when(futuresService.isConfigured()).thenReturn(true);
        when(futuresService.isHedgeMode()).thenReturn(false);
        when(futuresService.getPositionRisk(any())).thenReturn("[{\"positionAmt\":\"0\"}]");
        when(futuresService.setLeverage(any(), anyInt())).thenReturn("{}");
        when(futuresService.cancelAllOrders(any())).thenReturn("{}");
        when(futuresService.placeMarketOrder(any(), any(), any(), any()))
                .thenReturn("{\"avgPrice\":\"100000\",\"executedQty\":\"0.002\"}");
        when(futuresService.placeCloseOrder(any(), any(), any(), anyDouble(), any(), any()))
                .thenReturn("{\"algoId\":1234}");
        // getRecentUserTrades is called by reconcileClosedPosition — return empty to fall back to mark price
        try { when(futuresService.getRecentUserTrades(any(), anyInt())).thenReturn("[]"); }
        catch (Exception ignored) {}
        // getUserTradesFrom is called by reconcileTradeHistory — return empty by default
        try { when(futuresService.getUserTradesFrom(any(), anyLong(), anyInt())).thenReturn("[]"); }
        catch (Exception ignored) {}
        // getMarkPrice used for SL race-condition pre-check — default: mark == entry (no breach)
        try { when(futuresService.getMarkPrice(any())).thenReturn(100_000.0); }
        catch (Exception ignored) {}
        when(scalpingService.getSignal()).thenReturn(waitSignal());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private BinanceScalpingTradeService realBean() {
        if (svc instanceof ClientProxy) {
            return (BinanceScalpingTradeService) ((ClientProxy) svc).arc_contextualInstance();
        }
        return svc;
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field f = BinanceScalpingTradeService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private ScalpingSignal waitSignal() {
        ScalpingSignal s = new ScalpingSignal();
        s.direction    = "WAIT";
        s.confidence   = 50;
        s.currentPrice = 100_000.0;
        return s;
    }

    private ScalpingSignal longSignal(int conf) {
        ScalpingSignal s = new ScalpingSignal();
        s.direction    = "LONG";
        s.confidence   = conf;
        s.currentPrice = 100_000.0;
        return s;
    }

    private ScalpingSignal shortSignal(int conf) {
        ScalpingSignal s = new ScalpingSignal();
        s.direction    = "SHORT";
        s.confidence   = conf;
        s.currentPrice = 100_000.0;
        return s;
    }

    // ── Gate: not configured ──────────────────────────────────────────────────

    @Test
    void checkAndTrade_notConfigured_returnsSkipped() {
        when(futuresService.isConfigured()).thenReturn(false);
        BinanceScalpingTradeService.ScalpResult r = svc.checkAndTrade();
        assertEquals("skipped", r.status);
        assertTrue(r.message.contains("non configurée"));
    }

    // ── Gate: disabled ────────────────────────────────────────────────────────

    @Test
    void checkAndTrade_disabled_noActivePos_returnsSkipped() {
        // svc is disabled in @BeforeEach, no active position
        when(scalpingService.getSignal()).thenReturn(longSignal(80));
        BinanceScalpingTradeService.ScalpResult r = svc.checkAndTrade();
        assertEquals("skipped", r.status);
        assertTrue(r.message.contains("désactivé"));
    }

    // ── Gate: signal WAIT ─────────────────────────────────────────────────────

    @Test
    void checkAndTrade_signalWait_returnsSkipped() {
        svc.enable();
        when(scalpingService.getSignal()).thenReturn(waitSignal());
        BinanceScalpingTradeService.ScalpResult r = svc.checkAndTrade();
        assertEquals("skipped", r.status);
        assertTrue(r.message.contains("WAIT"));
    }

    // ── Gate: low confidence ──────────────────────────────────────────────────

    @Test
    void checkAndTrade_confidenceBelowThreshold_returnsSkipped() {
        svc.enable();
        when(scalpingService.getSignal()).thenReturn(longSignal(60)); // < 65
        BinanceScalpingTradeService.ScalpResult r = svc.checkAndTrade();
        assertEquals("skipped", r.status);
        assertTrue(r.message.contains("60"));
    }

    // ── Gate: cooldown ────────────────────────────────────────────────────────

    @Test
    void checkAndTrade_withinCooldown_returnsSkipped() throws Exception {
        svc.enable();
        setField(realBean(), "lastTradeAt", Instant.now().minusSeconds(60)); // 1 min ago < 5 min
        when(scalpingService.getSignal()).thenReturn(longSignal(80));
        BinanceScalpingTradeService.ScalpResult r = svc.checkAndTrade();
        assertEquals("skipped", r.status);
        assertTrue(r.message.toLowerCase().contains("cooldown"));
    }

    @Test
    void checkAndTrade_cooldownExpired_placesTrade() throws Exception {
        svc.enable();
        setField(realBean(), "lastTradeAt", Instant.now().minusSeconds(11 * 60)); // 11 min > 10 min
        when(scalpingService.getSignal()).thenReturn(longSignal(80));
        BinanceScalpingTradeService.ScalpResult r = svc.checkAndTrade();
        assertEquals("placed", r.status);
    }

    // ── Gate: Binance position already open ───────────────────────────────────

    @Test
    void checkAndTrade_binancePositionOpen_returnsSkipped() throws Exception {
        svc.enable();
        when(scalpingService.getSignal()).thenReturn(longSignal(80));
        when(futuresService.getPositionRisk(any()))
                .thenReturn("[{\"positionAmt\":\"0.050\"}]");
        BinanceScalpingTradeService.ScalpResult r = svc.checkAndTrade();
        assertEquals("skipped", r.status);
        assertTrue(r.message.contains("ouverte"));
    }

    // ── LONG trade: SL = STOP_MARKET SELL, TP = TAKE_PROFIT_MARKET SELL ──────

    @Test
    void checkAndTrade_longSignal_placesSLAndTP() throws Exception {
        svc.enable();
        when(scalpingService.getSignal()).thenReturn(longSignal(80));

        BinanceScalpingTradeService.ScalpResult r = svc.checkAndTrade();

        assertEquals("placed", r.status);
        assertEquals("LONG",   r.direction);

        // SL: 1 × STOP_MARKET SELL
        verify(futuresService, times(1))
                .placeCloseOrder(eq("BTCUSDT"), eq("SELL"), eq("STOP_MARKET"), anyDouble(), any(), isNull());
        // TP1 (60%) + TP2 (40%) both placed on Binance as independent algo orders
        verify(futuresService, times(2))
                .placeCloseOrder(eq("BTCUSDT"), eq("SELL"), eq("TAKE_PROFIT_MARKET"), anyDouble(), any(), isNull());
    }

    @Test
    void checkAndTrade_longSignal_slPriceBelowEntry() throws Exception {
        svc.enable();
        when(scalpingService.getSignal()).thenReturn(longSignal(80));
        svc.checkAndTrade();

        ArgumentCaptor<Double> priceCaptor = ArgumentCaptor.forClass(Double.class);
        verify(futuresService, times(1))
                .placeCloseOrder(any(), any(), eq("STOP_MARKET"), priceCaptor.capture(), any(), any());

        double slPrice = priceCaptor.getValue();
        assertTrue(slPrice < 100_000.0, "SL must be below entry for LONG: " + slPrice);
    }

    // ── SHORT trade: SL = STOP_MARKET BUY, TP = TAKE_PROFIT_MARKET BUY ───────

    @Test
    void checkAndTrade_shortSignal_placesSLAndTP() throws Exception {
        svc.enable();
        when(scalpingService.getSignal()).thenReturn(shortSignal(80));

        BinanceScalpingTradeService.ScalpResult r = svc.checkAndTrade();

        assertEquals("placed", r.status);
        assertEquals("SHORT",  r.direction);

        // SL: 1 × STOP_MARKET BUY
        verify(futuresService, times(1))
                .placeCloseOrder(eq("BTCUSDT"), eq("BUY"), eq("STOP_MARKET"), anyDouble(), any(), isNull());
        // TP1 (60%) + TP2 (40%) both placed on Binance as independent algo orders
        verify(futuresService, times(2))
                .placeCloseOrder(eq("BTCUSDT"), eq("BUY"), eq("TAKE_PROFIT_MARKET"), anyDouble(), any(), isNull());
    }

    @Test
    void checkAndTrade_shortSignal_slPriceAboveEntry() throws Exception {
        svc.enable();
        when(scalpingService.getSignal()).thenReturn(shortSignal(80));
        svc.checkAndTrade();

        ArgumentCaptor<Double> priceCaptor = ArgumentCaptor.forClass(Double.class);
        verify(futuresService, times(1))
                .placeCloseOrder(any(), any(), eq("STOP_MARKET"), priceCaptor.capture(), any(), any());

        double slPrice = priceCaptor.getValue();
        assertTrue(slPrice > 100_000.0, "SL must be above entry for SHORT: " + slPrice);
    }

    // ── cancelAllOrders called before market entry ────────────────────────────

    @Test
    void checkAndTrade_cancelAllOrdersCalledBeforeEntry() throws Exception {
        svc.enable();
        when(scalpingService.getSignal()).thenReturn(longSignal(80));
        svc.checkAndTrade();

        // cancelAllOrders must be called (and now internally also cancels algo orders)
        verify(futuresService, atLeastOnce()).cancelAllOrders("BTCUSDT");
        // market order comes after
        verify(futuresService).placeMarketOrder(eq("BTCUSDT"), eq("BUY"), any(), isNull());
    }

    // ── SL / TP failure resilience ────────────────────────────────────────────

    @Test
    void checkAndTrade_slOrderFails_emergencyClose() throws Exception {
        svc.enable();
        when(scalpingService.getSignal()).thenReturn(longSignal(80));
        when(futuresService.placeCloseOrder(any(), any(), eq("STOP_MARKET"), anyDouble(), any(), any()))
                .thenThrow(new RuntimeException("Binance Futures 400: -4120 algo error"));
        when(futuresService.closeWithMarket(any(), any(), any(), any())).thenReturn("{}");

        BinanceScalpingTradeService.ScalpResult r = svc.checkAndTrade();

        // SL failure is now fatal: position is closed immediately and error is returned
        assertEquals("error", r.status, "SL failure must return error and close position");
        assertTrue(r.message.contains("SL"), "Result message should mention SL");
        // Emergency market close must be called
        verify(futuresService, atLeastOnce()).closeWithMarket(eq("BTCUSDT"), eq("SELL"), any(), isNull());
        // No TP orders should be placed when position is emergency-closed
        verify(futuresService, never()).placeCloseOrder(any(), any(), eq("TAKE_PROFIT_MARKET"), anyDouble(), any(), any());
    }

    @Test
    void checkAndTrade_tp1JavaMonitor_longPositionTp1Hit_closesPartialAndSkips() throws Exception {
        svc.enable();
        BinanceScalpingTradeService real = realBean();
        setField(real, "activeDir",        "LONG");
        setField(real, "activeEntryPrice", 100_000.0);
        setField(real, "activeTp1Price",   100_300.0);
        setField(real, "activeTp2Price",   100_600.0);
        setField(real, "activeSlPrice",     99_850.0);
        setField(real, "activeQty",            0.003);
        setField(real, "activeQty40",          0.001);

        when(futuresService.closeWithMarket(any(), any(), any(), any())).thenReturn("{}");
        when(futuresService.getPositionRisk(any())).thenReturn("[{\"positionAmt\":\"0.003\"}]");

        ScalpingSignal tp1 = new ScalpingSignal();
        tp1.currentPrice = 100_350.0; // above TP1
        when(scalpingService.getSignal()).thenReturn(tp1);

        BinanceScalpingTradeService.ScalpResult r = svc.checkAndTrade();

        // TP1 fires a partial market close, returns "skipped" to keep monitoring for TP2
        assertEquals("skipped", r.status, "TP1 partial close should return skipped");
        assertTrue(r.message.contains("TP1"), "Message must mention TP1");
        // Java sends market close for 60% (qty60 = 0.003 - 0.001 = 0.002)
        verify(futuresService).closeWithMarket(eq("BTCUSDT"), eq("SELL"), eq("0.002"), isNull());
        // activeTp1Hit must be set
        assertTrue((Boolean) getField(real, "activeTp1Hit"), "activeTp1Hit must be true after TP1");
    }

    @Test
    void checkAndTrade_tp2JavaMonitor_longPositionTp2Hit_closesRemainder() throws Exception {
        svc.enable();
        BinanceScalpingTradeService real = realBean();
        setField(real, "activeDir",        "LONG");
        setField(real, "activeEntryPrice", 100_000.0);
        setField(real, "activeTp2Price",   100_600.0);
        setField(real, "activeSlPrice",     99_850.0);
        setField(real, "activeQty",            0.001);  // already at 40% after TP1
        setField(real, "activeTp1Hit",         true);
        setField(real, "activeTp1Pnl",        0.40);   // simulated TP1 locked-in PnL

        when(futuresService.closeWithMarket(any(), any(), any(), any())).thenReturn("{}");
        when(futuresService.getPositionRisk(any())).thenReturn("[{\"positionAmt\":\"0.001\"}]");

        ScalpingSignal tp2 = new ScalpingSignal();
        tp2.currentPrice = 100_650.0; // above TP2
        when(scalpingService.getSignal()).thenReturn(tp2);

        BinanceScalpingTradeService.ScalpResult r = svc.checkAndTrade();

        assertEquals("closed", r.status, "TP2 should fully close position");
        assertEquals("TP2",    r.message);
        // Total PnL = (100_650 - 100_000) * 0.001 + 0.40 (TP1) = 0.65 + 0.40 = 1.05 approx
        assertTrue(r.pnl > 0.9, "Total PnL (TP1+TP2) should accumulate: " + r.pnl);
        assertNull(getField(real, "activeDir"), "Active position must be cleared after TP2");
    }

    @Test
    void checkAndTrade_slFails_emergencyCloseAndError() throws Exception {
        svc.enable();
        when(scalpingService.getSignal()).thenReturn(longSignal(80));
        when(futuresService.placeCloseOrder(any(), any(), any(), anyDouble(), any(), any()))
                .thenThrow(new RuntimeException("network error"));
        when(futuresService.closeWithMarket(any(), any(), any(), any())).thenReturn("{}");

        BinanceScalpingTradeService.ScalpResult r = svc.checkAndTrade();

        assertEquals("error", r.status, "SL failure must return error status (position emergency-closed)");
        verify(futuresService, atLeastOnce()).closeWithMarket(eq("BTCUSDT"), eq("SELL"), any(), isNull());
    }

    @Test
    void checkAndTrade_slBreachRace_widenedSlPlacedSuccessfully() throws Exception {
        svc.enable();
        // SHORT signal — entry fills at 100000, SL nominal = 100000 * 1.0015 = 100150
        when(scalpingService.getSignal()).thenReturn(shortSignal(80));
        // Simulate race: BTC spiked to 101000 during the 1.5s wait after entry fill
        when(futuresService.getMarkPrice(any())).thenReturn(101_000.0);

        BinanceScalpingTradeService.ScalpResult r = svc.checkAndTrade();

        assertEquals("placed", r.status, "Trade should be placed with widened SL, not aborted");
        // SL order must be placed above the mark price (101000)
        ArgumentCaptor<Double> slCaptor = ArgumentCaptor.forClass(Double.class);
        verify(futuresService, times(1))
            .placeCloseOrder(eq("BTCUSDT"), eq("BUY"), eq("STOP_MARKET"), slCaptor.capture(), any(), any());
        assertTrue(slCaptor.getValue() > 101_000.0,
            "SL widened past mark price expected, got: " + slCaptor.getValue());
    }

    // ── Hedge mode: positionSide forwarded correctly ──────────────────────────

    @Test
    void checkAndTrade_hedgeMode_positionSideForwardedToCloseOrder() throws Exception {
        svc.enable();
        when(futuresService.isHedgeMode()).thenReturn(true);
        when(scalpingService.getSignal()).thenReturn(longSignal(80));

        svc.checkAndTrade();

        ArgumentCaptor<String> posSideCaptor = ArgumentCaptor.forClass(String.class);
        verify(futuresService, times(3))
                .placeCloseOrder(any(), any(), any(), anyDouble(), any(), posSideCaptor.capture());

        assertEquals("LONG", posSideCaptor.getValue(),
                   "Hedge mode SL order must carry positionSide=LONG");
    }

    @Test
    void checkAndTrade_oneWayMode_positionSideIsNull() throws Exception {
        svc.enable();
        when(futuresService.isHedgeMode()).thenReturn(false);
        when(scalpingService.getSignal()).thenReturn(longSignal(80));

        svc.checkAndTrade();

        ArgumentCaptor<String> posSideCaptor = ArgumentCaptor.forClass(String.class);
        verify(futuresService, times(3))
                .placeCloseOrder(any(), any(), any(), anyDouble(), any(), posSideCaptor.capture());

        assertNull(posSideCaptor.getValue(),
                   "One-Way mode SL order must have positionSide=null");
    }

    // ── closePosition: -4003 (qty=0) treated as already-closed ──────────────

    @Test
    void checkAndTrade_slMonitor_binanceReturns4003_treatedAsSuccess() throws Exception {
        svc.enable();
        // Seed an active LONG at entry=100_000, SL=99_850 (0.15% below)
        BinanceScalpingTradeService real = realBean();
        setField(real, "activeDir",        "LONG");
        setField(real, "activeEntryPrice", 100_000.0);
        setField(real, "activeSlPrice",     99_850.0);
        setField(real, "activeTpPrice",    100_300.0);
        setField(real, "activeQty",            0.002);

        // closeWithMarket throws -4003
        doThrow(new RuntimeException("Binance Futures 400: {\"code\":-4003,\"msg\":\"Quantity less than or equal to zero.\"}"))
                .when(futuresService).closeWithMarket(any(), any(), any(), any());

        // Binance confirms position still open (needed for new position check in checkAndTrade)
        when(futuresService.getPositionRisk(any())).thenReturn("[{\"positionAmt\":\"0.002\"}]");

        // Price drops to SL level
        ScalpingSignal sl = new ScalpingSignal();
        sl.direction    = "LONG";
        sl.confidence   = 80;
        sl.currentPrice = 99_800.0; // below SL
        when(scalpingService.getSignal()).thenReturn(sl);

        BinanceScalpingTradeService.ScalpResult r = svc.checkAndTrade();

        // -4003 must be treated as already-closed, not an error
        assertEquals("closed", r.status, "Expected 'closed' even on -4003");
        assertEquals("SL", r.message);
        // Active state cleared
        assertNull(getField(real, "activeDir"), "activeDir should be null after close");
    }

    @Test
    void checkAndTrade_slMonitor_binanceReturns2022_treatedAsSuccess() throws Exception {
        svc.enable();
        BinanceScalpingTradeService real = realBean();
        setField(real, "activeDir",        "SHORT");
        setField(real, "activeEntryPrice", 100_000.0);
        setField(real, "activeSlPrice",    100_200.0);
        setField(real, "activeTpPrice",     99_700.0);
        setField(real, "activeQty",             0.002);

        doThrow(new RuntimeException("Binance Futures 400: {\"code\":-2022,\"msg\":\"ReduceOnly Order is rejected.\"}"))
                .when(futuresService).closeWithMarket(any(), any(), any(), any());

        // Binance confirms position still open
        when(futuresService.getPositionRisk(any())).thenReturn("[{\"positionAmt\":\"0.002\"}]");

        ScalpingSignal sl = new ScalpingSignal();
        sl.direction    = "SHORT";
        sl.confidence   = 80;
        sl.currentPrice = 100_300.0; // above SHORT SL
        when(scalpingService.getSignal()).thenReturn(sl);

        BinanceScalpingTradeService.ScalpResult r = svc.checkAndTrade();

        assertEquals("closed", r.status, "Expected 'closed' even on -2022");
        assertEquals("SL", r.message);
        assertNull(getField(real, "activeDir"), "activeDir should be null after close");
    }

    // ── Internal SL/TP monitor: TP hit ────────────────────────────────────────

    @Test
    void checkAndTrade_tpMonitor_longPositionTpHit_returnsClosed() throws Exception {
        svc.enable();
        BinanceScalpingTradeService real = realBean();
        setField(real, "activeDir",        "LONG");
        setField(real, "activeEntryPrice", 100_000.0);
        setField(real, "activeTpPrice",    100_300.0);
        setField(real, "activeSlPrice",     99_850.0);
        setField(real, "activeQty",            0.002);

        when(futuresService.closeWithMarket(any(), any(), any(), any())).thenReturn("{}");

        // Binance confirms position still open (needed before internal TP check)
        when(futuresService.getPositionRisk(any())).thenReturn("[{\"positionAmt\":\"0.002\"}]");

        ScalpingSignal tp = new ScalpingSignal();
        tp.direction    = "LONG";
        tp.confidence   = 80;
        tp.currentPrice = 100_350.0; // above TP
        when(scalpingService.getSignal()).thenReturn(tp);

        BinanceScalpingTradeService.ScalpResult r = svc.checkAndTrade();

        assertEquals("closed", r.status);
        assertEquals("TP", r.message);
        assertTrue(r.pnl > 0, "TP on LONG should yield positive PnL: " + r.pnl);
        assertNull(getField(real, "activeDir"), "Active dir must be cleared after TP");
    }

    @Test
    void checkAndTrade_tpMonitor_shortPositionTpHit_returnsClosed() throws Exception {
        svc.enable();
        BinanceScalpingTradeService real = realBean();
        setField(real, "activeDir",        "SHORT");
        setField(real, "activeEntryPrice", 100_000.0);
        setField(real, "activeTpPrice",     99_700.0);
        setField(real, "activeSlPrice",    100_150.0);
        setField(real, "activeQty",            0.002);

        when(futuresService.closeWithMarket(any(), any(), any(), any())).thenReturn("{}");

        // Binance confirms position still open (needed before internal TP check)
        when(futuresService.getPositionRisk(any())).thenReturn("[{\"positionAmt\":\"0.002\"}]");

        ScalpingSignal tp = new ScalpingSignal();
        tp.direction    = "SHORT";
        tp.confidence   = 80;
        tp.currentPrice = 99_650.0; // below TP
        when(scalpingService.getSignal()).thenReturn(tp);

        BinanceScalpingTradeService.ScalpResult r = svc.checkAndTrade();

        assertEquals("closed", r.status);
        assertEquals("TP", r.message);
        assertTrue(r.pnl > 0, "TP on SHORT should yield positive PnL: " + r.pnl);
    }

    // ── Internal SL/TP monitor: position still alive ──────────────────────────

    @Test
    void checkAndTrade_activePosition_priceInRange_returnsSkipped() throws Exception {
        svc.enable();
        BinanceScalpingTradeService real = realBean();
        setField(real, "activeDir",        "LONG");
        setField(real, "activeEntryPrice", 100_000.0);
        setField(real, "activeTpPrice",    100_300.0);
        setField(real, "activeSlPrice",     99_850.0);
        setField(real, "activeQty",            0.002);

        // Binance confirms position still open
        when(futuresService.getPositionRisk(any())).thenReturn("[{\"positionAmt\":\"0.002\"}]");

        ScalpingSignal mid = new ScalpingSignal();
        mid.direction    = "LONG";
        mid.confidence   = 80;
        mid.currentPrice = 100_100.0; // between SL and TP
        when(scalpingService.getSignal()).thenReturn(mid);

        BinanceScalpingTradeService.ScalpResult r = svc.checkAndTrade();

        assertEquals("skipped", r.status);
        assertTrue(r.message.contains("active"), r.message);
        // No market order placed
        verify(futuresService, never()).placeMarketOrder(any(), any(), any(), any());
    }

    // ── statusMap: config fields ──────────────────────────────────────────────

    @Test
    void statusMap_configuredAndEnabled_containsExpectedKeys() throws Exception {
        svc.enable();
        svc.setAmountUsdt(50.0);
        svc.setLeverage(20);
        svc.setTpPct(0.5);
        svc.setSlPct(0.25);
        svc.setMinConfidence(70);

        when(futuresService.getUsdtBalances()).thenReturn(Map.of(
            "walletBalance", 500.0, "availableBalance", 480.0, "unrealizedProfit", 0.0));

        Map<String, Object> m = svc.statusMap();

        assertTrue((Boolean)  m.get("enabled"));
        assertTrue((Boolean)  m.get("configured"));
        assertEquals(70,      m.get("minConfidence"));
        assertEquals(50.0,    m.get("amountUsdt"));
        assertEquals(20,      m.get("leverage"));
        assertEquals(0.5,     m.get("tpPct"));
        assertEquals(0.25,    m.get("slPct"));
        assertNull(           m.get("activeDir"),   "No active position → null");
    }

    @Test
    void statusMap_walletIncludedWhenConfigured() {
        when(futuresService.getUsdtBalances()).thenReturn(Map.of(
            "walletBalance", 1234.56, "availableBalance", 1000.0, "unrealizedProfit", 12.3));

        Map<String, Object> m = svc.statusMap();

        @SuppressWarnings("unchecked")
        Map<String, Double> wallet = (Map<String, Double>) m.get("wallet");
        assertNotNull(wallet, "wallet key must be present when configured");
        assertEquals(1234.56, wallet.get("walletBalance"), 0.001);
        assertEquals(1000.0,  wallet.get("availableBalance"), 0.001);
    }

    @Test
    void statusMap_walletAbsentWhenNotConfigured() {
        when(futuresService.isConfigured()).thenReturn(false);

        Map<String, Object> m = svc.statusMap();

        assertFalse(m.containsKey("wallet"), "wallet must be absent when not configured");
    }

    @Test
    void statusMap_activePositionFieldsPresent() throws Exception {
        BinanceScalpingTradeService real = realBean();
        setField(real, "activeDir",        "SHORT");
        setField(real, "activeEntryPrice", 95_000.0);
        setField(real, "activeTpPrice",    94_700.0);
        setField(real, "activeSlPrice",    95_150.0);
        setField(real, "activeQty",            0.003);
        setField(real, "activeConf",               75);

        Map<String, Object> m = svc.statusMap();

        assertEquals("SHORT",   m.get("activeDir"));
        assertEquals(95_000.0,  m.get("activeEntry"));
        assertEquals(94_700.0,  m.get("activeTp"));
        assertEquals(95_150.0,  m.get("activeSl"));
        assertEquals(0.003,     m.get("activeQty"));
        assertEquals(75,        m.get("activeConf"));
    }

    // ── PnL calculation correctness ───────────────────────────────────────────

    @Test
    void closePosition_longPnlCalculation_isCorrect() throws Exception {
        svc.enable();
        BinanceScalpingTradeService real = realBean();
        setField(real, "activeDir",        "LONG");
        setField(real, "activeEntryPrice", 100_000.0);
        setField(real, "activeTpPrice",    100_300.0);
        setField(real, "activeSlPrice",     99_850.0);
        setField(real, "activeQty",            0.002);

        when(futuresService.closeWithMarket(any(), any(), any(), any())).thenReturn("{}");

        ScalpingSignal tp = new ScalpingSignal();
        tp.direction    = "LONG";
        tp.confidence   = 80;
        tp.currentPrice = 100_400.0;
        when(scalpingService.getSignal()).thenReturn(tp);

        BinanceScalpingTradeService.ScalpResult r = svc.checkAndTrade();

        // PnL = (exitPrice - entry) * qty = (100_400 - 100_000) * 0.002 = 0.80
        assertEquals(0.80, r.pnl, 0.01, "LONG PnL should be (exit-entry)*qty");
    }

    @Test
    void closePosition_shortPnlCalculation_isCorrect() throws Exception {
        svc.enable();
        BinanceScalpingTradeService real = realBean();
        setField(real, "activeDir",        "SHORT");
        setField(real, "activeEntryPrice", 100_000.0);
        setField(real, "activeTpPrice",     99_700.0);
        setField(real, "activeSlPrice",    100_150.0);
        setField(real, "activeQty",            0.002);

        when(futuresService.closeWithMarket(any(), any(), any(), any())).thenReturn("{}");

        ScalpingSignal tp = new ScalpingSignal();
        tp.direction    = "SHORT";
        tp.confidence   = 80;
        tp.currentPrice = 99_600.0;
        when(scalpingService.getSignal()).thenReturn(tp);

        BinanceScalpingTradeService.ScalpResult r = svc.checkAndTrade();

        // PnL = (entry - exitPrice) * qty = (100_000 - 99_600) * 0.002 = 0.80
        assertEquals(0.80, r.pnl, 0.01, "SHORT PnL should be (entry-exit)*qty");
    }

    // ── Double TP: execute() populates TP1+TP2 internal state ────────────────

    @Test
    void checkAndTrade_execute_setsActiveTp1AndTp2Prices_andQty40() throws Exception {
        svc.enable();
        ScalpingSignal sig = longSignal(80);
        sig.tp1 = 100_300.0;
        sig.tp2 = 100_600.0;
        when(scalpingService.getSignal()).thenReturn(sig);

        svc.checkAndTrade();

        BinanceScalpingTradeService real = realBean();
        // activeTp1Price and activeTp2Price must both be set from the signal — NOT 0
        assertEquals(100_300.0, (double) getField(real, "activeTp1Price"), 0.1,
            "activeTp1Price must be set from signal.tp1");
        assertEquals(100_600.0, (double) getField(real, "activeTp2Price"), 0.1,
            "activeTp2Price must NOT be 0 — if 0, TP2 branch is skipped forever");
        // qty40 must be positive so closePartial can compute qty60 = activeQty - qty40
        double qty40 = (double) getField(real, "activeQty40");
        assertTrue(qty40 > 0,
            "activeQty40 must be > 0 for double TP to work (qty=" + getField(real, "activeQty") + ")");
        // Not yet hit
        assertFalse((boolean) getField(real, "activeTp1Hit"), "TP1 not hit at open");
    }

    // ── Double TP: full end-to-end LONG — execute → TP1 (partial) → TP2 (close) ─

    @Test
    void checkAndTrade_doubleTP_longFullFlow_tp1ThenTp2_pnlAccumulates() throws Exception {
        svc.enable();
        when(futuresService.closeWithMarket(any(), any(), any(), any())).thenReturn("{}");

        // Sequence of getPositionRisk responses:
        //  call 1 = pre-trade guard (no position → allow entry)
        //  call 2 = TP1 monitoring check (full qty still open)
        //  call 3 = TP2 monitoring check (40% remaining after TP1)
        when(futuresService.getPositionRisk(any()))
            .thenReturn("[{\"positionAmt\":\"0\"}]")
            .thenReturn("[{\"positionAmt\":\"0.002\"}]")
            .thenReturn("[{\"positionAmt\":\"0.001\"}]");

        // ── Step 1: open trade ────────────────────────────────────────────────
        ScalpingSignal openSig = longSignal(80);
        openSig.tp1 = 100_300.0;
        openSig.tp2 = 100_600.0;
        when(scalpingService.getSignal()).thenReturn(openSig);

        BinanceScalpingTradeService.ScalpResult r1 = svc.checkAndTrade();
        assertEquals("placed", r1.status, "Trade must be placed");

        BinanceScalpingTradeService real = realBean();
        double qty   = (double) getField(real, "activeQty");
        double qty40 = (double) getField(real, "activeQty40");
        assertEquals(0.002, qty,   0.0001, "Total qty");
        assertEquals(0.001, qty40, 0.0001, "qty40 = 40% for TP2");
        assertEquals(100_300.0, (double) getField(real, "activeTp1Price"), 0.1, "TP1 from signal");
        assertEquals(100_600.0, (double) getField(real, "activeTp2Price"), 0.1,
            "TP2 must NOT be 0 after execute");

        // ── Step 2: TP1 hit — price above TP1 ────────────────────────────────
        ScalpingSignal tp1Sig = new ScalpingSignal();
        tp1Sig.currentPrice = 100_350.0;
        when(scalpingService.getSignal()).thenReturn(tp1Sig);

        BinanceScalpingTradeService.ScalpResult r2 = svc.checkAndTrade();
        assertEquals("skipped", r2.status,
            "TP1 hit must return skipped (still watching for TP2), got: " + r2.message);
        assertTrue(r2.message.contains("TP1"), "Message must mention TP1: " + r2.message);
        assertTrue((boolean) getField(real, "activeTp1Hit"), "activeTp1Hit=true after TP1");
        assertEquals(0.001, (double) getField(real, "activeQty"), 0.0001,
            "activeQty must be reduced to qty40=0.001 after TP1");
        // 60% partial close sent to Binance: qty60 = qty - qty40 = 0.002 - 0.001 = 0.001
        verify(futuresService, times(1))
            .closeWithMarket(eq("BTCUSDT"), eq("SELL"), eq("0.001"), isNull());

        // ── Step 3: TP2 hit — price above TP2 ────────────────────────────────
        ScalpingSignal tp2Sig = new ScalpingSignal();
        tp2Sig.currentPrice = 100_650.0;
        when(scalpingService.getSignal()).thenReturn(tp2Sig);

        BinanceScalpingTradeService.ScalpResult r3 = svc.checkAndTrade();
        assertEquals("closed", r3.status, "TP2 must fully close position");
        assertEquals("TP2", r3.message);
        // PnL = TP1 gain (100_350 - 100_000) × 0.001  +  TP2 gain (100_650 - 100_000) × 0.001
        //     = 0.350 + 0.650 = 1.000
        assertEquals(1.00, r3.pnl, 0.05, "Total PnL must accumulate TP1 + TP2: " + r3.pnl);
        assertNull(getField(real, "activeDir"), "Position cleared after TP2");
        // Two closeWithMarket calls total: TP1 (0.001) + TP2 (0.001)
        verify(futuresService, times(2))
            .closeWithMarket(eq("BTCUSDT"), eq("SELL"), eq("0.001"), isNull());
    }

    // ── Double TP: full end-to-end SHORT ─────────────────────────────────────

    @Test
    void checkAndTrade_doubleTP_shortFullFlow_tp1ThenTp2_pnlAccumulates() throws Exception {
        svc.enable();
        when(futuresService.closeWithMarket(any(), any(), any(), any())).thenReturn("{}");
        when(futuresService.getPositionRisk(any()))
            .thenReturn("[{\"positionAmt\":\"0\"}]")
            .thenReturn("[{\"positionAmt\":\"0.002\"}]")
            .thenReturn("[{\"positionAmt\":\"0.001\"}]");

        // ── Step 1: open SHORT trade ──────────────────────────────────────────
        ScalpingSignal openSig = shortSignal(80);
        openSig.tp1 = 99_700.0;   // SHORT: TP1 below entry
        openSig.tp2 = 99_400.0;   // SHORT: TP2 further below
        when(scalpingService.getSignal()).thenReturn(openSig);

        assertEquals("placed", svc.checkAndTrade().status);
        BinanceScalpingTradeService real = realBean();
        assertEquals(99_700.0, (double) getField(real, "activeTp1Price"), 0.1, "TP1 for SHORT");
        assertEquals(99_400.0, (double) getField(real, "activeTp2Price"), 0.1, "TP2 for SHORT must NOT be 0");

        // ── Step 2: TP1 hit — price BELOW TP1 for SHORT ──────────────────────
        ScalpingSignal tp1Sig = new ScalpingSignal();
        tp1Sig.currentPrice = 99_650.0;
        when(scalpingService.getSignal()).thenReturn(tp1Sig);

        BinanceScalpingTradeService.ScalpResult r2 = svc.checkAndTrade();
        assertEquals("skipped", r2.status,
            "SHORT TP1 hit must return skipped (watching for TP2): " + r2.message);
        assertTrue((boolean) getField(real, "activeTp1Hit"));
        // Close side for SHORT is BUY
        verify(futuresService, times(1))
            .closeWithMarket(eq("BTCUSDT"), eq("BUY"), eq("0.001"), isNull());

        // ── Step 3: TP2 hit — price BELOW TP2 for SHORT ──────────────────────
        ScalpingSignal tp2Sig = new ScalpingSignal();
        tp2Sig.currentPrice = 99_350.0;
        when(scalpingService.getSignal()).thenReturn(tp2Sig);

        BinanceScalpingTradeService.ScalpResult r3 = svc.checkAndTrade();
        assertEquals("closed", r3.status);
        assertEquals("TP2", r3.message);
        // PnL = (100_000 - 99_650) × 0.001  +  (100_000 - 99_350) × 0.001 = 0.35 + 0.65 = 1.00
        assertEquals(1.00, r3.pnl, 0.05);
        assertNull(getField(real, "activeDir"));
        verify(futuresService, times(2))
            .closeWithMarket(eq("BTCUSDT"), eq("BUY"), eq("0.001"), isNull());
    }

    // ── Double TP: after TP1, position remains active between TP1 and TP2 ────

    @Test
    void checkAndTrade_doubleTP_afterTp1_priceBeforeTp2_stillMonitoring() throws Exception {
        svc.enable();
        BinanceScalpingTradeService real = realBean();
        setField(real, "activeDir",        "LONG");
        setField(real, "activeEntryPrice", 100_000.0);
        setField(real, "activeTp1Price",   100_300.0);
        setField(real, "activeTp2Price",   100_600.0);
        setField(real, "activeSlPrice",     99_850.0);
        setField(real, "activeQty",            0.001);   // 40% remaining after TP1
        setField(real, "activeTp1Hit",         true);    // TP1 already done
        setField(real, "activeTp1Pnl",         0.35);    // TP1 gain locked in

        when(futuresService.getPositionRisk(any())).thenReturn("[{\"positionAmt\":\"0.001\"}]");

        // Price is between TP1 (100_300) and TP2 (100_600) — must keep watching
        ScalpingSignal mid = new ScalpingSignal();
        mid.currentPrice = 100_450.0;
        when(scalpingService.getSignal()).thenReturn(mid);

        BinanceScalpingTradeService.ScalpResult r = svc.checkAndTrade();

        assertEquals("skipped", r.status, "Must stay active between TP1 and TP2");
        assertTrue(r.message.contains("TP2") || r.message.contains("trailing"),
            "Must mention TP2 monitoring: " + r.message);
        // No close order — waiting for TP2
        verify(futuresService, never()).closeWithMarket(any(), any(), any(), any());
        assertNotNull(getField(real, "activeDir"), "Position not cleared yet");
    }

    // ── Double TP: SL hit after TP1 — accumulated PnL reduces total loss ─────

    @Test
    void checkAndTrade_doubleTP_afterTp1_slHit_pnlIncludesTp1Gain() throws Exception {
        svc.enable();
        BinanceScalpingTradeService real = realBean();
        setField(real, "activeDir",        "LONG");
        setField(real, "activeEntryPrice", 100_000.0);
        setField(real, "activeTp2Price",   100_600.0);
        setField(real, "activeSlPrice",     99_850.0);
        setField(real, "activeQty",            0.001);   // 40% remaining after TP1
        setField(real, "activeTp1Hit",         true);
        setField(real, "activeTp1Pnl",         0.35);    // $0.35 locked at TP1

        when(futuresService.closeWithMarket(any(), any(), any(), any())).thenReturn("{}");
        when(futuresService.getPositionRisk(any())).thenReturn("[{\"positionAmt\":\"0.001\"}]");

        ScalpingSignal slSig = new ScalpingSignal();
        slSig.currentPrice = 99_800.0; // below SL=99_850
        when(scalpingService.getSignal()).thenReturn(slSig);

        BinanceScalpingTradeService.ScalpResult r = svc.checkAndTrade();

        assertEquals("closed", r.status);
        assertEquals("SL", r.message);
        // PnL = (99_800 - 100_000) × 0.001  +  activeTp1Pnl(0.35)
        //     = -0.20 + 0.35 = +0.15
        // Without TP1 it would be -0.20; with TP1 the loss is reduced / turns positive
        assertEquals(0.15, r.pnl, 0.02,
            "PnL after SL must include TP1 gain to reduce loss: " + r.pnl);
    }

    // ── inferCloseReason — midpoint classification ────────────────────────────

    /**
     * Root-cause of the first live trade bug: SHORT exited at 73492 (above SL 73487.9),
     * but was classified as "TP" because the old 0.05% tolerance window (36 USDT) was
     * larger than the TP-SL distance (27 USDT) and TP was checked first.
     * The midpoint fix: (tp1+sl)/2 = 73474.3 → 73492 > 73474.3 → SL. ✓
     */
    @Test
    void inferCloseReason_short_exitAboveSl_classifiedAsSL_notTP() throws Exception {
        svc.enable();
        BinanceScalpingTradeService real = realBean();
        setField(real, "activeDir",        "SHORT");
        setField(real, "activeEntryPrice", 73477.7);
        setField(real, "activeTp1Price",   73460.7);
        setField(real, "activeTp2Price",   73443.8);
        setField(real, "activeSlPrice",    73487.9);
        setField(real, "activeQty",        0.002);
        setField(real, "activeQty40",      0.001);

        // Position already closed on Binance; Java fill price = 73492.3 (above SL)
        when(futuresService.getPositionRisk(any())).thenReturn("[{\"positionAmt\":\"0\"}]");
        when(futuresService.getRecentUserTrades(any(), anyInt())).thenReturn(
            "[{\"side\":\"BUY\",\"price\":\"73492.3\",\"qty\":\"0.002\",\"time\":\"9999999999999\"}]");

        ScalpingSignal sig = new ScalpingSignal();
        sig.currentPrice = 73492.3;
        when(scalpingService.getSignal()).thenReturn(sig);

        BinanceScalpingTradeService.ScalpResult r = svc.checkAndTrade();

        assertEquals("closed", r.status);
        assertEquals("SL", r.message,
            "Exit at 73492.3 (above SL 73487.9) must be classified as SL, not TP");
        assertTrue(r.pnl < 0, "SL exit on SHORT must be negative P&L");
    }

    @Test
    void inferCloseReason_short_exitBelowMidpoint_classifiedAsTP() throws Exception {
        svc.enable();
        BinanceScalpingTradeService real = realBean();
        setField(real, "activeDir",        "SHORT");
        setField(real, "activeEntryPrice", 73477.7);
        setField(real, "activeTp1Price",   73460.7);
        setField(real, "activeTp2Price",   73443.8);
        setField(real, "activeSlPrice",    73487.9);
        setField(real, "activeQty",        0.002);
        setField(real, "activeQty40",      0.001);

        // Fill at 73461 — just below TP1 (73460.7), well below midpoint (73474.3)
        when(futuresService.getPositionRisk(any())).thenReturn("[{\"positionAmt\":\"0\"}]");
        when(futuresService.getRecentUserTrades(any(), anyInt())).thenReturn(
            "[{\"side\":\"BUY\",\"price\":\"73461.0\",\"qty\":\"0.002\",\"time\":\"9999999999999\"}]");

        ScalpingSignal sig = new ScalpingSignal();
        sig.currentPrice = 73461.0;
        when(scalpingService.getSignal()).thenReturn(sig);

        BinanceScalpingTradeService.ScalpResult r = svc.checkAndTrade();

        assertEquals("closed", r.status);
        assertEquals("TP", r.message,
            "Exit at 73461 (near TP 73460.7, below midpoint 73474.3) must be classified as TP");
        assertTrue(r.pnl > 0, "TP exit on SHORT must be positive P&L");
    }

    // ── SL FAIL fix: retry with re-widened SL using fresh mark price ──────────

    /**
     * Core fix: first SL attempt fails (Binance -4120 — mark already past SL).
     * Before the retry, mark price is re-fetched (BTC kept rising) and SL is
     * widened again. Second attempt succeeds → trade placed instead of SL_FAILED.
     */
    @Test
    void slFail_attempt1Rejected_retryRewidensWithFreshMark_tradeSucceeds() throws Exception {
        svc.enable();
        ScalpingSignal sig = shortSignal(80);
        sig.atr = 100.0; // nominal SL = 100000 + 0.8×100 = 100080
        when(scalpingService.getSignal()).thenReturn(sig);

        // Pre-check: mark at 100300 (past SL 100080) → widened.
        // Retry re-check: mark moved further to 100600.
        when(futuresService.getMarkPrice(any()))
            .thenReturn(100_300.0)
            .thenReturn(100_600.0);

        // First STOP_MARKET call throws; second (after re-widen) succeeds.
        when(futuresService.placeCloseOrder(any(), any(), eq("STOP_MARKET"), anyDouble(), any(), any()))
            .thenThrow(new RuntimeException("Binance Futures 400: -4120 algo error"))
            .thenReturn("{\"algoId\":1234}");

        BinanceScalpingTradeService.ScalpResult r = svc.checkAndTrade();

        assertEquals("placed", r.status, "Retry re-widen must save the trade instead of SL_FAILED");

        ArgumentCaptor<Double> slCaptor = ArgumentCaptor.forClass(Double.class);
        verify(futuresService, times(2))
            .placeCloseOrder(any(), any(), eq("STOP_MARKET"), slCaptor.capture(), any(), any());
        double retrySl = slCaptor.getAllValues().get(1);
        assertTrue(retrySl > 100_600.0,
            "Retry SL must be widened past second mark price (100600), got: " + retrySl);
        assertNotNull(getField(realBean(), "activeDir"), "Position must be active after successful retry");
    }

    /**
     * Both SL attempts fail (connectivity loss): emergency market close fires.
     * No TP orders placed, no orphaned internal state.
     */
    @Test
    void slFail_bothAttemptsFail_emergencyCloseNoTpOrders_noOrphanedState() throws Exception {
        svc.enable();
        ScalpingSignal sig = shortSignal(80);
        sig.atr = 100.0;
        when(scalpingService.getSignal()).thenReturn(sig);

        when(futuresService.placeCloseOrder(any(), any(), eq("STOP_MARKET"), anyDouble(), any(), any()))
            .thenThrow(new RuntimeException("Binance Futures 400: -4120 algo error"));
        when(futuresService.closeWithMarket(any(), any(), any(), any())).thenReturn("{}");

        BinanceScalpingTradeService.ScalpResult r = svc.checkAndTrade();

        assertEquals("error", r.status, "Double SL failure must return error");
        assertTrue(r.message.contains("SL"), "Error message must mention SL");
        // Emergency market close triggered
        verify(futuresService, atLeastOnce())
            .closeWithMarket(eq("BTCUSDT"), eq("BUY"), any(), isNull());
        // TP orders must NOT be placed on an emergency-closed position
        verify(futuresService, never())
            .placeCloseOrder(any(), any(), eq("TAKE_PROFIT_MARKET"), anyDouble(), any(), any());
        // Internal state must not be orphaned (activeDir never set on this path)
        assertNull(getField(realBean(), "activeDir"), "No orphaned active state after emergency close");
    }

    /**
     * Low ATR produces a small buffer (0.5×50 = 25); the fix falls back to 0.1% of
     * mark price (≈100) when it is larger. This keeps the widened SL far enough above
     * the mark that Binance accepts it even during fast moves.
     */
    @Test
    void slFail_lowAtr_bufferUsesPercentageFallback_slMeaningfullyAboveMark() throws Exception {
        svc.enable();
        ScalpingSignal sig = shortSignal(80);
        sig.atr = 50.0; // 0.5×50 = 25 < 0.1%×100100 = 100.1 → percentage wins
        when(scalpingService.getSignal()).thenReturn(sig);

        // Mark slightly past the tight SL (100000 + 0.8×50 = 100040)
        when(futuresService.getMarkPrice(any())).thenReturn(100_100.0);

        BinanceScalpingTradeService.ScalpResult r = svc.checkAndTrade();

        assertEquals("placed", r.status);
        ArgumentCaptor<Double> slCaptor = ArgumentCaptor.forClass(Double.class);
        verify(futuresService, times(1))
            .placeCloseOrder(any(), any(), eq("STOP_MARKET"), slCaptor.capture(), any(), any());
        double sl = slCaptor.getValue();
        // Old 0.3×ATR = 15 → 100115. New max(25, 100.1) → ~100200. Must be above 100150.
        assertTrue(sl > 100_100.0 + 50,
            "Low-ATR buffer must use %-of-mark fallback (expected >100150), got: " + sl);
    }

    /**
     * Reproduces the real 04/06/2026 09:18 SL FAIL: SHORT at $63228 with a $66 SL gap,
     * BTC shot up $306 to $63534 before the order was submitted.
     * With the wider buffer fix, the trade is placed with widened SL above $63534
     * instead of falling through to the emergency close path.
     */
    @Test
    void slFail_realScenario_04jun0918_btcShot300_widenedSlSavesTheTrade() throws Exception {
        svc.enable();
        ScalpingSignal sig = new ScalpingSignal();
        sig.direction    = "SHORT";
        sig.confidence   = 100;
        sig.currentPrice = 63_228.2;
        sig.atr          = 83.0; // SL = 63228.2 + 0.8×83 = 63294.6 (matches real trade)
        when(scalpingService.getSignal()).thenReturn(sig);

        when(futuresService.placeMarketOrder(any(), any(), any(), any()))
            .thenReturn("{\"avgPrice\":\"63228.2\",\"executedQty\":\"0.003\"}");
        // BTC at 63534 during fill wait: $240 past SL, $306 above entry
        when(futuresService.getMarkPrice(any())).thenReturn(63_534.4);

        BinanceScalpingTradeService.ScalpResult r = svc.checkAndTrade();

        assertEquals("placed", r.status,
            "Wider buffer must place trade instead of SL_FAILED (pre-fix behavior)");
        ArgumentCaptor<Double> slCaptor = ArgumentCaptor.forClass(Double.class);
        verify(futuresService, times(1))
            .placeCloseOrder(eq("BTCUSDT"), eq("BUY"), eq("STOP_MARKET"), slCaptor.capture(), any(), any());
        assertTrue(slCaptor.getValue() > 63_534.4,
            "Widened SL must clear mark price 63534.4, got: " + slCaptor.getValue());
    }

    // ── closePartial: SL fired before Java TP1 detection ─────────────────────

    /**
     * Bug fix: SL fires on Binance before the Java 1-minute cycle detects TP1.
     * fetchFillsAfter(closeMs) returns null (SL fills are before closeMs).
     * The wider search from activeOpenedAt finds the SL fill (below entry).
     * Expected: whole position closed as SL with actual negative PnL — NOT a fake TP1 profit.
     */
    @Test
    void closePartial_slFiredBeforeJavaTp1Detection_closesFullPositionAsSLLoss() throws Exception {
        svc.enable();
        BinanceScalpingTradeService real = realBean();

        Instant openedAt = Instant.now().minusSeconds(120);
        setField(real, "activeDir",        "LONG");
        setField(real, "activeEntryPrice", 100_000.0);
        setField(real, "activeTp1Price",   100_300.0);
        setField(real, "activeTp2Price",   100_600.0);
        setField(real, "activeSlPrice",     99_850.0);
        setField(real, "activeQty",            0.004);
        setField(real, "activeQty40",          0.002);
        setField(real, "activeTp1Hit",         false);
        setField(real, "activeTp1Pnl",         0.0);
        setField(real, "activeFees",            0.0);
        setField(real, "activeOpenedAt",   openedAt);
        setField(real, "activeTp1CloseMs", 0L);

        // SL fill happened 60s ago (between openedAt and the upcoming closeMs)
        long slFillTime = Instant.now().minusSeconds(60).toEpochMilli();
        String slFillJson = String.format(
            "[{\"side\":\"SELL\",\"price\":\"99800\",\"qty\":\"0.004\",\"time\":\"%d\",\"commission\":\"0.04\"}]",
            slFillTime);

        // Binance still shows position open (race: SL just fired but positionRisk lags)
        when(futuresService.getPositionRisk(any())).thenReturn("[{\"positionAmt\":\"0.004\"}]");
        // closeWithMarket: -2022 — position already fully closed by SL
        when(futuresService.closeWithMarket(any(), any(), any(), any()))
            .thenThrow(new RuntimeException("Binance error: -2022 ReduceOnly Order is rejected"));
        // getRecentUserTrades always returns the SL fill; time filter in Java decides if included
        when(futuresService.getRecentUserTrades(any(), anyInt())).thenReturn(slFillJson);

        // Signal shows price at TP1 (triggering Java TP1 detection)
        ScalpingSignal sig = new ScalpingSignal();
        sig.currentPrice = 100_300.0;
        when(scalpingService.getSignal()).thenReturn(sig);

        BinanceScalpingTradeService.ScalpResult r = svc.checkAndTrade();

        assertEquals("closed", r.status,
            "Position should be fully closed, not partial TP1");
        assertEquals("SL", r.message,
            "Fill below entry must be classified as SL, not TP1");
        assertTrue(r.pnl < 0,
            "Trade with fill below entry must show a loss, got: " + r.pnl);
    }

    /**
     * Variant: fetchFillsAfter returns a fill immediately but it is below entry (SL fill
     * arrived within the narrow window). wrongDirection check must catch this and close
     * the full position as SL — not record a fake TP1 profit.
     */
    @Test
    void closePartial_fillBelowEntryInNarrowWindow_closesFullPositionAsSLLoss() throws Exception {
        svc.enable();
        BinanceScalpingTradeService real = realBean();

        Instant openedAt = Instant.now().minusSeconds(120);
        setField(real, "activeDir",        "LONG");
        setField(real, "activeEntryPrice", 100_000.0);
        setField(real, "activeTp1Price",   100_300.0);
        setField(real, "activeTp2Price",   100_600.0);
        setField(real, "activeSlPrice",     99_850.0);
        setField(real, "activeQty",            0.004);
        setField(real, "activeQty40",          0.002);
        setField(real, "activeTp1Hit",         false);
        setField(real, "activeTp1Pnl",         0.0);
        setField(real, "activeFees",            0.0);
        setField(real, "activeOpenedAt",   openedAt);
        setField(real, "activeTp1CloseMs", 0L);

        // SL fill in the future (guaranteed to pass the narrow time filter)
        String slFillJson =
            "[{\"side\":\"SELL\",\"price\":\"99800\",\"qty\":\"0.004\",\"time\":\"9999999999999\",\"commission\":\"0.04\"}]";

        when(futuresService.getPositionRisk(any())).thenReturn("[{\"positionAmt\":\"0.004\"}]");
        when(futuresService.closeWithMarket(any(), any(), any(), any())).thenReturn("{}");
        when(futuresService.getRecentUserTrades(any(), anyInt())).thenReturn(slFillJson);

        ScalpingSignal sig = new ScalpingSignal();
        sig.currentPrice = 100_300.0;
        when(scalpingService.getSignal()).thenReturn(sig);

        BinanceScalpingTradeService.ScalpResult r = svc.checkAndTrade();

        assertEquals("closed", r.status);
        assertEquals("SL", r.message,
            "Fill at 99800 is below entry 100000 — must be classified as SL, not fake TP1");
        assertTrue(r.pnl < 0,
            "Fill below entry must produce a loss, got: " + r.pnl);
    }

    /**
     * closePosition with null fill in narrow window: broader search finds actual fill
     * and uses it instead of the trigger price.
     * Scenario: TP2 triggered in Java, closeWithMarket returns -4003, fill happened
     * before closeMs (SL close), broader search from activeTp1CloseMs finds it.
     */
    @Test
    void closePosition_nullFillInNarrowWindow_broadensSearchAndUsesActualFill() throws Exception {
        svc.enable();
        BinanceScalpingTradeService real = realBean();

        Instant openedAt = Instant.now().minusSeconds(180);
        long tp1CloseMs  = Instant.now().minusSeconds(60).toEpochMilli();
        setField(real, "activeDir",        "LONG");
        setField(real, "activeEntryPrice", 100_000.0);
        setField(real, "activeTp1Price",   100_300.0);
        setField(real, "activeTp2Price",   100_600.0);
        setField(real, "activeSlPrice",     99_850.0);
        setField(real, "activeQty",            0.002);   // 40% remaining after TP1
        setField(real, "activeQty40",          0.002);
        setField(real, "activeTp1Hit",         true);
        setField(real, "activeTp1Pnl",         0.06);    // +$0.06 locked at TP1
        setField(real, "activeFees",            0.05);   // entry + TP1 fees
        setField(real, "activeOpenedAt",   openedAt);
        setField(real, "activeTp1CloseMs", tp1CloseMs);

        // Actual fill (SL) happened 30s ago — after tp1CloseMs, before upcoming closeMs
        long fillTime = Instant.now().minusSeconds(30).toEpochMilli();
        String fillJson = String.format(
            "[{\"side\":\"SELL\",\"price\":\"99800\",\"qty\":\"0.002\",\"time\":\"%d\",\"commission\":\"0.02\"}]",
            fillTime);

        when(futuresService.getPositionRisk(any())).thenReturn("[{\"positionAmt\":\"0.002\"}]");
        when(futuresService.closeWithMarket(any(), any(), any(), any()))
            .thenThrow(new RuntimeException("Binance error: -4003 quantity is invalid"));
        when(futuresService.getRecentUserTrades(any(), anyInt())).thenReturn(fillJson);

        // Signal price at TP2 triggers Java closePosition("TP2")
        ScalpingSignal sig = new ScalpingSignal();
        sig.currentPrice = 100_600.0;
        when(scalpingService.getSignal()).thenReturn(sig);

        BinanceScalpingTradeService.ScalpResult r = svc.checkAndTrade();

        assertEquals("closed", r.status);
        // Fill at 99800 (actual price from broader search) used instead of trigger price 100600
        // pnl = (99800 - 100000) * 0.002 + 0.06 = -0.40 + 0.06 = -0.34 → negative
        assertTrue(r.pnl < 0,
            "Actual SL fill (99800) must be used, not trigger price (100600). PnL got: " + r.pnl);
    }

    // ── analyzeHistory ────────────────────────────────────────────────────────

    @Test
    void analyzeHistory_noRecentLogs_returnsPasDeDonn​ees() {
        // days=0 → sinceMs = now → aucun log avec loggedAt >= now
        BinanceScalpingTradeService.AnalyticsReport r = svc.analyzeHistory(0);
        assertEquals("PAS_DE_DONNÉES", r.status);
        assertEquals(0, r.totalTrades);
        assertNotNull(r.topInsight);
        assertNotNull(r.byAdx);
    }

    @Test
    @TestTransaction
    void analyzeHistory_onePlacedTrade_appearsInReport() {
        ScalpingTrade trade = newClosedTrade("LONG", 95000, 95300, 12.0, 0.114);
        trade.persist();
        ScalpingTradeLog log = newPlacedLog(trade.id, 30.0, 65.0, 75, 3, 0);
        log.persist();

        BinanceScalpingTradeService.AnalyticsReport r = svc.analyzeHistory(1);

        assertEquals("OK", r.status);
        assertTrue(r.totalTrades >= 1, "totalTrades doit inclure le trade inséré");
        assertTrue(r.winRate >= 0 && r.winRate <= 100);
        assertNotNull(r.waitBreakdown);
        assertFalse(r.byAdx.isEmpty());
        assertFalse(r.byHour.isEmpty());
        assertFalse(r.byConfidence.isEmpty());
    }

    @Test
    @TestTransaction
    void analyzeHistory_winnerAndLoser_sumIsCorrect() {
        // Trade gagnant
        ScalpingTrade win = newClosedTrade("LONG", 95000, 95300, 12.0, 0.114);
        win.persist();
        newPlacedLog(win.id, 30.0, 65.0, 75, 3, 0).persist();

        // Trade perdant
        ScalpingTrade loss = newClosedTrade("SHORT", 95000, 95200, -6.0, 0.114);
        loss.pnlNet = -6.114;
        loss.status = "SL";
        loss.persist();
        newPlacedLog(loss.id, 32.0, 42.0, 72, 0, 3).persist();

        BinanceScalpingTradeService.AnalyticsReport r = svc.analyzeHistory(1);

        assertTrue(r.winCount + r.lossCount == r.totalTrades);
        assertEquals(r.winCount + r.lossCount, r.totalTrades);
        assertTrue(r.totalPnlNet != 0 || r.totalTrades == 0);
    }

    @Test
    @TestTransaction
    void analyzeHistory_adx30_bucketedAs2832() {
        ScalpingTrade trade = newClosedTrade("LONG", 95000, 95300, 12.0, 0.114);
        trade.persist();
        newPlacedLog(trade.id, 30.0, 65.0, 75, 3, 0).persist(); // adx=30 → "28-32"

        BinanceScalpingTradeService.AnalyticsReport r = svc.analyzeHistory(1);

        assertTrue(
            r.byAdx.stream().anyMatch(b -> "28-32".equals(b.label) && b.count >= 1),
            "Bucket '28-32' attendu pour adx=30; buckets: " +
                r.byAdx.stream().map(b -> b.label + "×" + b.count)
                    .collect(java.util.stream.Collectors.joining(", ")));
    }

    @Test
    @TestTransaction
    void analyzeHistory_rsi65_bucketedAsRsi6072() {
        ScalpingTrade trade = newClosedTrade("LONG", 95000, 95300, 12.0, 0.114);
        trade.persist();
        newPlacedLog(trade.id, 30.0, 65.0, 75, 3, 0).persist(); // rsi=65 → "RSI 60-72"

        BinanceScalpingTradeService.AnalyticsReport r = svc.analyzeHistory(1);

        assertTrue(
            r.byRsiZone.stream().anyMatch(b -> "RSI 60-72".equals(b.label) && b.count >= 1),
            "Bucket 'RSI 60-72' attendu pour rsi=65; buckets: " +
                r.byRsiZone.stream().map(b -> b.label)
                    .collect(java.util.stream.Collectors.joining(", ")));
    }

    @Test
    @TestTransaction
    void analyzeHistory_waitBreakdown_adxGateClassified() {
        ScalpingTradeLog wait = new ScalpingTradeLog();
        wait.outcome   = "wait";
        wait.loggedAt  = Instant.now().minusSeconds(1800);
        wait.reasoning = "ADX=24.5 — marché en RANGE (seuil 28), scalping suspendu.";
        wait.persist();

        BinanceScalpingTradeService.AnalyticsReport r = svc.analyzeHistory(1);

        assertTrue(r.waitBreakdown.total    >= 1, "total doit être >= 1");
        assertTrue(r.waitBreakdown.adxGate  >= 1, "adxGate doit capter 'ADX' + 'range'");
    }

    @Test
    @TestTransaction
    void analyzeHistory_waitBreakdown_ttmSqueezeClassified() {
        ScalpingTradeLog wait = new ScalpingTradeLog();
        wait.outcome   = "wait";
        wait.loggedAt  = Instant.now().minusSeconds(1800);
        wait.reasoning = "TTM Squeeze actif — BB dans KC, volatilité insuffisante.";
        wait.persist();

        BinanceScalpingTradeService.AnalyticsReport r = svc.analyzeHistory(1);

        assertTrue(r.waitBreakdown.ttmSqueeze >= 1, "ttmSqueeze doit capter 'ttm'");
    }

    // ── reconcileTradeHistory ─────────────────────────────────────────────────

    @Test
    void reconcileHistory_notConfigured_returnsError() {
        when(futuresService.isConfigured()).thenReturn(false);
        BinanceScalpingTradeService.HistoryReconcileReport r = svc.reconcileTradeHistory(7);
        assertEquals("ERREUR", r.status);
        assertNotNull(r.summary);
    }

    @Test
    void reconcileHistory_noLocalTrades_noFills_returnsOk() throws Exception {
        // days=0 → sinceMs = now → openedAt >= now matches nothing
        when(futuresService.getUserTradesFrom(any(), anyLong(), anyInt())).thenReturn("[]");
        BinanceScalpingTradeService.HistoryReconcileReport r = svc.reconcileTradeHistory(0);
        assertEquals("OK", r.status);
        assertEquals(0, r.localClosedCount);
        assertEquals(0, r.binanceFillCount);
        assertTrue(r.discrepancies.isEmpty());
    }

    @Test
    void reconcileHistory_orphanBinanceFill_detectedAsWarning() throws Exception {
        // days=0 → no local trades; Binance has a realized-PnL fill → orphan warning
        long now = Instant.now().toEpochMilli();
        String fills = String.format(Locale.US,
            "[{\"side\":\"SELL\",\"price\":\"95000.0\",\"qty\":\"0.002\"," +
            "\"commission\":\"0.057\",\"realizedPnl\":\"5.5\",\"time\":%d}]", now);
        when(futuresService.getUserTradesFrom(any(), anyLong(), anyInt())).thenReturn(fills);

        BinanceScalpingTradeService.HistoryReconcileReport r = svc.reconcileTradeHistory(0);

        assertEquals("AVERTISSEMENTS", r.status);
        assertEquals(0, r.errorsCount);
        assertEquals(1, r.warningsCount);
        BinanceScalpingTradeService.HistoryDiscrepancy d = r.discrepancies.get(0);
        assertEquals("ORPHAN_FILL", d.type);
        assertEquals("BINANCE",     d.origin);
        assertEquals("WARNING",     d.severity);
        assertEquals(-1L,           d.tradeId);
    }

    @Test
    @TestTransaction
    void reconcileHistory_binanceEmpty_closedTrade_missingFillsDetected() throws Exception {
        // Insert a closed LONG trade; Binance returns [] → MISSING_ENTRY_FILL + MISSING_EXIT_FILL
        ScalpingTrade t = newClosedTrade("LONG", 95000.0, 95300.0, 12.0, 0.1);
        t.persist();
        long id = t.id;

        when(futuresService.getUserTradesFrom(any(), anyLong(), anyInt())).thenReturn("[]");

        BinanceScalpingTradeService.HistoryReconcileReport r = svc.reconcileTradeHistory(1);

        List<BinanceScalpingTradeService.HistoryDiscrepancy> mine = discOf(r, id);
        assertTrue(mine.stream().anyMatch(d -> "MISSING_ENTRY_FILL".equals(d.type)),
            "MISSING_ENTRY_FILL expected; got: " + mine);
        assertTrue(mine.stream().anyMatch(d -> "MISSING_EXIT_FILL".equals(d.type)),
            "MISSING_EXIT_FILL expected; got: " + mine);
        assertTrue(mine.stream().allMatch(d -> "ERROR".equals(d.severity)));
        assertTrue(mine.stream().allMatch(d -> "BINANCE".equals(d.origin)));
        assertEquals("ERREURS_DÉTECTÉES", r.status);
    }

    @Test
    @TestTransaction
    void reconcileHistory_entryPriceMismatch_detectedForInsertedTrade() throws Exception {
        Instant openT  = Instant.now().minusSeconds(3600);
        Instant closeT = Instant.now().minusSeconds(1800);
        ScalpingTrade t = newClosedTrade("LONG", 95000.0, 95300.0, 12.0, 0.114);
        t.openedAt = openT;
        t.closedAt = closeT;
        t.persist();
        long id = t.id;

        // Entry fill: price 0.5% above stored entryPrice → WARNING (< 0.8% threshold)
        double binanceEntry = 95000.0 * 1.005; // 95475.0
        String fills = String.format(Locale.US,
            "[{\"side\":\"BUY\",\"price\":\"%.1f\",\"qty\":\"0.002\"," +
            "\"commission\":\"0.057\",\"realizedPnl\":\"0.0\",\"time\":%d}," +
            "{\"side\":\"SELL\",\"price\":\"95300.0\",\"qty\":\"0.002\"," +
            "\"commission\":\"0.057\",\"realizedPnl\":\"12.0\",\"time\":%d}]",
            binanceEntry,
            openT.toEpochMilli()  + 1000,
            closeT.toEpochMilli() + 1000);
        when(futuresService.getUserTradesFrom(any(), anyLong(), anyInt())).thenReturn(fills);

        BinanceScalpingTradeService.HistoryReconcileReport r = svc.reconcileTradeHistory(1);

        List<BinanceScalpingTradeService.HistoryDiscrepancy> mine = discOf(r, id);
        BinanceScalpingTradeService.HistoryDiscrepancy mismatch = mine.stream()
            .filter(d -> "ENTRY_PRICE_MISMATCH".equals(d.type))
            .findFirst().orElse(null);
        assertNotNull(mismatch, "ENTRY_PRICE_MISMATCH expected; got: " + mine);
        assertEquals("LOCAL",   mismatch.origin);
        assertEquals("WARNING", mismatch.severity);
        assertTrue(mismatch.localValue.contains("95000"), "localValue should contain stored entry price");
    }

    @Test
    @TestTransaction
    void reconcileHistory_entryPriceLargeGap_detectedAsError() throws Exception {
        Instant openT  = Instant.now().minusSeconds(3600);
        Instant closeT = Instant.now().minusSeconds(1800);
        ScalpingTrade t = newClosedTrade("SHORT", 95000.0, 94700.0, 10.0, 0.114);
        t.openedAt = openT;
        t.closedAt = closeT;
        t.persist();
        long id = t.id;

        // Entry fill: price 1% above stored entryPrice → ERROR (> 0.8% threshold)
        double binanceEntry = 95000.0 * 1.01; // 95950.0
        String fills = String.format(Locale.US,
            "[{\"side\":\"SELL\",\"price\":\"%.1f\",\"qty\":\"0.002\"," +
            "\"commission\":\"0.057\",\"realizedPnl\":\"0.0\",\"time\":%d}," +
            "{\"side\":\"BUY\",\"price\":\"94700.0\",\"qty\":\"0.002\"," +
            "\"commission\":\"0.057\",\"realizedPnl\":\"10.0\",\"time\":%d}]",
            binanceEntry,
            openT.toEpochMilli()  + 1000,
            closeT.toEpochMilli() + 1000);
        when(futuresService.getUserTradesFrom(any(), anyLong(), anyInt())).thenReturn(fills);

        BinanceScalpingTradeService.HistoryReconcileReport r = svc.reconcileTradeHistory(1);

        List<BinanceScalpingTradeService.HistoryDiscrepancy> mine = discOf(r, id);
        BinanceScalpingTradeService.HistoryDiscrepancy mismatch = mine.stream()
            .filter(d -> "ENTRY_PRICE_MISMATCH".equals(d.type))
            .findFirst().orElse(null);
        assertNotNull(mismatch, "ENTRY_PRICE_MISMATCH expected; got: " + mine);
        assertEquals("ERROR", mismatch.severity);
    }

    @Test
    @TestTransaction
    void reconcileHistory_pnlMismatch_detectedAsWarning() throws Exception {
        Instant openT  = Instant.now().minusSeconds(3600);
        Instant closeT = Instant.now().minusSeconds(1800);
        ScalpingTrade t = newClosedTrade("LONG", 95000.0, 95300.0, 12.0, 0.114);
        t.openedAt = openT;
        t.closedAt = closeT;
        t.persist();
        long id = t.id;

        // Binance shows realizedPnl=8.5 → gap of 3.5 > 1.5 threshold → PNL_MISMATCH
        String fills = String.format(Locale.US,
            "[{\"side\":\"BUY\",\"price\":\"95000.0\",\"qty\":\"0.002\"," +
            "\"commission\":\"0.057\",\"realizedPnl\":\"0.0\",\"time\":%d}," +
            "{\"side\":\"SELL\",\"price\":\"95300.0\",\"qty\":\"0.002\"," +
            "\"commission\":\"0.057\",\"realizedPnl\":\"8.5\",\"time\":%d}]",
            openT.toEpochMilli()  + 1000,
            closeT.toEpochMilli() + 1000);
        when(futuresService.getUserTradesFrom(any(), anyLong(), anyInt())).thenReturn(fills);

        BinanceScalpingTradeService.HistoryReconcileReport r = svc.reconcileTradeHistory(1);

        List<BinanceScalpingTradeService.HistoryDiscrepancy> mine = discOf(r, id);
        BinanceScalpingTradeService.HistoryDiscrepancy mismatch = mine.stream()
            .filter(d -> "PNL_MISMATCH".equals(d.type))
            .findFirst().orElse(null);
        assertNotNull(mismatch, "PNL_MISMATCH expected; got: " + mine);
        assertEquals("WARNING", mismatch.severity);
        assertEquals("LOCAL",   mismatch.origin);
        assertTrue(mismatch.localValue.contains("12.00"),   "localValue should mention stored PnL 12.00");
        assertTrue(mismatch.binanceValue.contains("8.50"),  "binanceValue should mention Binance PnL 8.50");
    }

    @Test
    @TestTransaction
    void reconcileHistory_feeMismatch_detectedAsWarning() throws Exception {
        Instant openT  = Instant.now().minusSeconds(3600);
        Instant closeT = Instant.now().minusSeconds(1800);
        ScalpingTrade t = newClosedTrade("LONG", 95000.0, 95300.0, 12.0, 2.0); // stored fees=2.0
        t.openedAt = openT;
        t.closedAt = closeT;
        t.persist();
        long id = t.id;

        // Binance commissions sum = 0.057+0.057 = 0.114 → gap = |2.0-0.114| = 1.886 > 0.5 → FEE_MISMATCH
        String fills = String.format(Locale.US,
            "[{\"side\":\"BUY\",\"price\":\"95000.0\",\"qty\":\"0.002\"," +
            "\"commission\":\"0.057\",\"realizedPnl\":\"0.0\",\"time\":%d}," +
            "{\"side\":\"SELL\",\"price\":\"95300.0\",\"qty\":\"0.002\"," +
            "\"commission\":\"0.057\",\"realizedPnl\":\"12.0\",\"time\":%d}]",
            openT.toEpochMilli()  + 1000,
            closeT.toEpochMilli() + 1000);
        when(futuresService.getUserTradesFrom(any(), anyLong(), anyInt())).thenReturn(fills);

        BinanceScalpingTradeService.HistoryReconcileReport r = svc.reconcileTradeHistory(1);

        List<BinanceScalpingTradeService.HistoryDiscrepancy> mine = discOf(r, id);
        assertTrue(mine.stream().anyMatch(d -> "FEE_MISMATCH".equals(d.type)),
            "FEE_MISMATCH expected; got: " + mine);
    }

    @Test
    @TestTransaction
    void reconcileHistory_perfectMatch_noDiscrepanciesForInsertedTrade() throws Exception {
        Instant openT  = Instant.now().minusSeconds(3600);
        Instant closeT = Instant.now().minusSeconds(1800);
        ScalpingTrade t = newClosedTrade("LONG", 95000.0, 95300.0, 12.0, 0.114);
        t.openedAt = openT;
        t.closedAt = closeT;
        t.persist();
        long id = t.id;

        // Binance fills exactly match: entry price 95000, exit price 95300, realizedPnl 12.0, fees 0.114
        String fills = String.format(Locale.US,
            "[{\"side\":\"BUY\",\"price\":\"95000.0\",\"qty\":\"0.002\"," +
            "\"commission\":\"0.057\",\"realizedPnl\":\"0.0\",\"time\":%d}," +
            "{\"side\":\"SELL\",\"price\":\"95300.0\",\"qty\":\"0.002\"," +
            "\"commission\":\"0.057\",\"realizedPnl\":\"12.0\",\"time\":%d}]",
            openT.toEpochMilli()  + 500,
            closeT.toEpochMilli() + 500);
        when(futuresService.getUserTradesFrom(any(), anyLong(), anyInt())).thenReturn(fills);

        BinanceScalpingTradeService.HistoryReconcileReport r = svc.reconcileTradeHistory(1);

        List<BinanceScalpingTradeService.HistoryDiscrepancy> mine = discOf(r, id);
        assertTrue(mine.isEmpty(),
            "Perfect match → no discrepancies for trade #" + id + "; got: " + mine);
        // Global counts might include discrepancies from other DB trades — only check per-id
        assertTrue(r.localClosedCount >= 1);
        assertTrue(r.binanceFillCount >= 2);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Convenience: discrepancies in report that belong to a specific trade id. */
    private List<BinanceScalpingTradeService.HistoryDiscrepancy> discOf(
            BinanceScalpingTradeService.HistoryReconcileReport r, long id) {
        return r.discrepancies.stream()
            .filter(d -> d.tradeId == id)
            .collect(java.util.stream.Collectors.toList());
    }

    private ScalpingTradeLog newPlacedLog(long tradeId, double adx, double rsi,
                                          int confidence, int longTfCount, int shortTfCount) {
        ScalpingTradeLog l = new ScalpingTradeLog();
        l.outcome      = "placed";
        l.tradeId      = tradeId;
        l.loggedAt     = Instant.now().minusSeconds(3600);
        l.direction    = longTfCount >= shortTfCount ? "LONG" : "SHORT";
        l.confidence   = confidence;
        l.currentPrice = 95000.0;
        l.adx          = adx;
        l.rsi          = rsi;
        l.longTfCount  = longTfCount;
        l.shortTfCount = shortTfCount;
        l.pillar1Score = longTfCount * 10;
        l.pillar2Score = 20;
        l.pillar3Score = 15;
        l.cvdPct       = 10.0;
        l.volumeRatio  = 1.5;
        l.macdHistogram= 0.5;
        l.stochK       = 60;
        l.stochD       = 55;
        return l;
    }

    private ScalpingTrade newClosedTrade(String dir, double entry, double exit,
                                         double pnl, double fees) {
        ScalpingTrade t = new ScalpingTrade();
        t.direction  = dir;
        t.entryPrice = entry;
        t.exitPrice  = exit;
        t.tpPrice    = exit;
        t.slPrice    = "LONG".equals(dir) ? entry * 0.99 : entry * 1.01;
        t.pnl        = pnl;
        t.pnlNet     = pnl - fees;
        t.fees       = fees;
        t.status     = "TP";
        t.openedAt   = Instant.now().minusSeconds(3600);
        t.closedAt   = Instant.now().minusSeconds(1800);
        t.amountUsdt = 50.0;
        t.leverage   = 10;
        t.confidence = 75;
        return t;
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private Object getField(Object target, String name) throws Exception {
        Field f = BinanceScalpingTradeService.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
}
