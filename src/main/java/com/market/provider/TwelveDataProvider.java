package com.market.provider;

import com.market.client.TwelveDataClient;
import com.market.client.dto.TwelveDataResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Twelve Data — https://twelvedata.com
 * Free plan: 800 API credits/day, 8 req/min.
 * Supports global markets including European stocks.
 * Get a free key at https://twelvedata.com/register
 *
 * Symbol format: same as Yahoo Finance (.PA, .DE, .AS, .SW accepted).
 */
@ApplicationScoped
public class TwelveDataProvider implements StockDataProvider {

    @Inject
    @RestClient
    TwelveDataClient client;

    @ConfigProperty(name = "market.datasource.twelvedata.api-key")
    Optional<String> apiKey;

    @Override public String  getName()   { return "Twelve Data"; }
    @Override public boolean isEnabled() { return apiKey.isPresent(); }

    @Override
    public List<DailyQuote> fetchQuotes(String symbol, int days) throws Exception {
        if (!isEnabled()) throw new Exception("Twelve Data not configured (no API key)");

        TwelveDataResponse response = client.getTimeSeries(symbol, "1day", days, apiKey.get());

        if (response == null) {
            throw new Exception("Null response from Twelve Data for " + symbol);
        }
        if (!"ok".equalsIgnoreCase(response.status)) {
            throw new Exception("Twelve Data error for " + symbol + ": " + response.message);
        }
        if (response.values == null || response.values.isEmpty()) {
            throw new Exception("Empty data from Twelve Data for " + symbol);
        }

        // Twelve Data returns newest first — reverse to chronological
        List<DailyQuote> out = new ArrayList<>();
        for (int i = response.values.size() - 1; i >= 0; i--) {
            TwelveDataResponse.Value v = response.values.get(i);
            try {
                out.add(new DailyQuote(
                        LocalDate.parse(v.datetime),
                        parseD(v.open), parseD(v.high), parseD(v.low),
                        parseD(v.close), parseL(v.volume)
                ));
            } catch (Exception ignored) {}
        }
        return out;
    }

    private Double parseD(String s) { try { return s == null ? null : Double.parseDouble(s); } catch (Exception e) { return null; } }
    private Long   parseL(String s) { try { return s == null ? null : Long.parseLong(s);   } catch (Exception e) { return null; } }
}
