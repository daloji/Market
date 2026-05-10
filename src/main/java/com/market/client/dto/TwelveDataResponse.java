package com.market.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Twelve Data /time_series response.
 * Docs: https://twelvedata.com/docs#time-series
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TwelveDataResponse {

    public String status;   // "ok" | "error"
    public String message;  // error message when status = "error"
    public Meta   meta;
    public List<Value> values;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Meta {
        public String symbol;
        public String currency;
        public String exchange;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Value {
        public String datetime; // "2024-01-15"
        public String open;
        public String high;
        public String low;
        public String close;
        public String volume;
    }
}
