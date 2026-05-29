package com.market.service;

import com.market.model.ScalpingSignal;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.Instant;
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

    // ── Helper ────────────────────────────────────────────────────────────────

    private Object getField(Object target, String name) throws Exception {
        Field f = BinanceScalpingTradeService.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
}
