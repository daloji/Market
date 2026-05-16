package com.market.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.Locale;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Low-level Binance Futures REST client with HMAC-SHA256 request signing.
 * Uses java.net.http.HttpClient for full control over headers and dynamic signing.
 *
 * Configure credentials in application.properties:
 *   market.binance.futures.api-key=YOUR_FUTURES_KEY
 *   market.binance.futures.secret=YOUR_FUTURES_SECRET
 *   market.binance.futures.testnet=false   (set true to use testnet)
 *
 * API base: https://fapi.binance.com  (live) or https://testnet.binancefuture.com (testnet)
 */
@ApplicationScoped
public class BinanceFuturesService {

    private static final Logger LOG = Logger.getLogger(BinanceFuturesService.class);

    private static final String LIVE_URL    = "https://fapi.binance.com";
    private static final String TESTNET_URL = "https://testnet.binancefuture.com";

    @ConfigProperty(name = "market.binance.futures.api-key", defaultValue = "")
    String apiKey;

    @ConfigProperty(name = "market.binance.futures.secret", defaultValue = "")
    String secret;

    @ConfigProperty(name = "market.binance.futures.testnet", defaultValue = "false")
    boolean testnet;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public boolean isConfigured() {
        return !apiKey.isBlank() && !secret.isBlank();
    }

    public boolean isTestnet() {
        return testnet;
    }

    private String baseUrl() {
        return testnet ? TESTNET_URL : LIVE_URL;
    }

    // ── Futures API calls ─────────────────────────────────────────────────────

    /**
     * Sets leverage for a symbol (POST /fapi/v1/leverage).
     * @param symbol   e.g. "BTCUSDT"
     * @param leverage 1–125 (BTCUSDT max = 125)
     */
    public String setLeverage(String symbol, int leverage) throws Exception {
        String params = "symbol=" + symbol + "&leverage=" + leverage + "&timestamp=" + ts();
        return post("/fapi/v1/leverage", params + "&signature=" + sign(params));
    }

    /**
     * Places a MARKET order to open a position (POST /fapi/v1/order).
     * @param symbol   e.g. "BTCUSDT"
     * @param side     "BUY" (LONG entry) or "SELL" (SHORT entry)
     * @param quantity BTC amount formatted to 3 decimal places (e.g. "0.010")
     */
    public String placeMarketOrder(String symbol, String side, String quantity) throws Exception {
        String params = "symbol=" + symbol
                + "&side=" + side
                + "&type=MARKET"
                + "&quantity=" + quantity
                + "&timestamp=" + ts();
        return post("/fapi/v1/order", params + "&signature=" + sign(params));
    }

    /**
     * Closes an existing position with a MARKET order using reduceOnly=true.
     * Unlike placeMarketOrder, this guarantees the order only reduces (never flips) the position.
     * Must be used for all close operations (manual close, SL/TP hit).
     */
    public String closeWithMarket(String symbol, String side, String quantity) throws Exception {
        String params = "symbol=" + symbol
                + "&side=" + side
                + "&type=MARKET"
                + "&quantity=" + quantity
                + "&reduceOnly=true"
                + "&timestamp=" + ts();
        return post("/fapi/v1/order", params + "&signature=" + sign(params));
    }

    /**
     * Places a STOP_MARKET (SL) or TAKE_PROFIT_MARKET (TP) conditional order.
     * Uses reduceOnly=true with explicit quantity (compatible with all account types).
     * closePosition=true triggered error -4120 on some accounts.
     * Uses workingType=MARK_PRICE to avoid wick-triggered stops.
     *
     * @param symbol    e.g. "BTCUSDT"
     * @param side      "SELL" (to close a LONG), "BUY" (to close a SHORT)
     * @param type      "STOP_MARKET" or "TAKE_PROFIT_MARKET"
     * @param stopPrice trigger price (1 decimal place for BTCUSDT)
     * @param quantity  BTC quantity to close (e.g. "0.010")
     */
    public String placeCloseOrder(String symbol, String side, String type,
                                  double stopPrice, String quantity) throws Exception {
        String params = "symbol=" + symbol
                + "&side=" + side
                + "&type=" + type
                + "&stopPrice=" + String.format(Locale.US, "%.1f", stopPrice)
                + "&quantity=" + quantity
                + "&reduceOnly=true"
                + "&workingType=MARK_PRICE"
                + "&timestamp=" + ts();
        return post("/fapi/v1/order", params + "&signature=" + sign(params));
    }

    /**
     * Cancels all open orders for a symbol (DELETE /fapi/v1/allOpenOrders).
     * Called before opening a new position to clean up stale orders.
     */
    public String cancelAllOrders(String symbol) throws Exception {
        String params = "symbol=" + symbol + "&timestamp=" + ts();
        return delete("/fapi/v1/allOpenOrders", params + "&signature=" + sign(params));
    }

    /**
     * Returns current position risk for a symbol (GET /fapi/v2/positionRisk).
     * positionAmt > 0 → long; < 0 → short; = 0 → no position.
     */
    public String getPositionRisk(String symbol) throws Exception {
        String params = "symbol=" + symbol + "&timestamp=" + ts();
        return get("/fapi/v2/positionRisk", params + "&signature=" + sign(params));
    }

    /**
     * Returns full futures account info (GET /fapi/v2/account).
     * Includes available balance and all current positions.
     */
    public String getAccount() throws Exception {
        String params = "timestamp=" + ts();
        return get("/fapi/v2/account", params + "&signature=" + sign(params));
    }

    // ── Public market data (no auth, always live fapi.binance.com) ────────────

    /**
     * Returns current open interest for a symbol.
     * GET /fapi/v1/openInterest?symbol=BTCUSDT
     * {"openInterest":"12345.678","symbol":"BTCUSDT","time":1234567890}
     */
    public String getOpenInterest(String symbol) throws Exception {
        return getPublic("/fapi/v1/openInterest", "symbol=" + symbol);
    }

    /**
     * Returns premium index (mark price, last funding rate, next funding time).
     * GET /fapi/v1/premiumIndex?symbol=BTCUSDT
     * {"lastFundingRate":"0.00010000","nextFundingTime":1234567890,"markPrice":"..."}
     */
    public String getPremiumIndex(String symbol) throws Exception {
        return getPublic("/fapi/v1/premiumIndex", "symbol=" + symbol);
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private String post(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("X-MBX-APIKEY", apiKey)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(req, "POST " + path);
    }

    private String delete(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("X-MBX-APIKEY", apiKey)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(15))
                .method("DELETE", HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(req, "DELETE " + path);
    }

    private String get(String path, String query) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path + "?" + query))
                .header("X-MBX-APIKEY", apiKey)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        return send(req, "GET " + path);
    }

    /** Public GET — no API key, always uses live fapi.binance.com. */
    private String getPublic(String path, String query) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(LIVE_URL + path + "?" + query))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return send(req, "GET " + path);
    }

    private String send(HttpRequest req, String label) throws Exception {
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        LOG.debugf("[Futures] %s → HTTP %d: %s", label, resp.statusCode(), resp.body());
        if (resp.statusCode() >= 400) {
            throw new RuntimeException("Binance Futures " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }

    // ── HMAC-SHA256 signing ───────────────────────────────────────────────────

    String sign(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(64);
        for (byte b : raw) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private long ts() {
        return System.currentTimeMillis();
    }
}
