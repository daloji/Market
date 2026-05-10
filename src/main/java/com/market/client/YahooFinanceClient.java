package com.market.client;

import com.market.client.dto.YahooChartResponse;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "yahoo-finance")
@Path("/v8/finance/chart")
public interface YahooFinanceClient {

    /**
     * Fetches OHLCV candlestick data for a given symbol.
     *
     * @param symbol   ticker, e.g. "AAPL" or "MC.PA"
     * @param interval candle interval: "1d", "1h", "5m", …
     * @param range    look-back window: "3mo", "1y", "5d", …
     */
    @GET
    @Path("/{symbol}")
    @ClientHeaderParam(name = "User-Agent",
            value = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
    @ClientHeaderParam(name = "Accept", value = "application/json")
    YahooChartResponse getChart(
            @PathParam("symbol") String symbol,
            @QueryParam("interval") String interval,
            @QueryParam("range") String range
    );
}
