package com.market.resource;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for SignalHistoryResource (/api/signals).
 * Uses the real H2 DB (test profile) — no mock needed, Panache queries work in-process.
 * The table may be empty in CI but all endpoints must return 200 + valid JSON arrays.
 */
@QuarkusTest
class SignalHistoryResourceTest {

    // ── GET /api/signals/history ──────────────────────────────────────────────

    @Test
    void history_returns200_andArray() {
        given()
            .when().get("/api/signals/history")
            .then()
            .statusCode(200)
            .contentType("application/json")
            .body("$", notNullValue());
    }

    @Test
    void history_defaultLimit50_returns200() {
        given()
            .when().get("/api/signals/history")
            .then()
            .statusCode(200)
            .body("size()", lessThanOrEqualTo(50));
    }

    @Test
    void history_customLimit_respected() {
        given()
            .queryParam("limit", 5)
            .when().get("/api/signals/history")
            .then()
            .statusCode(200)
            .body("size()", lessThanOrEqualTo(5));
    }

    @Test
    void history_limitCappedAt200() {
        // Requesting 9999 must not crash the endpoint
        given()
            .queryParam("limit", 9999)
            .when().get("/api/signals/history")
            .then()
            .statusCode(200)
            .body("size()", lessThanOrEqualTo(200));
    }

    // ── GET /api/signals/history/long ────────────────────────────────────────

    @Test
    void longHistory_returns200_andArray() {
        given()
            .when().get("/api/signals/history/long")
            .then()
            .statusCode(200)
            .contentType("application/json")
            .body("$", notNullValue());
    }

    @Test
    void longHistory_defaultLimit_returns200() {
        given()
            .when().get("/api/signals/history/long")
            .then()
            .statusCode(200)
            .body("size()", lessThanOrEqualTo(50));
    }

    @Test
    void longHistory_customLimit_respected() {
        given()
            .queryParam("limit", 3)
            .when().get("/api/signals/history/long")
            .then()
            .statusCode(200)
            .body("size()", lessThanOrEqualTo(3));
    }

    // ── GET /api/signals/history/short ───────────────────────────────────────

    @Test
    void shortHistory_returns200_andArray() {
        given()
            .when().get("/api/signals/history/short")
            .then()
            .statusCode(200)
            .contentType("application/json")
            .body("$", notNullValue());
    }

    @Test
    void shortHistory_defaultLimit_returns200() {
        given()
            .when().get("/api/signals/history/short")
            .then()
            .statusCode(200)
            .body("size()", lessThanOrEqualTo(50));
    }

    @Test
    void shortHistory_customLimit_respected() {
        given()
            .queryParam("limit", 2)
            .when().get("/api/signals/history/short")
            .then()
            .statusCode(200)
            .body("size()", lessThanOrEqualTo(2));
    }
}
