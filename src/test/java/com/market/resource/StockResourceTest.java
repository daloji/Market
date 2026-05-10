package com.market.resource;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.*;

@QuarkusTest
public class StockResourceTest {

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
            .body("{\"symbol\":\"IBM\",\"name\":\"IBM Corp.\"}")
            .when().post("/api/stocks")
            .then()
            .statusCode(201)
            .body("symbol", equalTo("IBM"));

        // Duplicate should return 409
        given()
            .contentType("application/json")
            .body("{\"symbol\":\"IBM\"}")
            .when().post("/api/stocks")
            .then()
            .statusCode(409);

        // Soft-delete
        given()
            .when().delete("/api/stocks/IBM")
            .then()
            .statusCode(204);
    }

    @Test
    public void testRecommendationsEndpoint() {
        given()
            .when().get("/api/recommendations")
            .then()
            .statusCode(200);
    }
}
