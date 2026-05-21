package com.market.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.Locale;
import java.util.Map;
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

    /** Cached hedge mode flag: true = Hedge Mode (dual), false = One-Way, null = not yet detected. */
    private volatile Boolean hedgeModeCache = null;

    /** Clock offset (ms) = Binance server time − local System.currentTimeMillis(). */
    private volatile long    timeOffset  = 0;
    /** True once the first successful time sync has completed. */
    private volatile boolean timeSynced  = false;

    public boolean isConfigured() {
        return !apiKey.isBlank() && !secret.isBlank();
    }

    public boolean isTestnet() {
        return testnet;
    }

    /**
     * Returns true if the account uses Hedge Mode (dual position side).
     * In Hedge Mode, orders must use positionSide=LONG/SHORT instead of reduceOnly=true.
     * Result is cached after first call (resets to null on reconnect via {@link #resetHedgeModeCache()}).
     */
    public boolean isHedgeMode() {
        if (hedgeModeCache != null) return hedgeModeCache;
        try {
            String params = "timestamp=" + ts() + "&recvWindow=10000";
            String json   = get("/fapi/v1/positionSide/dual", params + "&signature=" + sign(params));
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            hedgeModeCache = root.path("dualSidePosition").asBoolean(false);
            LOG.infof("[Futures] Mode position détecté: %s", hedgeModeCache ? "Hedge Mode" : "One-Way");
        } catch (Exception e) {
            LOG.warnf("[Futures] Détection mode position échouée (%s) — One-Way supposé", e.getMessage());
            hedgeModeCache = false;
        }
        return hedgeModeCache;
    }

    /** Clears the cached hedge mode so it is re-detected on next call. */
    public void resetHedgeModeCache() {
        hedgeModeCache = null;
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
        String params = "symbol=" + symbol + "&leverage=" + leverage + tsSuffix();
        return post("/fapi/v1/leverage", params + "&signature=" + sign(params));
    }

    /**
     * Places a MARKET order to open a position (POST /fapi/v1/order).
     * @param symbol       e.g. "BTCUSDT"
     * @param side         "BUY" (LONG entry) or "SELL" (SHORT entry)
     * @param quantity     BTC amount formatted to 3 decimal places (e.g. "0.010")
     * @param positionSide "LONG" or "SHORT" for Hedge Mode accounts; null for One-Way mode
     */
    public String placeMarketOrder(String symbol, String side, String quantity,
                                   String positionSide) throws Exception {
        String params = "symbol=" + symbol
                + "&side=" + side
                + "&type=MARKET"
                + "&quantity=" + quantity;
        if (positionSide != null && !positionSide.isBlank()) {
            params += "&positionSide=" + positionSide;
        }
        params += tsSuffix();
        return post("/fapi/v1/order", params + "&signature=" + sign(params));
    }

    /**
     * Closes an existing position with a MARKET order.
     * One-Way mode: uses reduceOnly=true to guarantee only a reduce (never a flip).
     * Hedge Mode: uses positionSide=LONG/SHORT instead (reduceOnly not allowed).
     *
     * @param positionSide "LONG" or "SHORT" for Hedge Mode accounts; null for One-Way mode
     */
    public String closeWithMarket(String symbol, String side, String quantity,
                                  String positionSide) throws Exception {
        String params = "symbol=" + symbol
                + "&side=" + side
                + "&type=MARKET"
                + "&quantity=" + quantity;
        if (positionSide != null && !positionSide.isBlank()) {
            params += "&positionSide=" + positionSide;
        } else {
            params += "&reduceOnly=true";
        }
        params += tsSuffix();
        return post("/fapi/v1/order", params + "&signature=" + sign(params));
    }

    /**
     * Places a STOP_MARKET (SL) or TAKE_PROFIT_MARKET (TP) conditional order.
     * Uses the Algo Order API (POST /fapi/v1/algoOrder) with algoType=CONDITIONAL,
     * as required by Binance since STOP_MARKET / TAKE_PROFIT_MARKET are no longer
     * accepted on /fapi/v1/order (error -4120).
     *
     * One-Way mode:
     *   - STOP_MARKET   → closePosition=true  (closes full remaining position — safe for SL after partial TPs)
     *   - TAKE_PROFIT_MARKET → reduceOnly=true + explicit quantity (enables partial closes for TP1/TP2)
     * Hedge Mode: positionSide=LONG/SHORT + explicit quantity (closePosition not available).
     * Uses workingType=MARK_PRICE to avoid wick-triggered stops.
     *
     * @param symbol       e.g. "BTCUSDT"
     * @param side         "SELL" (to close a LONG), "BUY" (to close a SHORT)
     * @param type         "STOP_MARKET" or "TAKE_PROFIT_MARKET"
     * @param stopPrice    trigger price (1 decimal place for BTCUSDT)
     * @param quantity     BTC quantity — used in Hedge Mode and for One-Way TP partial closes
     * @param positionSide "LONG" or "SHORT" for Hedge Mode; null for One-Way mode
     */
    public String placeCloseOrder(String symbol, String side, String type,
                                  double stopPrice, String quantity,
                                  String positionSide) throws Exception {
        String params = buildCloseOrderBody(symbol, side, type, stopPrice, quantity, positionSide);
        params += tsSuffix();
        return post("/fapi/v1/algoOrder", params + "&signature=" + sign(params));
    }

    /**
     * Builds the request body for a conditional algo (SL/TP) order, without timestamp/signature.
     * Package-private for unit testing.
     */
    String buildCloseOrderBody(String symbol, String side, String type, double stopPrice,
                               String quantity, String positionSide) {
        String params = "symbol=" + symbol
                + "&side=" + side
                + "&algoType=CONDITIONAL"
                + "&type=" + type
                + "&triggerPrice=" + String.format(Locale.US, "%.1f", stopPrice);
        if (positionSide != null && !positionSide.isBlank()) {
            // Hedge Mode: quantity + positionSide required
            params += "&quantity=" + quantity
                    + "&positionSide=" + positionSide;
        } else if ("STOP_MARKET".equals(type)) {
            // One-Way SL: closePosition=true closes whatever remains after partial TP fills
            params += "&closePosition=true";
        } else {
            // One-Way TP: reduceOnly=true with explicit partial quantity (TP1=60%, TP2=40%)
            params += "&quantity=" + quantity + "&reduceOnly=true";
        }
        params += "&workingType=MARK_PRICE";
        return params;
    }

    /**
     * Cancels all open regular orders for a symbol (DELETE /fapi/v1/allOpenOrders).
     * Also cancels all open algo (SL/TP) orders via DELETE /fapi/v1/algoOpenOrders.
     * Called before opening a new position to clean up stale orders.
     */
    public String cancelAllOrders(String symbol) throws Exception {
        String params = "symbol=" + symbol + tsSuffix();
        String result = delete("/fapi/v1/allOpenOrders", params + "&signature=" + sign(params));
        try {
            cancelAllAlgoOrders(symbol);
        } catch (Exception e) {
            LOG.warnf("[Futures] cancelAllAlgoOrders skipped: %s", e.getMessage());
        }
        return result;
    }

    /**
     * Cancels all open algo (conditional SL/TP) orders for a symbol.
     * Uses DELETE /fapi/v1/algoOpenOrders — a single bulk-cancel endpoint.
     */
    public void cancelAllAlgoOrders(String symbol) throws Exception {
        String params = "symbol=" + symbol + tsSuffix();
        delete("/fapi/v1/algoOpenOrders", params + "&signature=" + sign(params));
        LOG.debugf("[Futures] All algo orders cancelled for %s", symbol);
    }

    /**
     * Returns all open orders for a symbol (GET /fapi/v1/openOrders).
     * Each order has: orderId, type (STOP_MARKET, TAKE_PROFIT_MARKET...), side, stopPrice, status.
     */
    public String getOpenOrders(String symbol) throws Exception {
        String params = "symbol=" + symbol + tsSuffix();
        return get("/fapi/v1/openOrders", params + "&signature=" + sign(params));
    }

    /**
     * Returns all open algo (conditional SL/TP) orders for a symbol.
     * GET /fapi/v1/algoOpenOrders
     */
    public String getOpenAlgoOrders(String symbol) throws Exception {
        String params = "symbol=" + symbol + tsSuffix();
        return get("/fapi/v1/openAlgoOrders", params + "&signature=" + sign(params));
    }

    /**
     * Returns current position risk for a symbol (GET /fapi/v2/positionRisk).
     * positionAmt > 0 → long; < 0 → short; = 0 → no position.
     */
    public String getPositionRisk(String symbol) throws Exception {
        String params = "symbol=" + symbol + tsSuffix();
        return get("/fapi/v2/positionRisk", params + "&signature=" + sign(params));
    }

    /**
     * Returns full futures account info (GET /fapi/v2/account).
     * Includes available balance and all current positions.
     */
    public String getAccount() throws Exception {
        String params = "timestamp=" + ts() + "&recvWindow=10000";
        return get("/fapi/v2/account", params + "&signature=" + sign(params));
    }

    /**
     * Returns available USDT balance for futures trading.
     * Parses availableBalance from /fapi/v2/account assets array.
     * Returns -1 if parsing fails.
     */
    public double getAvailableBalance() throws Exception {
        String json = getAccount();
        com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        com.fasterxml.jackson.databind.JsonNode assets = root.path("assets");
        if (assets.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode asset : assets) {
                if ("USDT".equals(asset.path("asset").asText())) {
                    return asset.path("availableBalance").asDouble(-1);
                }
            }
        }
        return -1;
    }

    /**
     * Returns both wallet balance (total equity) and available balance for USDT.
     * Map keys: "walletBalance", "availableBalance", "unrealizedProfit"
     */
    public Map<String, Double> getUsdtBalances() {
        Map<String, Double> result = new java.util.LinkedHashMap<>();
        try {
            String json = getAccount();
            com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            com.fasterxml.jackson.databind.JsonNode assets = root.path("assets");
            if (assets.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode asset : assets) {
                    if ("USDT".equals(asset.path("asset").asText())) {
                        result.put("walletBalance",    asset.path("walletBalance").asDouble(0));
                        result.put("availableBalance", asset.path("availableBalance").asDouble(0));
                        result.put("unrealizedProfit", asset.path("unrealizedProfit").asDouble(0));
                        return result;
                    }
                }
            }
        } catch (Exception e) {
            LOG.warnf("getUsdtBalances failed: %s", e.getMessage());
        }
        return result;
    }

    /**
     * Returns the most recent user trades for a symbol (GET /fapi/v1/userTrades).
     * Used to recover the actual fill price when a SL/TP fires asynchronously on Binance
     * before the Java monitoring cycle detects the close.
     */
    public String getRecentUserTrades(String symbol, int limit) throws Exception {
        String params = "symbol=" + symbol + "&limit=" + limit + tsSuffix();
        return get("/fapi/v1/userTrades", params + "&signature=" + sign(params));
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
     * Returns open interest history for a symbol.
     * GET /futures/data/openInterestHist?symbol=BTCUSDT&period=1h&limit=N
     * Returns array of {"sumOpenInterest":"...","sumOpenInterestValue":"...","timestamp":...}
     * Valid periods: 5m, 15m, 30m, 1h, 2h, 4h, 6h, 12h, 1d
     */
    public String getOpenInterestHistory(String symbol, String period, int limit) throws Exception {
        return getPublic("/futures/data/openInterestHist",
                "symbol=" + symbol + "&period=" + period + "&limit=" + limit);
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
            String body = resp.body();
            if (body.contains("-1021")) {
                // Timestamp out of recvWindow — force re-sync so next request succeeds
                LOG.warnf("[Futures] ⚠ -1021 timestamp désynchronisé — re-synchro horloge");
                timeSynced = false;
                syncServerTime();
            }
            throw new RuntimeException("Binance Futures " + resp.statusCode() + ": " + body);
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

    /**
     * Returns current timestamp corrected for clock drift vs Binance servers.
     * Syncs lazily on first call via GET /fapi/v1/time.
     */
    private long ts() {
        if (!timeSynced) syncServerTime();
        return System.currentTimeMillis() + timeOffset;
    }

    /**
     * Appends timestamp and recvWindow to signed request params.
     * recvWindow=10000 gives a 10s window, protecting against minor clock drift
     * between successive re-syncs.
     */
    private String tsSuffix() {
        return "&timestamp=" + ts() + "&recvWindow=10000";
    }

    /**
     * Fetches Binance server time (GET /fapi/v1/time) and computes the offset
     * between the local clock and the Binance server clock.
     * Called lazily on first signed request and again after any -1021 error.
     */
    public void syncServerTime() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + "/fapi/v1/time"))
                    .timeout(Duration.ofSeconds(5))
                    .GET().build();
            long t0   = System.currentTimeMillis();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            long t1   = System.currentTimeMillis();
            if (resp.statusCode() == 200) {
                com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(resp.body());
                long serverTime = node.path("serverTime").asLong();
                timeOffset = serverTime - (t0 + t1) / 2;
                LOG.infof("[Futures] Horloge synchronisée: offset=%+dms (local≈%d, server=%d)",
                    timeOffset, (t0 + t1) / 2, serverTime);
            }
        } catch (Exception e) {
            LOG.warnf("[Futures] Synchro horloge échouée: %s — offset inchangé (%+dms)",
                e.getMessage(), timeOffset);
        }
        timeSynced = true; // mark done even on failure to avoid hammering the endpoint
    }
}
