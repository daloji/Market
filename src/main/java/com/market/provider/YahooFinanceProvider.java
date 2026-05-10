package com.market.provider;

import com.market.client.YahooFinanceClient;
import com.market.client.dto.YahooChartResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Yahoo Finance — unofficial public API, no key required.
 * Supports all major markets including European stocks (.PA, .DE, .AS, .SW).
 * Always enabled; used as the primary source.
 */
@ApplicationScoped
public class YahooFinanceProvider implements StockDataProvider {

    @Inject
    @RestClient
    YahooFinanceClient client;

    @Override public String  getName()    { return "Yahoo Finance"; }
    @Override public boolean isEnabled()  { return true; }

    @Override
    public List<DailyQuote> fetchQuotes(String symbol, int days) throws Exception {
        String range = days <= 30 ? "1mo" : days <= 90 ? "3mo" : "6mo";
        YahooChartResponse response = client.getChart(symbol, "1d", range);

        if (response == null || response.chart == null
                || response.chart.result == null || response.chart.result.isEmpty()) {
            throw new Exception("Empty response for " + symbol);
        }

        YahooChartResponse.ChartResult result = response.chart.result.get(0);

        if (result.timestamp == null || result.indicators == null
                || result.indicators.quote == null || result.indicators.quote.isEmpty()) {
            throw new Exception("Incomplete payload for " + symbol);
        }

        YahooChartResponse.QuoteData q  = result.indicators.quote.get(0);
        List<Long>                   ts = result.timestamp;
        List<DailyQuote>             out = new ArrayList<>();

        for (int i = 0; i < ts.size(); i++) {
            if (ts.get(i) == null) continue;
            Double close = safe(q.close, i);
            if (close == null) continue;

            out.add(new DailyQuote(
                Instant.ofEpochSecond(ts.get(i)).atZone(ZoneOffset.UTC).toLocalDate(),
                safe(q.open, i), safe(q.high, i), safe(q.low, i),
                close, safeL(q.volume, i)
            ));
        }
        return out;
    }

    private Double safe(List<Double> l, int i) { return (l == null || i >= l.size()) ? null : l.get(i); }
    private Long  safeL(List<Long>   l, int i) { return (l == null || i >= l.size()) ? null : l.get(i); }
}
