package com.market.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Alpha Vantage OVERVIEW endpoint response.
 * Docs: https://www.alphavantage.co/documentation/#company-overview
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AlphaVantageOverviewResponse {

    @JsonProperty("Symbol")           public String symbol;
    @JsonProperty("Name")             public String name;
    @JsonProperty("Sector")           public String sector;
    @JsonProperty("Industry")         public String industry;
    @JsonProperty("MarketCapitalization") public String marketCap;

    @JsonProperty("PERatio")              public String peRatio;
    @JsonProperty("ForwardPE")            public String forwardPE;
    @JsonProperty("PEGRatio")             public String pegRatio;
    @JsonProperty("PriceToBookRatio")     public String priceToBook;
    @JsonProperty("PriceToSalesRatioTTM") public String priceToSales;
    @JsonProperty("EVToEBITDA")           public String evToEbitda;

    @JsonProperty("EPS")                          public String eps;
    @JsonProperty("ProfitMargin")                 public String profitMargin;
    @JsonProperty("OperatingMarginTTM")           public String operatingMargin;
    @JsonProperty("ReturnOnEquityTTM")            public String returnOnEquity;
    @JsonProperty("ReturnOnAssetsTTM")            public String returnOnAssets;
    @JsonProperty("QuarterlyEarningsGrowthYOY")   public String earningsGrowth;
    @JsonProperty("QuarterlyRevenueGrowthYOY")    public String revenueGrowth;

    @JsonProperty("Beta")                    public String beta;
    @JsonProperty("DividendYield")           public String dividendYield;
    @JsonProperty("AnalystTargetPrice")      public String analystTargetPrice;
    @JsonProperty("AnalystRatingStrongBuy")  public String ratingStrongBuy;
    @JsonProperty("AnalystRatingBuy")        public String ratingBuy;
    @JsonProperty("AnalystRatingHold")       public String ratingHold;
    @JsonProperty("AnalystRatingSell")       public String ratingSell;
    @JsonProperty("AnalystRatingStrongSell") public String ratingStrongSell;

    @JsonProperty("52WeekHigh") public String weekHigh52;
    @JsonProperty("52WeekLow")  public String weekLow52;

    /** Returns true if the response actually contains data (not just an error/empty response). */
    public boolean hasData() {
        return symbol != null && !symbol.isBlank();
    }
}
