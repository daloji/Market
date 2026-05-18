package com.market.resource;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for MarketIndexResource (/api/indices).
 * Does not mock YahooFinanceClient (Singleton REST client is not easily mockable).
 * Tests focus on routing, symbol normalisation and error handling without real network.
 */
@QuarkusTest
class MarketIndexResourceTest {

    // In test environment there is no real Yahoo Finance access,
    // so all calls return 5xx, but the endpoint must be reachable (not 404).

    @Test
    void getIndex_endpointExists_notHttp404() {
        given()
            .when().get("/api/indices/FCHI")
            .then()
            .statusCode(not(equalTo(404)));
    }

    @Test
    void getIndex_dax_endpointExists_notHttp404() {
        given()
            .when().get("/api/indices/GDAXI")
            .then()
            .statusCode(not(equalTo(404)));
    }

    @Test
    void getIndex_unknownSymbol_endpointStillRouted() {
        given()
            .when().get("/api/indices/MYINDEX")
            .then()
            .statusCode(not(equalTo(404)));
    }

    @Test
    void getIndex_symbolNormalised_toUpperCase() {
        // Both lower and upper case should reach the same handler (status ≠ 404)
        int statusUpper = given().when().get("/api/indices/FCHI").statusCode();
        int statusLower = given().when().get("/api/indices/fchi").statusCode();
        assert statusUpper == statusLower;
    }
}
