package com.market.resource;

import com.market.service.BinanceFuturesService;
import com.market.service.BinanceScalpingTradeService;
import com.market.service.BinanceScalpingTradeService.ScalpResult;
import com.market.service.BinanceScalpingTradeService.ScalpDiag;
import com.market.service.BinanceScalpingTradeService.ScalpTrade;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for ScalpingResource (/api/scalping).
 * BinanceScalpingTradeService and BinanceFuturesService are mocked — no real HTTP.
 */
@QuarkusTest
class ScalpingResourceTest {

    @InjectMock
    BinanceScalpingTradeService scalping;

    @InjectMock
    BinanceFuturesService futures;

    @BeforeEach
    void setup() {
        when(futures.isConfigured()).thenReturn(false);
        when(scalping.statusMap()).thenReturn(Map.of(
            "enabled",       false,
            "configured",    false,
            "minConfidence", 65,
            "amountUsdt",    20.0,
            "leverage",      10,
            "tpPct",         0.3,
            "slPct",         0.15
        ));
        when(scalping.isEnabled()).thenReturn(false);
        when(scalping.history()).thenReturn(List.of());
    }

    // ── GET /api/scalping/status ──────────────────────────────────────────────

    @Test
    void getStatus_returnsAllConfigFields() {
        given()
            .when().get("/api/scalping/status")
            .then()
            .statusCode(200)
            .body("enabled",       equalTo(false))
            .body("configured",    equalTo(false))
            .body("minConfidence", equalTo(65))
            .body("amountUsdt",    equalTo(20.0f))
            .body("leverage",      equalTo(10))
            .body("tpPct",         equalTo(0.3f))
            .body("slPct",         equalTo(0.15f));
    }

    @Test
    void getStatus_withActivePosition_exposesPositionFields() {
        when(scalping.statusMap()).thenReturn(Map.of(
            "enabled",     true,
            "configured",  true,
            "activeDir",   "LONG",
            "activeEntry", 95_000.0,
            "activeTp",    95_285.0,
            "activeSl",    94_857.5,
            "activeQty",   0.002
        ));

        given()
            .when().get("/api/scalping/status")
            .then()
            .statusCode(200)
            .body("activeDir",   equalTo("LONG"))
            .body("activeEntry", equalTo(95_000.0f))
            .body("activeQty",   equalTo(0.002f));
    }

    // ── POST /api/scalping/enable ─────────────────────────────────────────────

    @Test
    void enable_notConfigured_returns400() {
        when(futures.isConfigured()).thenReturn(false);

        given()
            .contentType("application/json")
            .when().post("/api/scalping/enable")
            .then()
            .statusCode(400)
            .body("error", containsString("non configurée"));
    }

    @Test
    void enable_configured_enables_andRunsFirstCheck() {
        when(futures.isConfigured()).thenReturn(true);
        when(scalping.checkAndTrade()).thenReturn(skipped("first"));

        given()
            .contentType("application/json")
            .when().post("/api/scalping/enable")
            .then()
            .statusCode(200)
            .body("enabled", equalTo(true))
            .body("message", containsString("activé"));

        verify(scalping).enable();
        verify(scalping).checkAndTrade();
    }

    // ── POST /api/scalping/disable ────────────────────────────────────────────

    @Test
    void disable_returns200_andDisables() {
        given()
            .contentType("application/json")
            .when().post("/api/scalping/disable")
            .then()
            .statusCode(200)
            .body("enabled", equalTo(false));

        verify(scalping).disable();
    }

    // ── POST /api/scalping/trigger ────────────────────────────────────────────

    @Test
    void trigger_callsCheckAndTrade_andReturnsResult() {
        when(scalping.checkAndTrade()).thenReturn(skipped("désactivé"));

        given()
            .contentType("application/json")
            .when().post("/api/scalping/trigger")
            .then()
            .statusCode(200)
            .body("status", equalTo("skipped"));

        verify(scalping).checkAndTrade();
    }

    @Test
    void trigger_placedResult_returnsPlaced() {
        ScalpResult r = new ScalpResult();
        r.status      = "placed";
        r.direction   = "LONG";
        r.confidence  = 80;
        r.entryPrice  = 95_000.0;
        r.tpPrice     = 95_285.0;
        r.slPrice     = 94_857.5;
        r.message     = "LONG @ 95000";
        when(scalping.checkAndTrade()).thenReturn(r);

        given()
            .contentType("application/json")
            .when().post("/api/scalping/trigger")
            .then()
            .statusCode(200)
            .body("status",    equalTo("placed"))
            .body("direction", equalTo("LONG"))
            .body("confidence",equalTo(80));
    }

    // ── POST /api/scalping/force/{direction} ──────────────────────────────────

    @Test
    void force_invalidDirection_returns400() {
        given()
            .contentType("application/json")
            .when().post("/api/scalping/force/INVALID")
            .then()
            .statusCode(400)
            .body("error", containsString("LONG or SHORT"));
    }

    @Test
    void force_long_callsForceExecute() {
        when(scalping.forceExecute("LONG")).thenReturn(placed("LONG"));

        given()
            .contentType("application/json")
            .when().post("/api/scalping/force/LONG")
            .then()
            .statusCode(200)
            .body("status", equalTo("placed"))
            .body("direction", equalTo("LONG"));

        verify(scalping).forceExecute("LONG");
    }

    @Test
    void force_short_callsForceExecute() {
        when(scalping.forceExecute("SHORT")).thenReturn(placed("SHORT"));

        given()
            .contentType("application/json")
            .when().post("/api/scalping/force/SHORT")
            .then()
            .statusCode(200)
            .body("status",    equalTo("placed"))
            .body("direction", equalTo("SHORT"));
    }

