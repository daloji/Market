package com.market.client;

import com.market.client.dto.AlphaVantageOverviewResponse;
import com.market.client.dto.AlphaVantageResponse;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "alphavantage")
@Path("/query")
public interface AlphaVantageClient {

    @GET
    AlphaVantageResponse getDailyTimeSeries(
            @QueryParam("function")   String function,
            @QueryParam("symbol")     String symbol,
            @QueryParam("outputsize") String outputsize,
            @QueryParam("apikey")     String apikey
    );

    @GET
    AlphaVantageOverviewResponse getCompanyOverview(
            @QueryParam("function") String function,
            @QueryParam("symbol")   String symbol,
            @QueryParam("apikey")   String apikey
    );
}
