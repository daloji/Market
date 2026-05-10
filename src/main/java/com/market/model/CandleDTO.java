package com.market.model;

/** OHLCV candle DTO used for the crypto trading page. */
public class CandleDTO {

    public long   time;    // Unix timestamp in seconds (for lightweight-charts)
    public double open;
    public double high;
    public double low;
    public double close;
    public double volume;

    public CandleDTO() {}

    public CandleDTO(long time, double open, double high, double low,
                     double close, double volume) {
        this.time   = time;
        this.open   = open;
        this.high   = high;
        this.low    = low;
        this.close  = close;
        this.volume = volume;
    }
}
