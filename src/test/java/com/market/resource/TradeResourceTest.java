package com.market.resource;

import com.market.model.Trade;
import com.market.service.TradeService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class TradeResourceTest {

    @Inject
    TradeService tradeService;

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

    // ─── POST /api/trades (simulation) ────────────────────────────────────────

    @Test
    void postTrade_valid_returns200_andSimulation() {
        Number id = given()
            .contentType("application/json")
            .body("""
                {"amount":100,"entryPrice":50000,"direction":"LONG","leverage":10}
                """)
            .when().post("/api/trades")
            .then()
            .statusCode(200)
            .body("tradeType", equalTo("SIMULATION"))
            .body("direction", equalTo("LONG"))
            .body("status", equalTo("OPEN"))
            .extract().path("id");
        createdIds.add(id.longValue());
    }

    @Test
    void postTrade_missingAmount_returns400() {
        given()
            .contentType("application/json")
            .body("""
                {"entryPrice":50000,"direction":"LONG"}
                """)
            .when().post("/api/trades")
            .then()
            .statusCode(400);
    }

    @Test
    void postTrade_invalidDirection_returns400() {
        given()
            .contentType("application/json")
            .body("""
                {"amount":100,"entryPrice":50000,"direction":"SIDEWAYS"}
                """)
            .when().post("/api/trades")
            .then()
            .statusCode(400);
    }

    @Test
    void postTrade_withCustomSlTp_stored() {
        Number id = given()
            .contentType("application/json")
            .body("""
                {"amount":100,"entryPrice":50000,"direction":"LONG",
                 "leverage":10,"sl":49000,"tp":55000}
                """)
            .when().post("/api/trades")
            .then()
            .statusCode(200)
            .body("sl", equalTo(49000.0f))
            .body("tp1", equalTo(55000.0f))
            .extract().path("id");
        createdIds.add(id.longValue());
    }

    // ─── POST /api/trades/real ─────────────────────────────────────────────────

    @Test
    void postRealTrade_valid_returns200_andReal() {
        Number id = given()
            .contentType("application/json")
            .body("""
                {"amount":500,"entryPrice":48000,"direction":"SHORT",
                 "leverage":5,"broker":"Binance","symbol":"BTC/USDT"}
                """)
            .when().post("/api/trades/real")
            .then()
            .statusCode(200)
            .body("tradeType", equalTo("REAL"))
            .body("broker", equalTo("Binance"))
            .body("symbol", equalTo("BTC/USDT"))
            .extract().path("id");
        createdIds.add(id.longValue());
    }

    @Test
    void postRealTrade_missingEntryPrice_returns400() {
        given()
            .contentType("application/json")
            .body("""
                {"amount":100,"direction":"LONG","broker":"Bybit"}
                """)
            .when().post("/api/trades/real")
            .then()
            .statusCode(400);
    }

    // ─── GET active / history endpoints ───────────────────────────────────────

    @Test
    void getActiveTrades_returnsArray_andOnlySimulation() {
        // Open one sim and one real trade
        long simId = openSim(100, "LONG", 50000);
        long realId = openReal(100, "LONG", 50000, "Bybit");
        createdIds.add(simId);
        createdIds.add(realId);

        given().when().get("/api/trades/active")
            .then()
            .statusCode(200)
            .body("findAll { it.tradeType == 'REAL' }.size()", equalTo(0))
            .body("findAll { it.tradeType == 'SIMULATION' }.size()", greaterThan(0));
    }

    @Test
    void getRealActive_returnsArray_andOnlyReal() {
        long simId  = openSim(100, "LONG", 50000);
        long realId = openReal(200, "SHORT", 49000, "Binance");
        createdIds.add(simId);
        createdIds.add(realId);

        given().when().get("/api/trades/real/active")
            .then()
            .statusCode(200)
            .body("findAll { it.tradeType == 'SIMULATION' }.size()", equalTo(0))
            .body("findAll { it.tradeType == 'REAL' }.size()", greaterThan(0));
    }

    @Test
    void getHistory_returns200() {
        given().when().get("/api/trades/history")
            .then().statusCode(200);
    }

    @Test
    void getRealHistory_returns200() {
        given().when().get("/api/trades/real/history")
            .then().statusCode(200);
    }

    @Test
    void getAllHistory_returns200() {
        given().when().get("/api/trades/all/history")
            .then().statusCode(200);
    }

    // ─── GET /{id} ─────────────────────────────────────────────────────────────

    @Test
    void getById_existing_returns200() {
        long id = openSim(100, "LONG", 50000);
        createdIds.add(id);

        given().when().get("/api/trades/" + id)
            .then()
            .statusCode(200)
            .body("id", equalTo((int) id));
    }

    @Test
    void getById_nonExistent_returns404() {
        given().when().get("/api/trades/999999999")
            .then().statusCode(404);
    }

    // ─── DELETE /{id} (close) ──────────────────────────────────────────────────

    @Test
    void deleteTrade_closes_withUserClosedReason() {
        long id = openSim(100, "LONG", 50000);
        createdIds.add(id);

        given().when().delete("/api/trades/" + id)
            .then()
            .statusCode(200)
            .body("status", equalTo("CLOSED"))
            .body("closeReason", equalTo("USER_CLOSED"));
    }

    @Test
    void deleteTrade_real_withClosePrice_recalculatesPnl() {
        long id = openReal(100, "LONG", 50000, "Binance");
        createdIds.add(id);

        // Close at 51000 → pnlUsd = 100*(1000/50000)*10 = 20
        given().when().delete("/api/trades/" + id + "?closePrice=51000")
            .then()
            .statusCode(200)
            .body("status", equalTo("CLOSED"))
            .body("pnlUsd", equalTo(20.0f));
    }

    @Test
    void deleteTrade_nonExistent_returns404() {
        given().when().delete("/api/trades/999999999")
            .then().statusCode(404);
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    private long openSim(double amount, String direction, double entryPrice) {
        return ((Number) given()
            .contentType("application/json")
            .body(String.format(
                "{\"amount\":%.0f,\"entryPrice\":%.0f,\"direction\":\"%s\",\"leverage\":10}",
                amount, entryPrice, direction))
            .when().post("/api/trades")
            .then().statusCode(200)
            .extract().path("id")).longValue();
    }

    private long openReal(double amount, String direction, double entryPrice, String broker) {
        return ((Number) given()
            .contentType("application/json")
            .body(String.format(
                "{\"amount\":%.0f,\"entryPrice\":%.0f,\"direction\":\"%s\",\"leverage\":10,\"broker\":\"%s\"}",
                amount, entryPrice, direction, broker))
            .when().post("/api/trades/real")
            .then().statusCode(200)
            .extract().path("id")).longValue();
    }
}
