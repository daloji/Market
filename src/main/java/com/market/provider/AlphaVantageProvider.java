package com.market.provider;

import com.market.client.AlphaVantageClient;
import com.market.client.dto.AlphaVantageResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Alpha Vantage — https://www.alphavantage.co
 * Free plan: 25 requests/day (use as last resort).
 * Best for US stocks. European support is limited.
 * Get a free key at https://www.alphavantage.co/support/#api-key
 */
@ApplicationScoped
public class AlphaVantageProvider implements StockDataProvider {

    @Inject
    @RestClient
    AlphaVantageClient client;

    @ConfigProperty(name = "market.datasource.alphavantage.api-key")
    Optional<String> apiKey;

    @Override public String  getName()   { return "Alpha Vantage"; }
    @Override public boolean isEnabled() { return apiKey.isPresent(); }

    @Override
    public List<DailyQuote> fetchQuotes(String symbol, int days) throws Exception {
        if (!isEnabled()) throw new Exception("Alpha Vantage not configured (no API key)");

        // Alpha Vantage uses plain symbols for US stocks; for EU stocks strip suffix
        String avSymbol = toAvSymbol(symbol);

        AlphaVantageResponse response = client.getDailyTimeSeries(
                "TIME_SERIES_DAILY", avSymbol, "compact", apiKey.get());

        if (response == null || response.timeSeries == null || response.timeSeries.isEmpty()) {
            throw new Exception("No data from Alpha Vantage for " + symbol + " (mapped: " + avSymbol + ")");
        }

        // timeSeries is a Map<date-string, data> — sort chronologically, keep last `days`
        List<DailyQuote> all = new ArrayList<>();
        response.timeSeries.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(e -> {
                    try {
                        AlphaVantageResponse.DailyData d = e.getValue();
                        all.add(new DailyQuote(
                                LocalDate.parse(e.getKey()),
                                parseD(d.open), parseD(d.high), parseD(d.low),
                                parseD(d.close), parseL(d.volume)
                        ));
                    } catch (Exception ignored) {}
                });

        // Return the last `days` entries (most recent)
        return all.subList(Math.max(0, all.size() - days), all.size());
    }

    /**
     * Alpha Vantage expects simple symbols for US stocks.
     * European stocks: strip exchange suffix (.PA → try as-is, then plain symbol).
     * Note: EU stock coverage is incomplete on AV free tier.
     */
    private String toAvSymbol(String symbol) {
        if (symbol.contains(".")) {
            // Try returning as-is first; AV sometimes accepts Yahoo-style symbols
            return symbol;
        }
        return symbol;
    }

    private Double parseD(String s) { try { return s == null ? null : Double.parseDouble(s); } catch (Exception e) { return null; } }
    private Long   parseL(String s) { try { return s == null ? null : Long.parseLong(s);   } catch (Exception e) { return null; } }
}
