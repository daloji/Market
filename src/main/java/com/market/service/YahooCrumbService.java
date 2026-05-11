package com.market.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.market.client.dto.YahooQuoteSummaryResponse;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Manages Yahoo Finance cookie + crumb authentication and fetches quoteSummary data.
 *
 * Auth flow (no API key required):
 *   1. GET https://fc.yahoo.com/       → sets A3/A1 cookies
 *   2. GET /v1/test/getcrumb           → returns crumb string
 *   3. GET /v10/finance/quoteSummary/{symbol}?crumb=... (with cookies)
 *
 * Crumb is cached for 1 hour; re-fetched automatically on 401.
 *
 * Server / EU note: datacenter IPs may trigger Yahoo's GDPR consent page.
 * We inject a consent cookie and try query2 as fallback to bypass this.
 */
@ApplicationScoped
public class YahooCrumbService {

    private static final Logger LOG = Logger.getLogger(YahooCrumbService.class);

    // Full Chrome UA — bare UA strings are more likely to be blocked on datacenter IPs
    private static final String UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final String MODULES =
        "defaultKeyStatistics,financialData,summaryDetail,assetProfile";

    // Pre-accepted GDPR/EU consent cookie (required on EU datacenter IPs)
    // This avoids the consent.yahoo.com redirect that breaks the crumb flow.
    private static final String EU_CONSENT_COOKIE =
        "GUCS=AQABCAFn; B=0oo4k8qcmvgmm&b=3&s=13; "
        + "EuConsent=CQAAAAAAAAAAAAAFABENAFCoAP_AAH_AACiQHNN5YgVjWfBVFB"
        + "mAIAQAgAIABgACAAxABAAQAAAAAEAAAA";

    private static final List<String> HOSTS = List.of(
        "https://query1.finance.yahoo.com",
        "https://query2.finance.yahoo.com"
    );

    @Inject
    ObjectMapper objectMapper;

    private HttpClient httpClient;
    private CookieManager cookieManager;
    private String     crumb;
    private Instant    crumbFetchedAt;

    @PostConstruct
    void init() {
        cookieManager = new CookieManager();
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        httpClient = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
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
        // Step 1: obtain Yahoo cookies + inject EU consent cookie to bypass GDPR redirect
        try {
            send(HttpRequest.newBuilder()
                    .uri(URI.create("https://fc.yahoo.com/"))
                    .header("User-Agent", UA)
                    .header("Accept", "text/html,application/xhtml+xml,*/*")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .GET().build());
        } catch (Exception e) {
            LOG.warnf("fc.yahoo.com unreachable (%s) — will try crumb anyway", e.getMessage());
        }

        // Inject EU/GDPR consent cookies so Yahoo doesn't redirect us to consent.yahoo.com
        injectConsentCookies();

        // Step 2: get crumb — try both hosts
        Exception lastEx = null;
        for (String host : HOSTS) {
            try {
                HttpResponse<String> resp = send(HttpRequest.newBuilder()
                        .uri(URI.create(host + "/v1/test/getcrumb"))
                        .header("User-Agent", UA)
                        .header("Accept", "*/*")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .GET().build());

                String body = resp.body() == null ? "" : resp.body().trim();
                LOG.debugf("getcrumb response [%d] from %s: %s", resp.statusCode(), host,
                        body.length() > 100 ? body.substring(0, 100) + "…" : body);

                // Valid crumb is a short non-JSON, non-HTML string
                if (!body.isBlank() && !body.startsWith("{") && !body.startsWith("<")
                        && body.length() < 50) {
                    crumb = body;
                    crumbFetchedAt = Instant.now();
                    LOG.infof("Yahoo Finance crumb refreshed via %s", host);
                    return;
                }
                LOG.warnf("Unexpected crumb response from %s (len=%d, starts=%s)",
                        host, body.length(), body.length() > 0 ? body.substring(0, Math.min(30, body.length())) : "");
            } catch (Exception e) {
                lastEx = e;
                LOG.warnf("Crumb request failed on %s: %s", host, e.getMessage());
            }
        }
        throw new Exception("Cannot obtain Yahoo Finance crumb — server may be blocked by Yahoo Finance. "
                + "Check outbound internet access and consider adding an API key for TwelveData/AlphaVantage as fallback.",
                lastEx);
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

    /**
     * Tests connectivity to Yahoo Finance and returns a diagnostic string.
     * Used by the health endpoint.
     */
    public String diagnose() {
        StringBuilder sb = new StringBuilder();
        for (String host : HOSTS) {
            try {
                HttpResponse<String> r = send(HttpRequest.newBuilder()
                        .uri(URI.create(host + "/v1/test/getcrumb"))
                        .header("User-Agent", UA)
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .GET().build());
                String body = r.body() == null ? "" : r.body().trim();
                sb.append(host).append(" → ").append(r.statusCode())
                  .append(" | body[").append(Math.min(body.length(), 60)).append("]: ")
                  .append(body, 0, Math.min(body.length(), 60)).append("\n");
            } catch (Exception e) {
                sb.append(host).append(" → ERROR: ").append(e.getMessage()).append("\n");
            }
        }
        return sb.toString();
    }

    private YahooQuoteSummaryResponse doFetch(String symbol, String crumbValue) throws Exception {
        String encodedSymbol = URLEncoder.encode(symbol, StandardCharsets.UTF_8);
        String encodedCrumb  = URLEncoder.encode(crumbValue, StandardCharsets.UTF_8);

        // Try both hosts
        for (String host : HOSTS) {
            String url = host + "/v10/finance/quoteSummary/"
                    + encodedSymbol + "?modules=" + MODULES + "&crumb=" + encodedCrumb
                    + "&lang=en-US&region=US&formatted=false";
            try {
                HttpResponse<String> resp = send(HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", UA)
                        .header("Accept", "application/json")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .GET().build());

                if (resp.statusCode() == 404) return null;
                if (resp.statusCode() == 401 || resp.statusCode() == 403) {
                    LOG.warnf("quoteSummary %s from %s: HTTP %d", symbol, host, resp.statusCode());
                    continue; // try other host
                }
                return objectMapper.readValue(resp.body(), YahooQuoteSummaryResponse.class);
            } catch (Exception e) {
                LOG.warnf("quoteSummary %s from %s failed: %s", symbol, host, e.getMessage());
            }
        }
        return null;
    }

    /**
     * Inject EU GDPR consent cookies directly into the cookie store so Yahoo
     * Finance doesn't redirect datacenter IPs to consent.yahoo.com.
     */
    private void injectConsentCookies() {
        try {
            URI yahooUri = URI.create("https://finance.yahoo.com");

            // B cookie — basic Yahoo session
            HttpCookie bCookie = new HttpCookie("B", "0oo4k8qcmvgmm&b=3&s=13");
            bCookie.setPath("/");
            bCookie.setDomain(".yahoo.com");
            bCookie.setMaxAge(86400);
            bCookie.setVersion(0);
            cookieManager.getCookieStore().add(yahooUri, bCookie);

            // GUCS — consent accepted
            HttpCookie gucs = new HttpCookie("GUCS", "AQABCAFn");
            gucs.setPath("/");
            gucs.setDomain(".yahoo.com");
            gucs.setMaxAge(86400);
            gucs.setVersion(0);
            cookieManager.getCookieStore().add(yahooUri, gucs);

        } catch (Exception e) {
            LOG.warnf("Could not inject consent cookies: %s", e.getMessage());
        }
    }

    private HttpResponse<String> send(HttpRequest req) throws Exception {
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }
}

