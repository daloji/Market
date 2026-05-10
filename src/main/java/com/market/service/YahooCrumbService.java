package com.market.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.market.client.dto.YahooQuoteSummaryResponse;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * Manages Yahoo Finance cookie + crumb authentication and fetches quoteSummary data.
 *
 * Auth flow (no API key required):
 *   1. GET https://fc.yahoo.com/       → sets A3/A1 cookies
 *   2. GET /v1/test/getcrumb           → returns crumb string
 *   3. GET /v10/finance/quoteSummary/{symbol}?crumb=... (with cookies)
 *
 * Crumb is cached for 1 hour; re-fetched automatically on 401.
 */
@ApplicationScoped
public class YahooCrumbService {

    private static final Logger LOG = Logger.getLogger(YahooCrumbService.class);
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36";
    private static final String MODULES = "defaultKeyStatistics,financialData,summaryDetail,assetProfile";

    @Inject
    ObjectMapper objectMapper;

    private HttpClient httpClient;
    private String     crumb;
    private Instant    crumbFetchedAt;

    @PostConstruct
    void init() {
        CookieManager cookieManager = new CookieManager();
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        httpClient = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** Returns a valid crumb, refreshing if expired or not yet fetched. */
    public synchronized String getCrumb() throws Exception {
        if (crumb != null && crumbFetchedAt != null &&
                Instant.now().isBefore(crumbFetchedAt.plusSeconds(3600))) {
            return crumb;
        }
        refreshCrumb();
        return crumb;
    }

    private void refreshCrumb() throws Exception {
        // Step 1: obtain Yahoo cookie from fc.yahoo.com
        send(HttpRequest.newBuilder()
                .uri(URI.create("https://fc.yahoo.com/"))
                .header("User-Agent", UA)
                .GET().build());

        // Step 2: exchange cookie for crumb
        HttpResponse<String> resp = send(HttpRequest.newBuilder()
                .uri(URI.create("https://query1.finance.yahoo.com/v1/test/getcrumb"))
                .header("User-Agent", UA)
                .header("Accept", "*/*")
                .GET().build());

        String body = resp.body() == null ? "" : resp.body().trim();
        if (body.isBlank() || body.startsWith("{")) {
            throw new Exception("Failed to get Yahoo Finance crumb (got: " + body + ")");
        }
        crumb = body;
        crumbFetchedAt = Instant.now();
        LOG.debugf("Yahoo Finance crumb refreshed");
    }

    /**
     * Fetches and parses the quoteSummary for a symbol.
     * Retries once with a fresh crumb if the response indicates auth failure.
     */
    public YahooQuoteSummaryResponse fetchSummary(String symbol) throws Exception {
        String crumbValue = getCrumb();
        YahooQuoteSummaryResponse resp = doFetch(symbol, crumbValue);

        // If crumb expired, refresh and retry once
        if (resp == null || (resp.quoteSummary != null && resp.quoteSummary.error != null)) {
            LOG.debugf("Crumb expired for %s, refreshing…", symbol);
            crumb = null;
            crumbValue = getCrumb();
            resp = doFetch(symbol, crumbValue);
        }
        return resp;
    }

    private YahooQuoteSummaryResponse doFetch(String symbol, String crumbValue) throws Exception {
        String encodedSymbol = URLEncoder.encode(symbol, StandardCharsets.UTF_8);
        String encodedCrumb  = URLEncoder.encode(crumbValue, StandardCharsets.UTF_8);
        String url = "https://query1.finance.yahoo.com/v10/finance/quoteSummary/"
                + encodedSymbol + "?modules=" + MODULES + "&crumb=" + encodedCrumb;

        HttpResponse<String> resp = send(HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .GET().build());

        if (resp.statusCode() == 404) return null;
        return objectMapper.readValue(resp.body(), YahooQuoteSummaryResponse.class);
    }

    private HttpResponse<String> send(HttpRequest req) throws Exception {
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
