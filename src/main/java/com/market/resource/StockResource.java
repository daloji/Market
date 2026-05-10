package com.market.resource;

import com.market.model.Stock;
import com.market.model.StockQuote;
import com.market.service.RecommendationService;
import com.market.service.StockDataService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

@Path("/api/stocks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Stocks", description = "Manage the watched-stock list")
public class StockResource {

    @Inject StockDataService stockDataService;
    @Inject RecommendationService recommendationService;

    @GET
    @Operation(summary = "List all watched stocks")
    public List<Stock> listAll() {
        return Stock.listAll();
    }

    @GET
    @Path("/{symbol}")
    @Operation(summary = "Get details of one stock")
    public Response getStock(@PathParam("symbol") String symbol) {
        Stock stock = Stock.findBySymbol(symbol.toUpperCase());
        if (stock == null) return Response.status(Response.Status.NOT_FOUND).build();
        return Response.ok(stock).build();
    }

    @POST
    @Transactional
    @Operation(summary = "Add a stock to the watchlist")
    public Response addStock(StockRequest req) {
        if (req == null || req.symbol == null || req.symbol.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("symbol is required").build();
        }
        String symbol = req.symbol.toUpperCase();

        if (Stock.findBySymbol(symbol) != null) {
            return Response.status(Response.Status.CONFLICT)
                    .entity("Stock already in watchlist").build();
        }

        Stock stock = new Stock();
        stock.symbol = symbol;
        stock.name   = req.name != null ? req.name : symbol;
        stock.active = true;
        stock.persist();

        // Kick off an immediate analysis for the new stock
        stockDataService.fetchAndStoreQuotes(symbol);
        recommendationService.generateRecommendation(symbol);

        return Response.status(Response.Status.CREATED).entity(stock).build();
    }

    @DELETE
    @Path("/{symbol}")
    @Transactional
    @Operation(summary = "Remove a stock from the watchlist (soft-delete)")
    public Response removeStock(@PathParam("symbol") String symbol) {
        Stock stock = Stock.findBySymbol(symbol.toUpperCase());
        if (stock == null) return Response.status(Response.Status.NOT_FOUND).build();
        stock.active = false;
        return Response.noContent().build();
    }

    @GET
    @Path("/{symbol}/quotes")
    @Operation(summary = "Get recent historical quotes")
    public Response getQuotes(
            @PathParam("symbol") String symbol,
            @QueryParam("limit") @DefaultValue("30") int limit) {

        List<StockQuote> quotes = StockQuote.findRecentBySymbol(symbol.toUpperCase(), limit);
        if (quotes.isEmpty()) return Response.status(Response.Status.NOT_FOUND).build();
        return Response.ok(quotes).build();
    }

    // ── request DTO ──────────────────────────────────────────────────────────

    public static class StockRequest {
        public String symbol;
        public String name;
    }
}
