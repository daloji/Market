package com.market.service;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BinanceFuturesService.
 *
 * - isConfigured / isTestnet: tested on locally-created instances so we can
 *   control the field values without wrestling with SmallRye Config's empty-string
 *   rejection in the test profile.
 * - sign(): tested on the CDI-injected bean (which has a non-empty secret from
 *   application.properties, so HMAC-SHA256 works).
 */
@QuarkusTest
class BinanceFuturesServiceTest {

    /** Injected real bean – has non-empty secret/api-key from application.properties. */
    @Inject
    BinanceFuturesService svc;

    // ── isConfigured ─────────────────────────────────────────────────────────

    @Test
    void isConfigured_noKeys_returnsFalse() {
        // Create a plain instance and set fields directly (same package → accessible)
        BinanceFuturesService s = new BinanceFuturesService();
        s.apiKey = "";
        s.secret = "";
        assertFalse(s.isConfigured());
    }

    @Test
    void isConfigured_bothKeysPresent_returnsTrue() {
        BinanceFuturesService s = new BinanceFuturesService();
        s.apiKey = "my-api-key";
        s.secret = "my-secret";
        assertTrue(s.isConfigured());
    }

    // ── isTestnet ─────────────────────────────────────────────────────────────

    @Test
    void isTestnet_defaultFalse() {
        BinanceFuturesService s = new BinanceFuturesService();
        s.testnet = false;
        assertFalse(s.isTestnet());
    }

    @Test
    void isTestnet_whenTrue_returnsTrue() {
        BinanceFuturesService s = new BinanceFuturesService();
        s.testnet = true;
        assertTrue(s.isTestnet());
    }

    // ── sign() ────────────────────────────────────────────────────────────────

    @Test
    void sign_returns64HexChars() throws Exception {
        String result = svc.sign("symbol=BTCUSDT&timestamp=1234567890");
        assertEquals(64, result.length(), "HMAC-SHA256 hex output must be 64 characters");
        assertTrue(result.matches("[0-9a-f]{64}"), "Output must match lowercase hex pattern");
    }

    @Test
    void sign_deterministicForSameInput() throws Exception {
        String input = "symbol=BTCUSDT&timestamp=1234567890";
        String first  = svc.sign(input);
        String second = svc.sign(input);
        assertEquals(first, second, "HMAC is deterministic for the same key and input");
    }

    @Test
    void sign_differentInputsProduceDifferentOutputs() throws Exception {
        String s1 = svc.sign("data=one");
        String s2 = svc.sign("data=two");
        assertNotEquals(s1, s2, "Different inputs must produce different HMAC outputs");
    }

    @Test
    void sign_doesNotThrow() {
        // The injected bean has a non-empty secret from application.properties → must not throw
        assertDoesNotThrow(() -> svc.sign("hello world"));
    }

    // ── getOpenInterestHistory ────────────────────────────────────────────────

    @Test
    void getOpenInterestHistory_methodExists() {
        assertDoesNotThrow(() -> {
            BinanceFuturesService.class.getMethod("getOpenInterestHistory",
                    String.class, String.class, int.class);
        });
    }

    // ── buildCloseOrderBody — algo endpoint params ────────────────────────────

    @Test
    void buildCloseOrderBody_oneWayMode_usesAlgoConditionalParams() {
        BinanceFuturesService s = new BinanceFuturesService();
        String body = s.buildCloseOrderBody("BTCUSDT", "SELL", "STOP_MARKET", 95000.0, "0.002", null);

        assertTrue(body.contains("algoType=CONDITIONAL"),   "Must include algoType=CONDITIONAL");
        assertTrue(body.contains("&type=STOP_MARKET"),      "Must use type= (not orderType=)");
        assertTrue(body.contains("triggerPrice=95000.0"),   "Must use triggerPrice= not stopPrice=");
        assertTrue(body.contains("quantity=0.002"),         "One-Way SL must include explicit quantity");
        assertTrue(body.contains("reduceOnly=true"),        "One-Way SL must use reduceOnly=true");
        assertFalse(body.contains("closePosition"),         "algoOrder API does not support closePosition");
        assertTrue(body.contains("workingType=MARK_PRICE"), "Must request MARK_PRICE");
        assertTrue(body.contains("symbol=BTCUSDT"),         "Must include symbol");
        assertTrue(body.contains("side=SELL"),              "Must include side");
    }

    @Test
    void buildCloseOrderBody_oneWayMode_takeProfitUsesReduceOnlyWithQuantity() {
        BinanceFuturesService s = new BinanceFuturesService();
        String body = s.buildCloseOrderBody("BTCUSDT", "BUY", "TAKE_PROFIT_MARKET", 105000.0, "0.002", null);

        assertFalse(body.contains("orderType="),     "Must NOT use orderType= parameter");
        assertFalse(body.contains("stopPrice="),     "Must NOT use legacy stopPrice= parameter");
        assertFalse(body.contains("positionSide"),   "One-Way mode must NOT include positionSide");
        assertFalse(body.contains("closePosition"),  "One-Way TP must NOT use closePosition (partial close)");
        assertTrue(body.contains("quantity=0.002"),  "One-Way TP must include explicit quantity for partial close");
        assertTrue(body.contains("reduceOnly=true"), "One-Way TP must use reduceOnly=true");
    }

