package com.market.service;

import com.market.model.BitcoinSignal;
import com.market.model.Trade;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Integration tests for BinanceAutoTradeService using @InjectMock for all external dependencies.
 * The real BinanceAutoTradeService runs with mocked CryptoAnalysisService, BinanceFuturesService,
 * and TradeService. State is fully reset in @BeforeEach via reflection on the actual bean instance.
 */
@QuarkusTest
class BinanceAutoTradeServiceTest {

    /** Mirror of BinanceAutoTradeService.COOLDOWN_MS for test helpers. */
    private static final long COOLDOWN_MS_FOR_TEST = 30L * 60 * 1000;

    @Inject
    BinanceAutoTradeService svc;

    @InjectMock
    CryptoAnalysisService analysisService;

    @InjectMock
    BinanceFuturesService futuresService;

    @InjectMock
    TradeService tradeService;

    // ── Setup ─────────────────────────────────────────────────────────────────

    @BeforeEach
    void setup() throws Exception {
        // Ensure bean is initialized before getting its real instance
        svc.isEnabled();

        // Get the actual bean (not the client proxy) for field reflection
        BinanceAutoTradeService real = getRealBean();

        svc.disable();

        Field fLastTradeAt = BinanceAutoTradeService.class.getDeclaredField("lastTradeAt");
        fLastTradeAt.setAccessible(true);
        fLastTradeAt.set(real, null);

        Field fLastDirection = BinanceAutoTradeService.class.getDeclaredField("lastDirection");
        fLastDirection.setAccessible(true);
        fLastDirection.set(real, null);

        Field fConsecutive = BinanceAutoTradeService.class.getDeclaredField("consecutiveSameDir");
        fConsecutive.setAccessible(true);
        fConsecutive.set(real, 0);

        Field fLastSigDir = BinanceAutoTradeService.class.getDeclaredField("lastSignalDir");
        fLastSigDir.setAccessible(true);
        fLastSigDir.set(real, null);

        // Set predictable runtime config
        svc.setMinConfidence(60);
        svc.setAmountUsdt(50.0);
        svc.setLeverage(10);
        svc.setSlPct(1.5);
        svc.setTpPct(3.0);

        // Default mocks used by most tests
        when(futuresService.isConfigured()).thenReturn(true);
        when(futuresService.isHedgeMode()).thenReturn(false);
        when(analysisService.getSignal()).thenReturn(waitSignal());
        when(futuresService.getPositionRisk(any())).thenReturn("[{\"positionAmt\":\"0\"}]");
        when(futuresService.setLeverage(any(), anyInt())).thenReturn("{}");
        when(futuresService.placeMarketOrder(any(), any(), any(), any())).thenReturn("{\"avgPrice\":\"100000\",\"executedQty\":\"0.001\"}");
        when(futuresService.closeWithMarket(any(), any(), any(), any())).thenReturn("{\"avgPrice\":\"100000\"}");
        when(futuresService.placeCloseOrder(any(), any(), any(), anyDouble(), any(), any())).thenReturn("{}");
        when(futuresService.cancelAllOrders(any())).thenReturn("{}");
        when(futuresService.getOpenOrders(any())).thenReturn("[{\"type\":\"STOP_MARKET\"},{\"type\":\"TAKE_PROFIT_MARKET\"}]");
        when(futuresService.getAvailableBalance()).thenReturn(500.0); // sufficient balance by default
        when(tradeService.openTrade(anyDouble(), anyDouble(), any(), anyInt(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble(), any(), any(), any(), any()))
                .thenReturn(new Trade());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns the actual singleton bean instance (unwraps Quarkus client proxy). */
    private BinanceAutoTradeService getRealBean() {
        if (svc instanceof ClientProxy) {
            return (BinanceAutoTradeService) ((ClientProxy) svc).arc_contextualInstance();
        }
        return svc;
    }

    /** Sets lastTradeAt on the actual bean instance via reflection. */
    private void setLastTradeAt(Instant t) throws Exception {
        BinanceAutoTradeService real = getRealBean();
        Field f = BinanceAutoTradeService.class.getDeclaredField("lastTradeAt");
        f.setAccessible(true);
        f.set(real, t);
    }

    private BitcoinSignal waitSignal() {
        BitcoinSignal s = new BitcoinSignal();
        s.direction = "WAIT";
        s.confidence = 50;
        s.currentPrice = 100000.0;
        s.atr = 500.0;
        s.reasoning = "test";
        return s;
    }

    private BitcoinSignal longSignal(int conf) { return longSignal(conf, 100000.0); }
    private BitcoinSignal shortSignal(int conf) { return shortSignal(conf, 100000.0); }

    private BitcoinSignal longSignal(int conf, double price) {
        BitcoinSignal s = new BitcoinSignal();
        s.direction = "LONG";
        s.confidence = conf;
        s.currentPrice = price;
        s.atr = 500.0;
        s.reasoning = "test";
        s.marketStructure = "BULL_TREND";
        s.tf4hBias = "BULL";
        s.tf5mMomentum = "UP";
        return s;
    }

    private BitcoinSignal shortSignal(int conf, double price) {
        BitcoinSignal s = new BitcoinSignal();
        s.direction = "SHORT";
        s.confidence = conf;
        s.currentPrice = price;
        s.atr = 500.0;
        s.reasoning = "test";
        s.marketStructure = "BEAR_TREND";
        s.tf4hBias = "BEAR";
        s.tf5mMomentum = "DOWN";
        return s;
    }

    // ── checkAndTrade() gating tests ──────────────────────────────────────────

    @Test
    void checkAndTrade_disabled_returnsSkipped() {
        // svc is disabled in @BeforeEach
        BinanceAutoTradeService.AutoTradeResult r = svc.checkAndTrade();
        assertEquals("skipped", r.status);
        assertTrue(r.message.contains("désactivé"), "Message should mention désactivé");
    }

    @Test
    void checkAndTrade_notConfigured_returnsSkipped() {
        svc.enable();
        when(futuresService.isConfigured()).thenReturn(false);
        BinanceAutoTradeService.AutoTradeResult r = svc.checkAndTrade();
        assertEquals("skipped", r.status);
    }

    @Test
    void checkAndTrade_signalError_returnsError() {
        svc.enable();
        when(analysisService.getSignal()).thenThrow(new RuntimeException("connection timeout"));
        BinanceAutoTradeService.AutoTradeResult r = svc.checkAndTrade();
        assertEquals("error", r.status);
    }

    @Test
    void checkAndTrade_signalWait_returnsSkipped() {
        svc.enable();
        when(analysisService.getSignal()).thenReturn(waitSignal());
        BinanceAutoTradeService.AutoTradeResult r = svc.checkAndTrade();
        assertEquals("skipped", r.status);
        assertTrue(r.message.contains("WAIT"), "Message should mention WAIT");
    }

    @Test
    void checkAndTrade_longBelowThreshold_returnsSkipped() {
        svc.enable();
        // LONG conf=55 < mc=60 → skipped
        when(analysisService.getSignal()).thenReturn(longSignal(55, 100000));
        BinanceAutoTradeService.AutoTradeResult r = svc.checkAndTrade();
        assertEquals("skipped", r.status);
        assertTrue(r.message.contains("LONG"), "Message should mention LONG");
        assertTrue(r.message.contains("55"), "Message should mention confidence value 55");
    }

    @Test
    void checkAndTrade_shortAboveThreshold_returnsSkipped() {
        svc.enable();
        // SHORT conf=45 > (100-60)=40 → skipped (confidence not ok)
        when(analysisService.getSignal()).thenReturn(shortSignal(45, 100000));
        BinanceAutoTradeService.AutoTradeResult r = svc.checkAndTrade();
        assertEquals("skipped", r.status);
        assertTrue(r.message.contains("SHORT"), "Message should mention SHORT");
        assertTrue(r.message.contains("45"), "Message should mention confidence value 45");
    }

    @Test
    void checkAndTrade_shortAtThreshold_executes() throws Exception {
        svc.enable();
        // SHORT conf=40 == (100-60)=40 → confOk=true
        // Requires 2 consecutive confirmations: 1st call returns "confirmation 1/2", 2nd places
        when(analysisService.getSignal()).thenReturn(shortSignal(40, 100000));
        BinanceAutoTradeService.AutoTradeResult r1 = svc.checkAndTrade();
        assertEquals("skipped", r1.status, "First call should be confirmation 1/2");
        assertTrue(r1.message.contains("1/2") || r1.message.contains("confirmation"), "Should mention confirmation: " + r1.message);
        BinanceAutoTradeService.AutoTradeResult r = svc.checkAndTrade();
        assertEquals("placed", r.status);
        assertEquals("SHORT", r.direction);
    }

    @Test
    void checkAndTrade_longAtThreshold_executes() throws Exception {
        svc.enable();
        // LONG conf=60 == mc=60 → confOk=true
        // Requires 2 consecutive confirmations
        when(analysisService.getSignal()).thenReturn(longSignal(60, 100000));
        BinanceAutoTradeService.AutoTradeResult r1 = svc.checkAndTrade();
        assertEquals("skipped", r1.status, "First call should be confirmation 1/2");
        BinanceAutoTradeService.AutoTradeResult r = svc.checkAndTrade();
        assertEquals("placed", r.status);
        assertEquals("LONG", r.direction);
    }

    @Test
    void checkAndTrade_openPosition_sameDirection_isBlocked() throws Exception {
        // Same-direction stacking is blocked: no LONG if LONG already tracked
        svc.enable();
        when(analysisService.getSignal()).thenReturn(longSignal(75, 100000));
        // First trade: no position open yet
        when(futuresService.getPositionRisk(any())).thenReturn("[{\"positionAmt\":\"0\"}]");
        svc.checkAndTrade();
        BinanceAutoTradeService.AutoTradeResult r1 = svc.checkAndTrade();
        assertEquals("placed", r1.status, "First LONG should be placed");

        // After trade placed: simulate position still open (LONG 0.05 BTC)
        when(futuresService.getPositionRisk(any())).thenReturn("[{\"positionAmt\":\"0.05\"}]");
        // Reset cooldown so only the same-direction filter blocks
        setLastTradeAt(Instant.now().minusMillis(COOLDOWN_MS_FOR_TEST + 1000));

        // Signal stays LONG — should be blocked (same direction already open)
        BinanceAutoTradeService.AutoTradeResult r2 = svc.checkAndTrade();
        assertEquals("skipped", r2.status, "Should be blocked: LONG position already open");
        assertTrue(r2.message.contains("LONG") && r2.message.contains("déjà ouverte"),
            "Should mention same-direction block: " + r2.message);
    }

    @Test
    void checkAndTrade_openPosition_oppositeDirection_isAllowed() throws Exception {
        // Opposite direction is allowed (hedge): LONG open → SHORT signal → trade placed
        svc.enable();
        when(analysisService.getSignal()).thenReturn(longSignal(75, 100000));
        when(futuresService.getPositionRisk(any())).thenReturn("[{\"positionAmt\":\"0\"}]");
        svc.checkAndTrade();
        BinanceAutoTradeService.AutoTradeResult rLong = svc.checkAndTrade();
        assertEquals("placed", rLong.status, "LONG should be placed");

        // After LONG placed: position is open
        when(futuresService.getPositionRisk(any())).thenReturn("[{\"positionAmt\":\"0.05\"}]");
        // Reset cooldown
        setLastTradeAt(Instant.now().minusMillis(COOLDOWN_MS_FOR_TEST + 1000));

        // Switch to SHORT signal — opposite direction allowed (hedge)
        when(analysisService.getSignal()).thenReturn(shortSignal(25, 100000));
        svc.checkAndTrade(); // confirmation 1/2
        BinanceAutoTradeService.AutoTradeResult rShort = svc.checkAndTrade(); // confirmation 2/2
        assertEquals("placed", rShort.status, "SHORT should be placed when LONG already open (hedge): " + rShort.message);
    }

    @Test
    void checkAndTrade_insufficientBalance_isSkipped() throws Exception {
        svc.enable();
        when(analysisService.getSignal()).thenReturn(longSignal(75, 100000));
        when(futuresService.getAvailableBalance()).thenReturn(10.0); // 10 USDT < 50 USDT mise
        // Seed confirmation
        svc.checkAndTrade();
        BinanceAutoTradeService.AutoTradeResult r = svc.checkAndTrade();
        assertEquals("skipped", r.status, "Should be skipped when balance < amount");
        assertTrue(r.message.contains("Solde insuffisant"), "Should mention insufficient balance: " + r.message);
    }

    @Test
    void checkAndTrade_sufficientBalance_isPlaced() throws Exception {
        svc.enable();
        when(analysisService.getSignal()).thenReturn(longSignal(75, 100000));
        when(futuresService.getAvailableBalance()).thenReturn(200.0); // 200 USDT >= 50 USDT
        svc.checkAndTrade();
        BinanceAutoTradeService.AutoTradeResult r = svc.checkAndTrade();
        assertEquals("placed", r.status, "Should be placed with sufficient balance: " + r.message);
    }

    @Test
    void checkAndTrade_apiFailure_returnsError() throws Exception {
        svc.enable();
        when(analysisService.getSignal()).thenReturn(longSignal(75, 100000));
        when(futuresService.setLeverage(any(), anyInt())).thenThrow(new RuntimeException("API error"));
        // First call: confirmation 1/2 (skipped before hitting setLeverage)
        svc.checkAndTrade();
        // Second call: confirmation 2/2 → execute() → setLeverage throws → error
        BinanceAutoTradeService.AutoTradeResult r = svc.checkAndTrade();
        assertEquals("error", r.status);
    }

    // ── SL/TP calculation tests ───────────────────────────────────────────────

    @Test
    void execute_longSLTP_correctPercentages() {
        svc.enable();
        // sl-pct=1.5, tp-pct=3.0, entry=100000
        // sl = 100000 * (1 - 1.5/100) = 98500.0
        // tp1 = 100000 * (1 + 3.0/100) = 103000.0
        when(analysisService.getSignal()).thenReturn(longSignal(75, 100000));
        svc.checkAndTrade(); // confirmation 1/2
        BinanceAutoTradeService.AutoTradeResult r = svc.checkAndTrade(); // places trade
        assertEquals("placed", r.status);
        assertEquals("LONG", r.direction);
        assertEquals(98500.0, r.sl,  1.0, "LONG SL should be ~98500 (entry - 1.5%)");
        assertEquals(103000.0, r.tp1, 1.0, "LONG TP should be ~103000 (entry + 3.0%)");
    }

    @Test
    void execute_shortSLTP_correctPercentages() {
        svc.enable();
        // SHORT conf=35 <= (100-60)=40 → executes
        // sl = 100000 * (1 + 1.5/100) = 101500.0
        // tp1 = 100000 * (1 - 3.0/100) = 97000.0
        when(analysisService.getSignal()).thenReturn(shortSignal(35, 100000));
        svc.checkAndTrade(); // confirmation 1/2
        BinanceAutoTradeService.AutoTradeResult r = svc.checkAndTrade(); // places trade
        assertEquals("placed", r.status);
        assertEquals("SHORT", r.direction);
        assertEquals(101500.0, r.sl,  1.0, "SHORT SL should be ~101500 (entry + 1.5%)");
        assertEquals(97000.0,  r.tp1, 1.0, "SHORT TP should be ~97000 (entry - 3.0%)");
    }

    // ── Config getters/setters ────────────────────────────────────────────────

    @Test
    void setMinConfidence_updatesValue() {
        svc.setMinConfidence(65);
        assertEquals(65, svc.getMinConfidence());
    }

    @Test
    void setAmountUsdt_updatesValue() {
        svc.setAmountUsdt(100.0);
        assertEquals(100.0, svc.getAmountUsdt(), 0.001);
    }

    @Test
    void setLeverage_updatesValue() {
        svc.setLeverage(5);
        assertEquals(5, svc.getLeverage());
    }

    @Test
    void setSlPct_updatesValue() {
        svc.setSlPct(2.0);
        assertEquals(2.0, svc.getSlPct(), 0.001);
    }

    @Test
    void setTpPct_updatesValue() {
        svc.setTpPct(5.0);
        assertEquals(5.0, svc.getTpPct(), 0.001);
    }

    @Test
    void enable_disable_togglesState() {
        svc.enable();
        assertTrue(svc.isEnabled(), "Should be enabled after enable()");
        svc.disable();
        assertFalse(svc.isEnabled(), "Should be disabled after disable()");
    }

    // ── diagnose() tests ──────────────────────────────────────────────────────

    @Test
    void diagnose_disabled_showsCorrectState() {
        // svc disabled in @BeforeEach, signal=WAIT
        BinanceAutoTradeService.DiagResult d = svc.diagnose();
        assertFalse(d.enabled);
        assertFalse(d.wouldTrade);
        assertNotNull(d.blockingReason);
        assertTrue(d.blockingReason.contains("désactivé"),
                "blockingReason should mention désactivé: " + d.blockingReason);
    }

    @Test
    void diagnose_enabled_longSignal_meetingThreshold_wouldTrade() {
        svc.enable();
        when(analysisService.getSignal()).thenReturn(longSignal(70, 100000)); // 70 >= 60
        // position mock already returns empty position in @BeforeEach
        // Seed 1 confirmation cycle first (confirmation logic requires 2 consecutive cycles)
        svc.checkAndTrade(); // sets consecutiveSameDir=1 for LONG
        // Now diagnose projects forward: count+1=2 >= required=2 → confirmOk=true
        BinanceAutoTradeService.DiagResult d = svc.diagnose();
        assertTrue(d.wouldTrade, "All conditions met → wouldTrade must be true");
        assertNull(d.blockingReason, "No blocking reason when all conditions met");
    }

    @Test
    void diagnose_shortSignal_aboveThreshold_confidenceNotOk() {
        svc.enable();
        when(analysisService.getSignal()).thenReturn(shortSignal(45, 100000)); // 45 > 40
        BinanceAutoTradeService.DiagResult d = svc.diagnose();
        assertFalse(d.confidenceOk);
        assertFalse(d.wouldTrade);
        assertNotNull(d.blockingReason);
        assertTrue(d.blockingReason.contains("SHORT"), "blockingReason: " + d.blockingReason);
        assertTrue(d.blockingReason.contains("45"),    "blockingReason: " + d.blockingReason);
    }

    @Test
    void diagnose_shortSignal_belowThreshold_confidenceOk() {
        svc.enable();
        when(analysisService.getSignal()).thenReturn(shortSignal(35, 100000)); // 35 <= 40
        BinanceAutoTradeService.DiagResult d = svc.diagnose();
        assertTrue(d.confidenceOk, "SHORT conf=35 <= threshold 40 → confidenceOk must be true");
    }

    @Test
    void diagnose_signalFields_populated() {
        svc.enable();
        when(analysisService.getSignal()).thenReturn(longSignal(70, 99500.0));
        BinanceAutoTradeService.DiagResult d = svc.diagnose();
        assertEquals("LONG", d.signalDirection);
        assertEquals(70, d.signalConfidence);
        assertEquals(99500.0, d.signalPrice, 1.0);
    }

    @Test
    void diagnose_allConfigFields_populated() {
        svc.setMinConfidence(62);
        svc.setSlPct(2.0);
        svc.setTpPct(4.0);
        svc.setLeverage(5);
        svc.setAmountUsdt(75.0);
        BinanceAutoTradeService.DiagResult d = svc.diagnose();
        assertEquals(62,   d.minConfidence);
        assertEquals(2.0,  d.slPct,      0.001);
        assertEquals(4.0,  d.tpPct,      0.001);
        assertEquals(5,    d.leverage);
        assertEquals(75.0, d.amountUsdt, 0.001);
    }

    // ── Hard filter tests ─────────────────────────────────────────────────────

    @Test
    void checkAndTrade_consolidation_blocksLong() {
        svc.enable();
        BitcoinSignal s = longSignal(70, 100000);
        s.marketStructure = "CONSOLIDATION";
        when(analysisService.getSignal()).thenReturn(s);
        BinanceAutoTradeService.AutoTradeResult r = svc.checkAndTrade();
        assertEquals("skipped", r.status);
        assertTrue(r.message.toLowerCase().contains("consolidation"),
                "Should mention consolidation: " + r.message);
    }

    @Test
    void checkAndTrade_bearTrend4h_blocksLong() {
        svc.enable();
        BitcoinSignal s = longSignal(70, 100000);
        s.tf4hBias  = "BEAR";
        s.tf4hScore = -25; // strong BEAR conviction (|score| >= 15 required to block)
        when(analysisService.getSignal()).thenReturn(s);
        BinanceAutoTradeService.AutoTradeResult r = svc.checkAndTrade();
        assertEquals("skipped", r.status);
        assertTrue(r.message.contains("4h") && r.message.contains("BEAR"),
                "Should mention 4h BEAR: " + r.message);
    }

    @Test
    void checkAndTrade_bullTrend4h_blocksShort() {
        svc.enable();
        BitcoinSignal s = shortSignal(35, 100000);
        s.tf4hBias  = "BULL";
        s.tf4hScore = 25; // strong BULL conviction
        when(analysisService.getSignal()).thenReturn(s);
        BinanceAutoTradeService.AutoTradeResult r = svc.checkAndTrade();
        assertEquals("skipped", r.status);
        assertTrue(r.message.contains("4h") && r.message.contains("BULL"),
                "Should mention 4h BULL: " + r.message);
    }

    @Test
    void checkAndTrade_5mDown_blocksLong() {
        svc.enable();
        BitcoinSignal s = longSignal(70, 100000);
        s.tf5mMomentum = "DOWN";
        when(analysisService.getSignal()).thenReturn(s);
        BinanceAutoTradeService.AutoTradeResult r = svc.checkAndTrade();
        assertEquals("skipped", r.status);
        assertTrue(r.message.contains("5m") && r.message.contains("DOWN"),
                "Should mention 5m DOWN: " + r.message);
    }

    @Test
    void checkAndTrade_5mUp_blocksShort() {
        svc.enable();
        BitcoinSignal s = shortSignal(35, 100000);
        s.tf5mMomentum = "UP";
        when(analysisService.getSignal()).thenReturn(s);
        BinanceAutoTradeService.AutoTradeResult r = svc.checkAndTrade();
        assertEquals("skipped", r.status);
        assertTrue(r.message.contains("5m") && r.message.contains("UP"),
                "Should mention 5m UP: " + r.message);
    }

    @Test
    void diagnose_consolidation_msOkFalse() {
        svc.enable();
        BitcoinSignal s = longSignal(70, 100000);
        s.marketStructure = "CONSOLIDATION";
        when(analysisService.getSignal()).thenReturn(s);
        BinanceAutoTradeService.DiagResult d = svc.diagnose();
        assertFalse(d.msOk, "CONSOLIDATION should set msOk=false");
        assertFalse(d.wouldTrade);
        assertTrue(d.blockingReason != null && d.blockingReason.toLowerCase().contains("consolidation"),
                "blockingReason should mention consolidation: " + d.blockingReason);
    }

    @Test
    void diagnose_bearTrend4h_tf4hOkFalse() {
        svc.enable();
        BitcoinSignal s = longSignal(70, 100000);
        s.tf4hBias  = "BEAR";
        s.tf4hScore = -25; // strong conviction required
        when(analysisService.getSignal()).thenReturn(s);
        BinanceAutoTradeService.DiagResult d = svc.diagnose();
        assertFalse(d.tf4hOk, "4h BEAR on LONG should set tf4hOk=false");
        assertFalse(d.wouldTrade);
    }

    // ── Volatility filter (P4) tests ──────────────────────────────────────────

    @Test
    void checkAndTrade_extremeVolatility_blocksLong() {
        svc.enable();
        BitcoinSignal s = longSignal(70, 100000);
        s.volatilityRegime = "EXTREME";
        s.atrPct = 3.5;
        when(analysisService.getSignal()).thenReturn(s);
        BinanceAutoTradeService.AutoTradeResult r = svc.checkAndTrade();
        assertEquals("skipped", r.status);
        assertTrue(r.message.toLowerCase().contains("extreme") || r.message.contains("EXTREME"),
                "Should mention EXTREME: " + r.message);
    }

    @Test
    void checkAndTrade_extremeCandle_blocksShort() {
        svc.enable();
        BitcoinSignal s = shortSignal(35, 100000);
        s.extremeCandle = true;
        when(analysisService.getSignal()).thenReturn(s);
        BinanceAutoTradeService.AutoTradeResult r = svc.checkAndTrade();
        assertEquals("skipped", r.status);
        assertTrue(r.message.toLowerCase().contains("extrême") || r.message.toLowerCase().contains("extreme"),
                "Should mention extreme candle: " + r.message);
    }

    @Test
    void checkAndTrade_bollingerSqueeze_blocksLong() {
        svc.enable();
        BitcoinSignal s = longSignal(70, 100000);
        s.bbState = "SQUEEZE";
        s.bbWidth = 0.5;
        when(analysisService.getSignal()).thenReturn(s);
        BinanceAutoTradeService.AutoTradeResult r = svc.checkAndTrade();
        assertEquals("skipped", r.status);
        assertTrue(r.message.toLowerCase().contains("squeeze"),
                "Should mention squeeze: " + r.message);
    }

    @Test
    void diagnose_extremeVolatility_volFilterOkFalse() {
        svc.enable();
        BitcoinSignal s = longSignal(70, 100000);
        s.volatilityRegime = "EXTREME";
        s.atrPct = 3.5;
        when(analysisService.getSignal()).thenReturn(s);
        BinanceAutoTradeService.DiagResult d = svc.diagnose();
        assertFalse(d.volFilterOk, "EXTREME volatility should set volFilterOk=false");
        assertFalse(d.wouldTrade);
        assertTrue(d.blockingReason != null && d.blockingReason.contains("EXTREME"),
                "blockingReason should mention EXTREME: " + d.blockingReason);
    }

    @Test
    void checkAndTrade_normalVolatility_doesNotBlock() {
        svc.enable();
        BitcoinSignal s = longSignal(70, 100000);
        s.volatilityRegime = "NORMAL";
        s.bbState = "NORMAL";
        s.extremeCandle = false;
        when(analysisService.getSignal()).thenReturn(s);
        // This will reach the position check (and potentially mock throws)
        // but volatility filter should not block it
        BinanceAutoTradeService.AutoTradeResult r = svc.checkAndTrade();
        assertFalse("skipped".equals(r.status) && r.message != null
                && (r.message.contains("EXTREME") || r.message.contains("squeeze")),
                "Normal volatility should not block: " + r.message);
    }

    @Test
    void checkAndTrade_directionChange_resetsConfirmation() {
        svc.enable();
        // Cycle 1: LONG → confirmation 1/2
        when(analysisService.getSignal()).thenReturn(longSignal(70, 100000));
        BinanceAutoTradeService.AutoTradeResult r1 = svc.checkAndTrade();
        assertEquals("skipped", r1.status);
        assertTrue(r1.message.contains("1/2") || r1.message.contains("confirmation"), "Should be confirmation 1/2: " + r1.message);
        // Cycle 2: direction changes to SHORT → reset to 1/2
        when(analysisService.getSignal()).thenReturn(shortSignal(35, 100000));
        BinanceAutoTradeService.AutoTradeResult r2 = svc.checkAndTrade();
        assertEquals("skipped", r2.status);
        assertTrue(r2.message.contains("1/2") || r2.message.contains("confirmation"), "Direction change should reset to 1/2: " + r2.message);
    }

    @Test
    void checkAndTrade_weakBullTrend4h_doesNotBlockShort() {
        svc.enable();
        // Weak 4h BULL (score=+8, diff barely positive) must NOT block shorts
        BitcoinSignal s = shortSignal(35, 100000);
        s.tf4hBias  = "BULL";
        s.tf4hScore = 8; // weak — |8| < 15, should NOT block
        when(analysisService.getSignal()).thenReturn(s);
        BinanceAutoTradeService.AutoTradeResult r = svc.checkAndTrade();
        assertFalse(r.message != null && r.message.contains("4h") && r.message.contains("BULL"),
                "Weak 4h BULL should not block SHORT: " + r.message);
    }

    // ── Cooldown ──────────────────────────────────────────────────────────────

    @Test
    void checkAndTrade_inCooldown_isSkipped() throws Exception {
        svc.enable();
        setLastTradeAt(Instant.now()); // just traded → cooldown active
        when(analysisService.getSignal()).thenReturn(longSignal(65));
        when(futuresService.getPositionRisk(any())).thenReturn("[{\"positionAmt\":\"0\"}]");

        BinanceAutoTradeService.AutoTradeResult r = svc.checkAndTrade();
        assertEquals("skipped", r.status);
        assertTrue(r.message.contains("cooldown") || r.message.contains("Cooldown"),
                "Expected cooldown message but got: " + r.message);
    }

    @Test
    void checkAndTrade_afterCooldownExpired_executes() throws Exception {
        svc.enable();
        setLastTradeAt(Instant.now().minusMillis(COOLDOWN_MS_FOR_TEST + 1000));
        when(analysisService.getSignal()).thenReturn(longSignal(70));
        when(futuresService.getPositionRisk(any())).thenReturn("[{\"positionAmt\":\"0\"}]");

        svc.checkAndTrade(); // confirmation 1/2
        BinanceAutoTradeService.AutoTradeResult r = svc.checkAndTrade(); // confirmation 2/2 → executes
        assertNotEquals("skipped", r.status, "Trade should execute after cooldown expires");
        assertNotEquals("error", r.status);
    }

    // ── Daily loss limit ──────────────────────────────────────────────────────

    @Test
    void diagnose_dailyLimitOk_whenNoTrades() {
        svc.enable();
        when(analysisService.getSignal()).thenReturn(longSignal(70));
        // With no closed trades in H2, getDailyPnl() returns 0.0 → limit not reached
        BinanceAutoTradeService.DiagResult d = svc.diagnose();
        assertTrue(d.dailyLimitOk, "With no DB trades, daily limit should be OK");
        assertEquals(100.0, d.dailyLossLimit, 0.01);
        assertEquals(0.0, d.dailyPnl, 10.0, "Daily PnL should be near 0 with no trades");
    }

    // ── Emergency close ───────────────────────────────────────────────────────

    @Test
    void emergencyCloseAll_disablesBotAndCancelsOrders() throws Exception {
        svc.enable();
        assertTrue(svc.isEnabled());
        when(futuresService.cancelAllOrders(any())).thenReturn("{}");
        when(futuresService.getPositionRisk(any())).thenReturn("[{\"positionAmt\":\"0\"}]");

        Map<String, Object> result = svc.emergencyCloseAll();

        assertFalse(svc.isEnabled(), "Bot must be disabled after kill switch");
        assertNotNull(result);
        assertEquals("ok", result.get("status"));
    }

    @Test
    void emergencyCloseAll_withApiFailure_returnsPartialStatus() throws Exception {
        svc.enable();
        when(futuresService.cancelAllOrders(any())).thenThrow(new RuntimeException("API error"));
        when(futuresService.getPositionRisk(any())).thenThrow(new RuntimeException("API error"));

        Map<String, Object> result = svc.emergencyCloseAll();

        assertFalse(svc.isEnabled(), "Bot must still be disabled after kill switch even on API failure");
        assertNotNull(result);
        assertEquals("partial", result.get("status"));
    }

    // ── Close position ────────────────────────────────────────────────────────

    @Test
    void closePosition_whenNoActivePosition_throwsException() throws Exception {
        when(futuresService.getPositionRisk(any())).thenReturn("[{\"positionAmt\":\"0\"}]");

        assertThrows(RuntimeException.class, () -> svc.closePosition("BTCUSDT"),
                "Should throw when no position is open");
    }
}
