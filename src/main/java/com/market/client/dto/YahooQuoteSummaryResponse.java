package com.market.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;
import java.util.List;

/**
 * Yahoo Finance /v10/finance/quoteSummary response DTO.
 * Modules: defaultKeyStatistics, financialData, summaryDetail, assetProfile
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class YahooQuoteSummaryResponse {

    public QuoteSummaryWrapper quoteSummary;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class QuoteSummaryWrapper {
        public List<QuoteSummaryResult> result;
        public ErrorWrapper error;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ErrorWrapper {
        public String code;
        public String description;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class QuoteSummaryResult {
        public FinancialData       financialData;
        public DefaultKeyStatistics defaultKeyStatistics;
        public SummaryDetail        summaryDetail;
        public AssetProfile         assetProfile;
    }

    /**
     * Wraps a numeric value from Yahoo Finance.
     * Yahoo sometimes returns {"raw": 0.015, "fmt": "1.5%"} and sometimes
     * a bare number 0.015 — the custom deserializer handles both forms.
     */
    @JsonDeserialize(using = V.VDeserializer.class)
    public static class V {
        public Double raw;

        public static class VDeserializer extends StdDeserializer<V> {
            public VDeserializer() { super(V.class); }

            @Override
            public V deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
                V v = new V();
                if (p.currentToken() == JsonToken.START_OBJECT) {
                    // {"raw": 0.015, "fmt": "1.5%"} — standard form
                    while (p.nextToken() != JsonToken.END_OBJECT) {
                        String field = p.currentName();
                        p.nextToken();
                        if ("raw".equals(field)) {
                            v.raw = p.currentToken() == JsonToken.VALUE_NULL ? null : p.getDoubleValue();
                        } else {
                            p.skipChildren();
                        }
                    }
                } else if (p.currentToken().isNumeric()) {
                    // bare number — e.g. dividendYield: 0.0154
                    v.raw = p.getDoubleValue();
                }
                // any other token (null, string…) → v.raw stays null
                return v;
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FinancialData {
        public V profitMargins;
        public V operatingMargins;
        public V returnOnEquity;
        public V returnOnAssets;
        public V revenueGrowth;
        public V earningsGrowth;
        public V currentRatio;
        public V debtToEquity;
        public V targetMeanPrice;
        public V numberOfAnalystOpinions;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DefaultKeyStatistics {
        public V pegRatio;
        public V priceToBook;
        public V beta;
        public V enterpriseToEbitda;
        public V enterpriseToRevenue;
        public V forwardEps;
        public V trailingEps;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SummaryDetail {
        public V trailingPE;
        public V forwardPE;
        public V dividendYield;
        public V marketCap;
        public V fiftyTwoWeekHigh;
        public V fiftyTwoWeekLow;
        public V priceToSalesTrailing12Months;
        public V beta;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AssetProfile {
        public String sector;
        public String industry;
    }

    /** Convenience: get the result at index 0, or null. */
    public QuoteSummaryResult firstResult() {
        if (quoteSummary == null || quoteSummary.result == null || quoteSummary.result.isEmpty()) return null;
        return quoteSummary.result.get(0);
    }
}
