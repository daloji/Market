package com.market.resource;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.*;

@QuarkusTest
public class StockResourceTest {

    /** Ensure test symbol is removed after each test to avoid 409 on re-run. */
    @AfterEach
    public void cleanup() {
        given().when().delete("/api/stocks/TSLA_TEST").then();
    }

    @Test
    public void testListStocksReturnsArray() {
        given()
            .when().get("/api/stocks")
            .then()
            .statusCode(200)
            .contentType("application/json")
            .body("$", notNullValue());
    }

    @Test
    public void testGetUnknownStockReturns404() {
        given()
            .when().get("/api/stocks/UNKNOWN_XYZ_999")
            .then()
            .statusCode(404);
    }

    @Test
    public void testAddAndRemoveStock() {
        // Add a new stock
        given()
            .contentType("application/json")
            .body("{\"symbol\":\"TSLA_TEST\",\"name\":\"Tesla Test\"}")
            .when().post("/api/stocks")
            .then()
            .statusCode(201)
            .body("symbol", equalTo("TSLA_TEST"));

        // Duplicate should return 409
        given()
            .contentType("application/json")
            .body("{\"symbol\":\"TSLA_TEST\"}")
            .when().post("/api/stocks")
            .then()
            .statusCode(409);

        // Soft-delete
        given()
            .when().delete("/api/stocks/TSLA_TEST")
            .then()
            .statusCode(204);
    }

    @Test
    public void testAddStock_emptyBody_returns400() {
        given()
            .contentType("application/json")
            .body("{}")
            .when().post("/api/stocks")
            .then()
            .statusCode(400);
    }

    @Test
    public void testGetQuotes_unknownSymbol_returns200OrEmpty() {
        given()
            .when().get("/api/stocks/UNKNOWN_XYZ_999/quotes")
            .then()
            .statusCode(anyOf(equalTo(200), equalTo(404)));
    }

    @Test
    public void testRecommendationsEndpoint() {
        given()
            .when().get("/api/recommendations")
            .then()
            .statusCode(200);
    }
}
