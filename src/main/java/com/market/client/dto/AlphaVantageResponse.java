package com.market.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Alpha Vantage TIME_SERIES_DAILY response.
 * Docs: https://www.alphavantage.co/documentation/#daily
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AlphaVantageResponse {

    @JsonProperty("Time Series (Daily)")
    public Map<String, DailyData> timeSeries;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DailyData {
        @JsonProperty("1. open")   public String open;
        @JsonProperty("2. high")   public String high;
        @JsonProperty("3. low")    public String low;
        @JsonProperty("4. close")  public String close;
        @JsonProperty("5. volume") public String volume;
    }
}
