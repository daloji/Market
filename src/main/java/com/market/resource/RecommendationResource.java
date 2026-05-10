package com.market.resource;

import com.market.model.RecommendationSignal;
import com.market.model.StockRecommendation;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

@Path("/api/recommendations")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Recommendations", description = "Investment recommendations based on technical analysis")
public class RecommendationResource {

    @GET
    @Operation(summary = "Latest recommendation for every watched stock")
    public List<StockRecommendation> getAll() {
        return StockRecommendation.findAllLatest();
    }

    @GET
    @Path("/buy")
    @Operation(summary = "Stocks currently recommended for buying (score ≥ 65)")
    public List<StockRecommendation> getBuys() {
        return StockRecommendation.findBySignal(RecommendationSignal.BUY);
    }

    @GET
    @Path("/sell")
    @Operation(summary = "Stocks currently recommended for selling (score ≤ 35)")
    public List<StockRecommendation> getSells() {
        return StockRecommendation.findBySignal(RecommendationSignal.SELL);
    }

    @GET
    @Path("/{symbol}")
    @Operation(summary = "Latest recommendation for one stock")
    public Response getForSymbol(@PathParam("symbol") String symbol) {
        StockRecommendation rec =
                StockRecommendation.findLatestBySymbol(symbol.toUpperCase());
        if (rec == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("No recommendation available for " + symbol).build();
        }
        return Response.ok(rec).build();
    }

    @GET
    @Path("/{symbol}/history")
    @Operation(summary = "Recommendation history for one stock (newest first)")
    public List<StockRecommendation> getHistory(
            @PathParam("symbol") String symbol,
            @QueryParam("limit") @DefaultValue("20") int limit) {

        return StockRecommendation
                .find("symbol = ?1 order by timestamp desc", symbol.toUpperCase())
                .page(0, limit)
                .list();
    }
}
