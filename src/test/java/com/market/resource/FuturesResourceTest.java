package com.market.resource;

import com.market.service.BinanceAutoTradeService;
import com.market.service.BinanceFuturesService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for FuturesResource (/api/futures) using @InjectMock for both services.
 */
@QuarkusTest
class FuturesResourceTest {

    @InjectMock
    BinanceAutoTradeService autoTrade;

    @InjectMock
    BinanceFuturesService futures;

    @BeforeEach
    void setup() {
        when(futures.isConfigured()).thenReturn(false);
        when(futures.isTestnet()).thenReturn(false);
        when(autoTrade.isEnabled()).thenReturn(false);
        when(autoTrade.getMinConfidence()).thenReturn(60);
        when(autoTrade.getAmountUsdt()).thenReturn(50.0);
        when(autoTrade.getLeverage()).thenReturn(10);
        when(autoTrade.getSlPct()).thenReturn(1.5);
        when(autoTrade.getTpPct()).thenReturn(3.0);
        when(autoTrade.lastResult()).thenReturn(null);
    }

    // ── /status ───────────────────────────────────────────────────────────────

    @Test
    void getStatus_returnsAllExpectedFields() {
        given()
            .when().get("/api/futures/status")
            .then()
            .statusCode(200)
            .body("configured",    equalTo(false))
            .body("testnet",       equalTo(false))
            .body("enabled",       equalTo(false))
            .body("minConfidence", equalTo(60))
            .body("amountUsdt",    equalTo(50.0f))
            .body("leverage",      equalTo(10))
            .body("slPct",         equalTo(1.5f))
            .body("tpPct",         equalTo(3.0f));
    }

    @Test
    void getStatus_configured_returnsTrue() {
        when(futures.isConfigured()).thenReturn(true);
        when(autoTrade.isEnabled()).thenReturn(true);

        given()
            .when().get("/api/futures/status")
            .then()
            .statusCode(200)
            .body("configured", equalTo(true))
            .body("enabled",    equalTo(true));
    }

    // ── /enable ───────────────────────────────────────────────────────────────

    @Test
    void enable_notConfigured_returns400() {
        when(futures.isConfigured()).thenReturn(false);

        given()
            .contentType("application/json")
            .when().post("/api/futures/enable")
            .then()
            .statusCode(400)
            .body("error", containsString("non configurée"));
    }

    @Test
    void enable_configured_returns200() {
        when(futures.isConfigured()).thenReturn(true);
        when(autoTrade.checkAndTrade()).thenReturn(skippedResult("first check"));

        given()
            .contentType("application/json")
            .when().post("/api/futures/enable")
            .then()
            .statusCode(200)
            .body("enabled", equalTo(true))
            .body("message", containsString("activé"));

        verify(autoTrade).enable();
        verify(autoTrade).checkAndTrade();
    }

    // ── /disable ──────────────────────────────────────────────────────────────

    @Test
    void disable_returns200() {
        given()
            .contentType("application/json")
            .when().post("/api/futures/disable")
            .then()
            .statusCode(200)
            .body("enabled", equalTo(false));

        verify(autoTrade).disable();
    }

    // ── /trigger ──────────────────────────────────────────────────────────────

    @Test
    void trigger_callsCheckAndTrade() {
        when(autoTrade.checkAndTrade()).thenReturn(skippedResult("manual trigger"));

        given()
            .contentType("application/json")
            .when().post("/api/futures/trigger")
            .then()
            .statusCode(200);

        verify(autoTrade).checkAndTrade();
    }

    @Test
    void trigger_returnsCheckResult_skipped() {
        when(autoTrade.checkAndTrade()).thenReturn(skippedResult("test"));

        given()
            .contentType("application/json")
            .when().post("/api/futures/trigger")
            .then()
            .statusCode(200)
            .body("status", equalTo("skipped"));
    }

    // ── /config ───────────────────────────────────────────────────────────────

    @Test
    void config_empty_returns200() {
        given()
            .contentType("application/json")
            .body("{}")
            .when().post("/api/futures/config")
            .then()
            .statusCode(200);
    }

    @Test
    void config_withValues_callsSetters() {
        given()
            .contentType("application/json")
            .body("{\"minConfidence\":55,\"amountUsdt\":100,\"leverage\":5,\"slPct\":2.0,\"tpPct\":4.0}")
            .when().post("/api/futures/config")
            .then()
            .statusCode(200);

        verify(autoTrade).setMinConfidence(55);
        verify(autoTrade).setAmountUsdt(100.0);
        verify(autoTrade).setLeverage(5);
    }

