package com.market.client;

import com.market.client.dto.TwelveDataResponse;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "twelvedata")
@Path("/time_series")
public interface TwelveDataClient {

    @GET
    TwelveDataResponse getTimeSeries(
            @QueryParam("symbol")     String symbol,
            @QueryParam("interval")   String interval,
            @QueryParam("outputsize") int    outputsize,
            @QueryParam("apikey")     String apikey
    );
}
