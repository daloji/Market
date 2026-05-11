package com.market.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.market.client.dto.YahooQuoteSummaryResponse;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

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
 * Yahoo Finance cookie + crumb authentication for quoteSummary.
 *
 * Robustness improvements for server / EU datacenter environments:
 *  - No Java CookieManager (unreliable across JVM versions / Docker networks)
 *  - Consent cookies are passed as explicit Cookie: header (not via cookie store)
 *  - Tries both query1 and query2 hosts
 *  - Extracts Set-Cookie from the fc.yahoo.com response and reuses them
 */
@ApplicationScoped
public class YahooCrumbService {

    private static final Logger LOG = Logger.getLogger(YahooCrumbService.class);

    private static final String UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final String MODULES =
        "defaultKeyStatistics,financialData,summaryDetail,assetProfile";

    private static final List<String> HOSTS = List.of(
        "https://query1.finance.yahoo.com",
        "https://query2.finance.yahoo.com"
    );

    @Inject
    ObjectMapper objectMapper;

    private HttpClient httpClient;

    /** Raw cookie string harvested from fc.yahoo.com + consent defaults */
    private volatile String cookieHeader;
    private volatile String crumb;
    private volatile Instant crumbFetchedAt;

    @PostConstruct
    void init() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ─── Public API ────────────────────────────────────────────────────────────

    public synchronized String getCrumb() throws Exception {
        if (crumb != null && crumbFetchedAt != null
                && Instant.now().isBefore(crumbFetchedAt.plusSeconds(3600))) {
            return crumb;
        }
        refreshCrumb();
        return crumb;
    }

    public YahooQuoteSummaryResponse fetchSummary(String symbol) throws Exception {
        String c = getCrumb();
        YahooQuoteSummaryResponse resp = doFetch(symbol, c);

        if (resp == null || (resp.quoteSummary != null && resp.quoteSummary.error != null)) {
            LOG.debugf("Crumb stale for %s — refreshing", symbol);
            synchronized (this) { crumb = null; }
            c = getCrumb();
            resp = doFetch(symbol, c);
        }
        return resp;
    }

    /** Quick connectivity test — returns human-readable multi-line report. */
    public String diagnose() {
        StringBuilder sb = new StringBuilder();
        // Test cookie sources
        for (String cookieUrl : List.of("https://fc.yahoo.com/", "https://finance.yahoo.com/")) {
            sb.append("=== ").append(cookieUrl).append(" ===\n");
            try {
                HttpResponse<String> r = get(cookieUrl, null);
                List<String> cookies = r.headers().allValues("Set-Cookie");
                sb.append("  HTTP ").append(r.statusCode()).append("\n");
                sb.append("  Set-Cookie headers: ").append(cookies.size()).append("\n");
                if (!cookies.isEmpty()) {
                    sb.append("  First cookie: ").append(cookies.get(0), 0, Math.min(cookies.get(0).length(), 60)).append("\n");
                }
            } catch (Exception e) {
                sb.append("  ERROR: ").append(e.getMessage()).append("\n");
            }
        }
        // Test crumb endpoint on each host
        for (String host : HOSTS) {
            sb.append("=== ").append(host).append("/v1/test/getcrumb ===\n");
            try {
                String cookie = buildCookieHeader(null);
                HttpResponse<String> r = get(host + "/v1/test/getcrumb", cookie);
                String body = r.body() == null ? "" : r.body().trim();
                sb.append("  HTTP ").append(r.statusCode()).append("\n");
                sb.append("  body[").append(Math.min(body.length(), 80)).append("]: ")
                  .append(body, 0, Math.min(body.length(), 80)).append("\n");
            } catch (Exception e) {
                sb.append("  ERROR: ").append(e.getMessage()).append("\n");
            }
        }
        return sb.toString();
    }

    // ─── Private helpers ───────────────────────────────────────────────────────