    // ── /diagnose ─────────────────────────────────────────────────────────────

    @Test
    void diagnose_returnsAllFields() {
        BinanceAutoTradeService.DiagResult diag = new BinanceAutoTradeService.DiagResult();
        diag.enabled       = false;
        diag.wouldTrade    = false;
        diag.blockingReason = "Auto-trade désactivé";
        when(autoTrade.diagnose()).thenReturn(diag);

        given()
            .when().get("/api/futures/diagnose")
            .then()
            .statusCode(200)
            .body("enabled",        equalTo(false))
            .body("wouldTrade",     equalTo(false))
            .body("blockingReason", containsString("désactivé"));
    }

    // ── /positions ────────────────────────────────────────────────────────────

    @Test
    void positions_notConfigured_returns400() {
        when(futures.isConfigured()).thenReturn(false);

        given()
            .when().get("/api/futures/positions")
            .then()
            .statusCode(400);
    }

    @Test
    void positions_configured_emptyPositions_returnsEmptyList() throws Exception {
        when(futures.isConfigured()).thenReturn(true);
        when(futures.getPositionRisk("BTCUSDT")).thenReturn(
            "[{\"positionAmt\":\"0.000\",\"entryPrice\":\"0\",\"unRealizedProfit\":\"0\"," +
            "\"liquidationPrice\":\"0\",\"leverage\":\"10\",\"symbol\":\"BTCUSDT\"," +
            "\"markPrice\":\"0\",\"notional\":\"0\"}]"
        );

        given()
            .when().get("/api/futures/positions")
            .then()
            .statusCode(200)
            .body("size()", equalTo(0)); // positionAmt≈0 → filtered out
    }

    @Test
    void positions_configured_withOpenLong_returnsPosition() throws Exception {
        when(futures.isConfigured()).thenReturn(true);
        when(futures.getPositionRisk("BTCUSDT")).thenReturn(
            "[{\"symbol\":\"BTCUSDT\",\"positionAmt\":\"0.050\",\"entryPrice\":\"98000\"," +
            "\"unRealizedProfit\":\"100\",\"liquidationPrice\":\"90000\",\"leverage\":\"10\"," +
            "\"markPrice\":\"100000\",\"notional\":\"5000\"}]"
        );

        given()
            .when().get("/api/futures/positions")
            .then()
            .statusCode(200)
            .body("size()",        equalTo(1))
            .body("[0].symbol",    equalTo("BTCUSDT"))
            .body("[0].direction", equalTo("LONG"));
    }

    // ── /emergency-close ──────────────────────────────────────────────────────

    @Test
    void emergencyClose_notConfigured_returns400() {
        when(futures.isConfigured()).thenReturn(false);

        given()
            .contentType("application/json")
            .when().post("/api/futures/emergency-close")
            .then()
            .statusCode(400);
    }

    @Test
    void emergencyClose_configured_returns200() throws Exception {
        when(futures.isConfigured()).thenReturn(true);
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("status", "ok");
        result.put("message", "Aucune position ouverte — bot désactivé");
        when(autoTrade.emergencyCloseAll()).thenReturn(result);

        given()
            .contentType("application/json")
            .when().post("/api/futures/emergency-close")
            .then()
            .statusCode(200)
            .body("status", equalTo("ok"));
    }

    // ── /close-position ───────────────────────────────────────────────────────

    @Test
    void closePosition_notConfigured_returns400() {
        when(futures.isConfigured()).thenReturn(false);

        given()
            .contentType("application/json")
            .when().post("/api/futures/close-position")
            .then()
            .statusCode(400);
    }

    @Test
    void closePosition_configured_returns200() throws Exception {
        when(futures.isConfigured()).thenReturn(true);
        java.util.Map<String, Object> result = new java.util.HashMap<>();
        result.put("status", "ok");
        result.put("message", "Position fermée");
        when(autoTrade.closePosition(any())).thenReturn(result);

        given()
            .contentType("application/json")
            .when().post("/api/futures/close-position")
            .then()
            .statusCode(200)
            .body("status", equalTo("ok"));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private BinanceAutoTradeService.AutoTradeResult skippedResult(String msg) {
        BinanceAutoTradeService.AutoTradeResult r = new BinanceAutoTradeService.AutoTradeResult();
        r.status  = "skipped";
        r.message = msg;
        return r;
    }
}