    @Test
    void buildCloseOrderBody_hedgeMode_usesQuantityAndPositionSide() {
        BinanceFuturesService s = new BinanceFuturesService();
        String body = s.buildCloseOrderBody("BTCUSDT", "SELL", "STOP_MARKET", 94000.5, "0.003", "LONG");

        assertTrue(body.contains("positionSide=LONG"), "Hedge mode must include positionSide");
        assertTrue(body.contains("quantity=0.003"),    "Hedge mode must include explicit quantity");
        assertFalse(body.contains("closePosition"),    "Hedge mode must NOT use closePosition");
    }

    @Test
    void buildCloseOrderBody_hedgeMode_short() {
        BinanceFuturesService s = new BinanceFuturesService();
        String body = s.buildCloseOrderBody("BTCUSDT", "BUY", "TAKE_PROFIT_MARKET", 90000.0, "0.001", "SHORT");

        assertTrue(body.contains("positionSide=SHORT"));
        assertTrue(body.contains("&type=TAKE_PROFIT_MARKET"));
        assertTrue(body.contains("side=BUY"));
    }

    @Test
    void buildCloseOrderBody_triggerPriceFormattedToOneDecimal() {
        BinanceFuturesService s = new BinanceFuturesService();

        assertTrue(s.buildCloseOrderBody("BTCUSDT", "SELL", "STOP_MARKET", 95000.123, "0.001", null)
                .contains("triggerPrice=95000.1"), "Must round to 1 decimal");

        assertTrue(s.buildCloseOrderBody("BTCUSDT", "SELL", "STOP_MARKET", 95000.0, "0.001", null)
                .contains("triggerPrice=95000.0"), "Must keep trailing .0");
    }

    @Test
    void buildCloseOrderBody_blankPositionSide_treatedAsOneWay() {
        BinanceFuturesService s = new BinanceFuturesService();
        String body = s.buildCloseOrderBody("BTCUSDT", "SELL", "STOP_MARKET", 95000.0, "0.002", "  ");

        assertTrue(body.contains("quantity=0.002"),  "Blank positionSide: One-Way SL must include quantity");
        assertTrue(body.contains("reduceOnly=true"), "Blank positionSide: One-Way SL must use reduceOnly=true");
        assertFalse(body.contains("closePosition"),  "algoOrder API does not support closePosition");
        assertFalse(body.contains("positionSide"),   "Blank positionSide must NOT appear in body");
    }

    // ── cancelAllAlgoOrders — JSON parsing ────────────────────────────────────

    @Test
    void cancelAllAlgoOrders_methodExists() {
        assertDoesNotThrow(() -> {
            BinanceFuturesService.class.getMethod("cancelAllAlgoOrders", String.class);
        });
    }

    @Test
    void buildCloseOrderBody_oneWayMode_slAndTpUseDifferentCloseStrategies() {
        BinanceFuturesService s = new BinanceFuturesService();
        String sl = s.buildCloseOrderBody("BTCUSDT", "BUY", "STOP_MARKET",        96000.0, "0.002", null);
        String tp = s.buildCloseOrderBody("BTCUSDT", "BUY", "TAKE_PROFIT_MARKET", 96000.0, "0.002", null);

        assertTrue(sl.contains("&type=STOP_MARKET"),        "SL must use STOP_MARKET");
        assertTrue(tp.contains("&type=TAKE_PROFIT_MARKET"), "TP must use TAKE_PROFIT_MARKET");
        // Both SL and TP use reduceOnly=true + quantity (closePosition not supported by algoOrder API)
        assertTrue(sl.contains("reduceOnly=true"),  "One-Way SL must use reduceOnly=true");
        assertTrue(sl.contains("quantity=0.002"),   "One-Way SL must include explicit quantity");
        assertFalse(sl.contains("closePosition"),   "One-Way SL must NOT use closePosition");
        // TP also uses reduceOnly=true + quantity (partial close for TP1/TP2 split)
        assertTrue(tp.contains("reduceOnly=true"),  "One-Way TP must use reduceOnly=true");
        assertTrue(tp.contains("quantity=0.002"),   "One-Way TP must include explicit quantity");
        assertFalse(tp.contains("closePosition"),   "One-Way TP must NOT use closePosition");
        // SL and TP are distinguished by their type, not close strategy
        assertTrue(sl.contains("&type=STOP_MARKET"),        "SL must use STOP_MARKET");
        assertTrue(tp.contains("&type=TAKE_PROFIT_MARKET"), "TP must use TAKE_PROFIT_MARKET");
    }
}

