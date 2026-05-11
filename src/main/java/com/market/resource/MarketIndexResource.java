package com.market.resource;

import com.market.client.YahooFinanceClient;
import com.market.client.dto.YahooChartResponse;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

@Path("/api/indices")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Indices", description = "Market indices (CAC 40, DAX, …)")
public class MarketIndexResource {

    /** Maps URL-safe symbol to Yahoo Finance symbol (prepends ^). */
    private static final Map<String, String> KNOWN = Map.of(
            "FCHI",  "^FCHI",   // CAC 40
            "GDAXI", "^GDAXI",  // DAX
            "STOXX50E", "^STOXX50E", // Euro Stoxx 50
            "N100",  "^N100",   // Euronext 100
            "FTSE",  "^FTSE"    // FTSE 100
    );

    @Inject
    @RestClient
    YahooFinanceClient yahooClient;

    /**
     * Returns current price, day-change and name for a market index.
     * Use clean symbols: FCHI (CAC40), GDAXI (DAX), STOXX50E, FTSE, N100.
     */
    @GET
    @Path("/{symbol}")
    @Operation(summary = "Get current data for a market index")
    public Response getIndex(@PathParam("symbol") String symbol) {
        String yahooSym = KNOWN.getOrDefault(symbol.toUpperCase(),
                "^" + symbol.toUpperCase());
        try {
            YahooChartResponse resp = yahooClient.getChart(yahooSym, "1d", "5d");

            if (resp == null || resp.chart == null
                    || resp.chart.result == null || resp.chart.result.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("Index not found: " + symbol).build();
            }

            YahooChartResponse.ChartMeta meta = resp.chart.result.get(0).meta;

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("symbol",        symbol.toUpperCase());
            out.put("yahooSymbol",   yahooSym);
            out.put("name",          meta.shortName != null ? meta.shortName : yahooSym);
            out.put("price",         meta.regularMarketPrice);

            // Yahoo Finance doesn't always provide change fields in chart responses —
            // compute them from price and previousClose when missing.
            Double price = meta.regularMarketPrice;
            Double prev  = meta.chartPreviousClose;
            Double change     = meta.regularMarketChange;
            Double changePct  = meta.regularMarketChangePercent;
            if ((change == null || changePct == null) && price != null && prev != null && prev != 0) {
                change    = price - prev;
                changePct = (change / prev) * 100.0;
            }
            out.put("changePercent", changePct);
            out.put("change",        change);
            out.put("previousClose", prev);

            return Response.ok(out).build();
        } catch (Exception e) {
            return Response.serverError().entity(e.getMessage()).build();
        }
    }
}
