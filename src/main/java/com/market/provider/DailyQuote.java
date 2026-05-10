package com.market.provider;

import java.time.LocalDate;

/** Normalized daily OHLCV quote, source-agnostic. */
public class DailyQuote {

    public final LocalDate date;
    public final Double open;
    public final Double high;
    public final Double low;
    public final Double close;
    public final Long   volume;

    public DailyQuote(LocalDate date, Double open, Double high,
                      Double low, Double close, Long volume) {
        this.date   = date;
        this.open   = open;
        this.high   = high;
        this.low    = low;
        this.close  = close;
        this.volume = volume;
    }
}
