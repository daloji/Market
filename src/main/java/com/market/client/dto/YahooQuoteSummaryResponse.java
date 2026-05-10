package com.market.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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

    /** Wraps a raw numeric value from Yahoo Finance. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class V {
        public Double raw;
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
