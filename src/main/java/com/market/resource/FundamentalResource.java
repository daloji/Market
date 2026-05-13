package com.market.resource;

import com.market.model.FundamentalData;
import com.market.model.Stock;
import com.market.service.FundamentalAnalysisService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.Map;

@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Fundamentals", description = "Fundamental valuation analysis")
public class FundamentalResource {

    @Inject
    FundamentalAnalysisService fundamentalService;

    @GET
    @Path("/fundamentals")
    @Transactional
    @Operation(summary = "Get latest cached fundamental data for all stocks")
    public List<FundamentalData> getAllFundamentals() {
        return FundamentalData.findAllLatest();
    }

    @GET
    @Path("/fundamentals/health")
    @Operation(summary = "Diagnose Yahoo Finance connectivity (useful for server deployments)")
    public Response checkYahooHealth() {
        String report = fundamentalService.diagnoseYahoo();
        return Response.ok(Map.of("report", report)).build();
    }

    @GET
    @Path("/fundamentals/{symbol}")
    @Operation(summary = "Get (or fetch) fundamental valuation analysis for a stock")
    public Response getFundamental(@PathParam("symbol") String symbol) {
        String sym = symbol.toUpperCase();

        if (Stock.findBySymbol(sym) == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("Stock not in watchlist").build();
        }

        FundamentalData fd = fundamentalService.analyzeAndStore(sym);
        if (fd == null) {
            // Fallback: return last cached data from DB even if stale
            FundamentalData cached = FundamentalData.findLatestBySymbol(sym);
            if (cached != null) {
                return Response.ok(cached)
                        .header("X-Data-Stale", "true")
                        .header("X-Data-FetchedAt", cached.fetchedAt.toString())
                        .build();
            }
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("No fundamental data available for " + sym
                          + " — check /api/fundamentals/health for connectivity details").build();
        }
        return Response.ok(fd).build();
    }

    @POST
    @Path("/fundamentals/{symbol}/from-client")
    @Transactional
    @Operation(summary = "Store fundamental data fetched by the client browser (bypasses server IP blocks)")
    public Response storeFromClient(@PathParam("symbol") String symbol,
                                    com.market.client.dto.YahooQuoteResponse.QuoteResult quoteResult) {
        String sym = symbol.toUpperCase();
        if (quoteResult == null || !quoteResult.hasData()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("No usable fundamental data in payload").build();
        }
        FundamentalData fd = fundamentalService.storeFromClientFetch(sym, quoteResult);
        if (fd == null) {
            return Response.serverError().entity("Failed to store fundamental data").build();
        }
        return Response.ok(fd)
                .header("X-Data-Source", "CLIENT_BROWSER")
                .build();
    }

    @Transactional
    @Operation(summary = "Get the last cached fundamental data without triggering a new fetch")
    public Response getCachedFundamental(@PathParam("symbol") String symbol) {
        FundamentalData fd = FundamentalData.findLatestBySymbol(symbol.toUpperCase());
        if (fd == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(fd).build();
    }
}
