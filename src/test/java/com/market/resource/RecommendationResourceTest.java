package com.market.resource;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class RecommendationResourceTest {

    @Test
    void getAllRecommendations_returns200AndArray() {
        given().when().get("/api/recommendations")
            .then()
            .statusCode(200)
            .contentType("application/json")
            .body("$", notNullValue());
    }

    @Test
    void getBuyRecommendations_returns200AndArray() {
        given().when().get("/api/recommendations/buy")
            .then()
            .statusCode(200)
            .body("$", notNullValue());
    }

    @Test
    void getSellRecommendations_returns200AndArray() {
        given().when().get("/api/recommendations/sell")
            .then()
            .statusCode(200)
            .body("$", notNullValue());
    }

    @Test
    void getByUnknownSymbol_returns404() {
        given().when().get("/api/recommendations/UNKNOWN_XYZ_999")
            .then()
            .statusCode(404);
    }

    @Test
    void getHistoryForUnknownSymbol_returns200AndEmptyArray() {
        given().when().get("/api/recommendations/UNKNOWN_XYZ_999/history")
            .then()
            .statusCode(200)
            .body("size()", equalTo(0));
    }
}