    // ── GET /api/scalping/diagnose ────────────────────────────────────────────

    @Test
    void diagnose_returnsAllDiagFields() {
        ScalpDiag d = new ScalpDiag();
        d.enabled        = false;
        d.configured     = false;
        d.wouldTrade     = false;
        d.blockingReason = "Auto-scalping désactivé";
        when(scalping.diagnose()).thenReturn(d);

        given()
            .when().get("/api/scalping/diagnose")
            .then()
            .statusCode(200)
            .body("enabled",        equalTo(false))
            .body("wouldTrade",     equalTo(false))
            .body("blockingReason", containsString("désactivé"));
    }

    @Test
    void diagnose_wouldTrade_whenAllGatesPass() {
        ScalpDiag d = new ScalpDiag();
        d.enabled          = true;
        d.configured       = true;
        d.cooldownOk       = true;
        d.directionOk      = true;
        d.confidenceOk     = true;
        d.binancePosOk     = true;
        d.wouldTrade       = true;
        d.blockingReason   = null;
        d.signalDirection  = "LONG";
        d.signalConfidence = 80;
        when(scalping.diagnose()).thenReturn(d);

        given()
            .when().get("/api/scalping/diagnose")
            .then()
            .statusCode(200)
            .body("wouldTrade",       equalTo(true))
            .body("blockingReason",   nullValue())
            .body("signalDirection",  equalTo("LONG"))
            .body("signalConfidence", equalTo(80));
    }

    // ── GET /api/scalping/history ─────────────────────────────────────────────

    @Test
    void history_empty_returnsEmptyArray() {
        given()
            .when().get("/api/scalping/history")
            .then()
            .statusCode(200)
            .body("size()", equalTo(0));
    }

    @Test
    void history_withTrades_returnsCorrectFields() {
        ScalpTrade t = new ScalpTrade();
        t.direction  = "SHORT";
        t.entryPrice = 98_000.0;
        t.tpPrice    = 97_706.0;
        t.slPrice    = 98_147.0;
        t.confidence = 75;
        t.status     = "TP";
        t.pnl        = 0.58;
        when(scalping.history()).thenReturn(List.of(t));

        given()
            .when().get("/api/scalping/history")
            .then()
            .statusCode(200)
            .body("size()",         equalTo(1))
            .body("[0].direction",  equalTo("SHORT"))
            .body("[0].status",     equalTo("TP"))
            .body("[0].confidence", equalTo(75));
    }

    // ── POST /api/scalping/config ─────────────────────────────────────────────

    @Test
    void config_nullBody_returns400() {
        given()
            .contentType("application/json")
            .when().post("/api/scalping/config")
            .then()
            .statusCode(400);
    }

    @Test
    void config_emptyObject_returns200_noSettersCalled() {
        given()
            .contentType("application/json")
            .body("{}")
            .when().post("/api/scalping/config")
            .then()
            .statusCode(200);

        verify(scalping, never()).setMinConfidence(anyInt());
        verify(scalping, never()).setAmountUsdt(anyDouble());
        verify(scalping, never()).setLeverage(anyInt());
    }

    @Test
    void config_withAllValues_callsAllSetters() {
        given()
            .contentType("application/json")
            .body("{\"minConfidence\":70,\"amountUsdt\":50,\"leverage\":15,\"tpPct\":0.5,\"slPct\":0.25}")
            .when().post("/api/scalping/config")
            .then()
            .statusCode(200);

        verify(scalping).setMinConfidence(70);
        verify(scalping).setAmountUsdt(50.0);
        verify(scalping).setLeverage(15);
        verify(scalping).setTpPct(0.5);
        verify(scalping).setSlPct(0.25);
    }

    @Test
    void config_zeroValues_notForwarded_settersNotCalled() {
        given()
            .contentType("application/json")
            .body("{\"minConfidence\":0,\"amountUsdt\":0,\"leverage\":0}")
            .when().post("/api/scalping/config")
            .then()
            .statusCode(200);

        verify(scalping, never()).setMinConfidence(anyInt());
        verify(scalping, never()).setAmountUsdt(anyDouble());
        verify(scalping, never()).setLeverage(anyInt());
    }

    // ── GET /api/scalping/orders (Binance open orders) ────────────────────────

    @Test
    void openOrders_notConfigured_returnsError() throws Exception {
        when(futures.isConfigured()).thenReturn(true);
        when(futures.getOpenOrders("BTCUSDT"))
            .thenThrow(new RuntimeException("Binance API non configurée"));

        given()
            .when().get("/api/scalping/orders")
            .then()
            .statusCode(500)
            .body("error", containsString("non configurée"));
    }

    @Test
    void openOrders_configured_returnsJson() throws Exception {
        when(futures.isConfigured()).thenReturn(true);
        when(futures.getOpenOrders("BTCUSDT")).thenReturn("[]");

        given()
            .when().get("/api/scalping/orders")
            .then()
            .statusCode(200);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static ScalpResult skipped(String msg) {
        ScalpResult r = new ScalpResult();
        r.status  = "skipped";
        r.message = msg;
        return r;
    }

    private static ScalpResult placed(String dir) {
        ScalpResult r = new ScalpResult();
        r.status     = "placed";
        r.direction  = dir;
        r.confidence = 80;
        r.entryPrice = 95_000.0;
        r.tpPrice    = 95_285.0;
        r.slPrice    = 94_857.5;
        r.message    = dir + " @ 95000";
        return r;
    }
}
