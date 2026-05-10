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
    @Path("/stocks/{symbol}/fundamental")
    @Operation(summary = "Get (or fetch) fundamental valuation analysis for a stock")
    public Response getFundamental(@PathParam("symbol") String symbol) {
        String sym = symbol.toUpperCase();

        if (Stock.findBySymbol(sym) == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("Stock not in watchlist").build();
        }

        FundamentalData fd = fundamentalService.analyzeAndStore(sym);
        if (fd == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("No fundamental data available for " + sym).build();
        }
        return Response.ok(fd).build();
    }

    @GET
    @Path("/stocks/{symbol}/fundamental/cached")
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
