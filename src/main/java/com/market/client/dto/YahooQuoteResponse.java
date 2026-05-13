package com.market.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * DTO for Yahoo Finance /v7/finance/quote?symbols={symbol}
 * This endpoint does NOT require a crumb/cookie — it works from datacenter IPs
 * (OVH, AWS, etc.) unlike /v10/finance/quoteSummary.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class YahooQuoteResponse {

    public QuoteResponse quoteResponse;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class QuoteResponse {
        public List<QuoteResult> result;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class QuoteResult {
        public String symbol;
        public String shortName;
        public String longName;
        public String sector;
        public String industry;

        // Valuation
        public Double trailingPE;
        public Double forwardPE;
        public Double priceToBook;
        public Double bookValue;
        public Double priceEpsCurrentYear;

        // Earnings / EPS
        public Double epsTrailingTwelveMonths;
        public Double epsForward;
        public Double epsCurrentYear;

        // Dividends
        public Double dividendYield;
        public Double trailingAnnualDividendYield;

        // Risk
        public Double beta;

        // Price range
        public Double fiftyTwoWeekHigh;
        public Double fiftyTwoWeekLow;
        public Double regularMarketPrice;

        // Market cap & targets
        public Long   marketCap;
        public Double targetMeanPrice;
        public Integer numberOfAnalystOpinions;

        public boolean hasData() {
            return symbol != null && !symbol.isBlank()
                && (trailingPE != null || forwardPE != null || priceToBook != null);
        }
    }

    public QuoteResult firstResult() {
        if (quoteResponse == null || quoteResponse.result == null
                || quoteResponse.result.isEmpty()) return null;
        return quoteResponse.result.get(0);
    }
}
