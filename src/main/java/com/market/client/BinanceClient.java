package com.market.client;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;

@RegisterRestClient(configKey = "binance-api")
@Path("/api/v3")
@Produces(MediaType.APPLICATION_JSON)
public interface BinanceClient {

    /**
     * Fetches OHLCV candlestick data from Binance.
     * Each element: [openTime, open, high, low, close, volume, closeTime, ...]
     *
     * @param symbol   e.g. "BTCUSDT"
     * @param interval e.g. "1h", "15m", "5m"
     * @param limit    number of candles (max 1000)
     */
    @GET
    @Path("/klines")
    List<List<Object>> getKlines(
            @QueryParam("symbol")   String symbol,
            @QueryParam("interval") String interval,
            @QueryParam("limit")    int    limit
    );
}
