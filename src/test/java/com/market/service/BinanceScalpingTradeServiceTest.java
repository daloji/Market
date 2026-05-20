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
 *  - SL/TP placement failures are non-fatal — trade is still tracked
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

        // Only SL is placed on Binance — TP1/TP2 are managed by Java monitoring
        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sideCaptor = ArgumentCaptor.forClass(String.class);
        verify(futuresService, times(1))
                .placeCloseOrder(eq("BTCUSDT"), sideCaptor.capture(), typeCaptor.capture(),
                                 anyDouble(), any(), isNull());

        assertEquals("STOP_MARKET", typeCaptor.getValue(), "Only SL STOP_MARKET placed on Binance");
        assertEquals("SELL",        sideCaptor.getValue(), "SL close side must be SELL for LONG");
        verify(futuresService, never()).placeCloseOrder(any(), any(), eq("TAKE_PROFIT_MARKET"), anyDouble(), any(), any());
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

        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sideCaptor = ArgumentCaptor.forClass(String.class);
        verify(futuresService, times(1))
                .placeCloseOrder(eq("BTCUSDT"), sideCaptor.capture(), typeCaptor.capture(),
                                 anyDouble(), any(), isNull());

        assertEquals("STOP_MARKET", typeCaptor.getValue(), "Only SL STOP_MARKET placed on Binance");
        assertEquals("BUY",         sideCaptor.getValue(), "SL close side must be BUY for SHORT");
        verify(futuresService, never()).placeCloseOrder(any(), any(), eq("TAKE_PROFIT_MARKET"), anyDouble(), any(), any());
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
    void checkAndTrade_slOrderFails_tradeStillTracked() throws Exception {
        svc.enable();
        when(scalpingService.getSignal()).thenReturn(longSignal(80));
        when(futuresService.placeCloseOrder(any(), any(), eq("STOP_MARKET"), anyDouble(), any(), any()))
                .thenThrow(new RuntimeException("Binance Futures 400: -4120 algo error"));

        BinanceScalpingTradeService.ScalpResult r = svc.checkAndTrade();

        // Trade is still "placed" even when SL fails
        assertEquals("placed", r.status, "Trade should be placed even if SL order fails");
        assertTrue(r.message.contains("SL"), "Result message should mention SL");
        // No TAKE_PROFIT_MARKET orders placed on Binance — TP is Java-managed
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
    void checkAndTrade_slFails_tradeStillTracked() throws Exception {
        svc.enable();
        when(scalpingService.getSignal()).thenReturn(longSignal(80));
        when(futuresService.placeCloseOrder(any(), any(), any(), anyDouble(), any(), any()))
                .thenThrow(new RuntimeException("network error"));

        BinanceScalpingTradeService.ScalpResult r = svc.checkAndTrade();

        assertEquals("placed", r.status, "Trade result must be 'placed' even when SL Binance order fails");
    }

    // ── Hedge mode: positionSide forwarded correctly ──────────────────────────

    @Test
    void checkAndTrade_hedgeMode_positionSideForwardedToCloseOrder() throws Exception {
        svc.enable();
        when(futuresService.isHedgeMode()).thenReturn(true);
        when(scalpingService.getSignal()).thenReturn(longSignal(80));

        svc.checkAndTrade();

        ArgumentCaptor<String> posSideCaptor = ArgumentCaptor.forClass(String.class);
        verify(futuresService, times(1))
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
        verify(futuresService, times(1))
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

    // ── Helper ────────────────────────────────────────────────────────────────

    private Object getField(Object target, String name) throws Exception {
        Field f = BinanceScalpingTradeService.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
}
