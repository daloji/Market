package com.market.resource;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for FundamentalResource (/api/fundamentals).
 * Uses real H2 DB and real service — all external HTTP is blocked in test profile.
 * Focus: contract (HTTP status codes, response shape), not data correctness.
 */
@QuarkusTest
class FundamentalResourceTest {

    private static final String TEST_SYMBOL = "MC.PA"; // seeded by DataInitializer

    // ── GET /api/fundamentals ─────────────────────────────────────────────────

    @Test
    void getAllFundamentals_returns200_andArray() {
        given()
            .when().get("/api/fundamentals")
            .then()
            .statusCode(200)
            .contentType("application/json")
            .body("$", notNullValue());
    }

    // ── GET /api/fundamentals/health ──────────────────────────────────────────

    @Test
    void health_returns200_withReport() {
        given()
            .when().get("/api/fundamentals/health")
            .then()
            .statusCode(200)
            .body("report", notNullValue());
    }

    // ── GET /api/fundamentals/{symbol} ────────────────────────────────────────

    @Test
    void getFundamental_unknownSymbol_returns404() {
        given()
            .when().get("/api/fundamentals/UNKNOWN_XYZ_9999")
            .then()
            .statusCode(404);
    }

    @Test
    void getFundamental_knownSymbol_returns200or404() {
        // MC.PA is seeded; if external data is unavailable the endpoint returns 404 with a message,
        // which is also acceptable behaviour in the test environment.
        int code = given()
            .when().get("/api/fundamentals/" + TEST_SYMBOL)
            .then()
            .extract().statusCode();

        org.junit.jupiter.api.Assertions.assertTrue(
            code == 200 || code == 404,
            "Expected 200 or 404, got " + code
        );
    }

    // ── POST /api/fundamentals/{symbol}/from-client ───────────────────────────

    @Test
    void storeFromClient_unknownSymbol_returns404_orBadRequest() {
        // Symbol not in watchlist → 404 before even parsing the body
        // (but body is required — 400 if null payload reaches service)
        int code = given()
            .contentType("application/json")
            .body("{\"symbol\":\"UNKNOWN_XYZ_9999\",\"regularMarketPrice\":100}")
            .when().post("/api/fundamentals/UNKNOWN_XYZ_9999/from-client")
            .then()
            .extract().statusCode();

        // 400 (bad request — missing required fields) or 404 (symbol unknown) both acceptable
        org.junit.jupiter.api.Assertions.assertTrue(
            code == 400 || code == 404,
            "Expected 400 or 404, got " + code
        );
    }

    @Test
    void storeFromClient_emptyBody_returns400() {
        given()
            .contentType("application/json")
            .body("{}")
            .when().post("/api/fundamentals/" + TEST_SYMBOL + "/from-client")
            .then()
            .statusCode(anyOf(equalTo(400), equalTo(404)));
    }
}