    private synchronized void refreshCrumb() throws Exception {
        // Step 1: get session cookies — try fc.yahoo.com, then finance.yahoo.com as fallback
        String freshCookies = null;
        for (String cookieUrl : List.of("https://fc.yahoo.com/", "https://finance.yahoo.com/")) {
            try {
                HttpResponse<String> resp = get(cookieUrl, null);
                String extracted = extractCookiesFromResponse(resp);
                if (!extracted.isBlank()) {
                    freshCookies = extracted;
                    LOG.debugf("Cookies from %s: %s", cookieUrl,
                            freshCookies.length() > 80 ? freshCookies.substring(0, 80) + "…" : freshCookies);
                    break;
                }
                LOG.debugf("%s returned HTTP %d but no Set-Cookie headers", cookieUrl, resp.statusCode());
            } catch (Exception e) {
                LOG.warnf("%s unreachable: %s", cookieUrl, e.getMessage());
            }
        }
        if (freshCookies == null) {
            LOG.warnf("No session cookies obtained — falling back to consent defaults only");
        }

        cookieHeader = buildCookieHeader(freshCookies);

        // Step 2: try each host
        Exception last = null;
        for (String host : HOSTS) {
            try {
                HttpResponse<String> r = get(host + "/v1/test/getcrumb", cookieHeader);
                String body = r.body() == null ? "" : r.body().trim();
                LOG.debugf("getcrumb [%d] from %s: %s", r.statusCode(), host,
                        body.length() > 60 ? body.substring(0, 60) + "…" : body);

                if (!body.isBlank() && !body.startsWith("{") && !body.startsWith("<") && body.length() < 50) {
                    crumb = body;
                    crumbFetchedAt = Instant.now();
                    LOG.infof("Yahoo crumb ok via %s", host);
                    return;
                }
                LOG.warnf("Unexpected crumb from %s (HTTP %d, starts: %.30s)", host, r.statusCode(), body);
            } catch (Exception e) {
                last = e;
                LOG.warnf("getcrumb failed on %s: %s", host, e.getMessage());
            }
        }
        throw new Exception(
            "Cannot get Yahoo Finance crumb. Check /api/fundamentals/health for details. "
            + "Server may need outbound HTTPS access to finance.yahoo.com.",
            last);
    }

    private YahooQuoteSummaryResponse doFetch(String symbol, String crumbValue) throws Exception {
        String encodedSymbol = URLEncoder.encode(symbol, StandardCharsets.UTF_8);
        String encodedCrumb  = URLEncoder.encode(crumbValue, StandardCharsets.UTF_8);

        for (String host : HOSTS) {
            String url = host + "/v10/finance/quoteSummary/" + encodedSymbol
                    + "?modules=" + MODULES
                    + "&crumb=" + encodedCrumb
                    + "&lang=en-US&region=US&formatted=false";
            try {
                HttpResponse<String> r = get(url, cookieHeader);
                if (r.statusCode() == 404) return null;
                if (r.statusCode() == 401 || r.statusCode() == 403) {
                    LOG.warnf("quoteSummary %s HTTP %d from %s", symbol, r.statusCode(), host);
                    continue;
                }
                return objectMapper.readValue(r.body(), YahooQuoteSummaryResponse.class);
            } catch (Exception e) {
                LOG.warnf("quoteSummary %s failed on %s: %s", symbol, host, e.getMessage());
            }
        }
        return null;
    }

    /**
     * Builds the Cookie header string by merging:
     *  1. Fresh session cookies from fc.yahoo.com / finance.yahoo.com (if available)
     *  2. EU consent cookies (GDPR bypass for datacenter IPs)
     *
     * The cmp timestamp must match the current epoch — Yahoo Finance rejects stale values.
     */
    private String buildCookieHeader(String fresh) {
        long now = Instant.now().getEpochSecond();
        String consent = "B=0oo4k8qcmvgmm&b=3&s=13; GUCS=AQABCAFn; "
                + "GUC=AQABCAFn; "
                + "cmp=t=" + now + "&j=0&u=1---";
        return fresh != null && !fresh.isBlank() ? fresh + "; " + consent : consent;
    }

    /** Extracts Set-Cookie values from an HTTP response into a single Cookie: header string. */
    private String extractCookiesFromResponse(HttpResponse<String> resp) {
        List<String> setCookies = resp.headers().allValues("Set-Cookie");
        if (setCookies.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String header : setCookies) {
            // Each Set-Cookie is "name=value; attributes…" — we only want "name=value"
            String kv = header.split(";")[0].trim();
            if (!kv.isEmpty()) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(kv);
            }
        }
        return sb.toString();
    }

    private HttpResponse<String> get(String url, String cookie) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml,application/json,*/*;q=0.9")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Accept-Encoding", "identity")
                .GET();
        if (cookie != null && !cookie.isBlank()) b.header("Cookie", cookie);
        return httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}


