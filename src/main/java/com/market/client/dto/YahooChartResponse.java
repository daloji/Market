package com.market.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class YahooChartResponse {

    public Chart chart;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Chart {
        public List<ChartResult> result;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChartResult {
        public ChartMeta meta;
        public List<Long> timestamp;
        public Indicators indicators;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChartMeta {
        public String symbol;
        public String currency;
        public Double regularMarketPrice;
        public Double regularMarketChangePercent;
        public Double regularMarketChange;
        public Double chartPreviousClose;
        public Long   regularMarketVolume;
        public String shortName;
        public String longName;
        public String exchangeName;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Indicators {
        public List<QuoteData> quote;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class QuoteData {
        public List<Double> open;
        public List<Double> high;
        public List<Double> low;
        public List<Double> close;
        public List<Long> volume;
    }
}
