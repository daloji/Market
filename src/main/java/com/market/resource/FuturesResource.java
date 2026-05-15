package com.market.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.market.model.Trade;
import com.market.service.BinanceAutoTradeService;
import com.market.service.BinanceFuturesService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.*;

/**
 * REST API for Binance Futures auto-trading.
 *
 * GET  /api/futures/status       — config, state, last result
 * POST /api/futures/enable       — enable auto-trading
 * POST /api/futures/disable      — disable auto-trading
 * POST /api/futures/trigger      — manually trigger check-and-trade
 * POST /api/futures/config       — update runtime settings
 * GET  /api/futures/positions    — live positions enriched with SL/TP from DB
 */
@Path("/api/futures")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class FuturesResource {

    @Inject BinanceAutoTradeService autoTrade;
    @Inject BinanceFuturesService   futures;

    private final ObjectMapper mapper = new ObjectMapper();

    // ── Status ────────────────────────────────────────────────────────────────

    @GET @Path("/status")
    public Response status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("configured",    futures.isConfigured());
        body.put("testnet",       futures.isTestnet());
        body.put("enabled",       autoTrade.isEnabled());
        body.put("minConfidence", autoTrade.getMinConfidence());
        body.put("amountUsdt",    autoTrade.getAmountUsdt());
        body.put("leverage",      autoTrade.getLeverage());
        body.put("slPct",         autoTrade.getSlPct());
        body.put("tpPct",         autoTrade.getTpPct());
        body.put("lastResult",    autoTrade.lastResult() != null ? autoTrade.lastResult() : Map.of());
        return Response.ok(body).build();
    }

    // ── Enable / Disable ──────────────────────────────────────────────────────

    @POST @Path("/enable")
    public Response enable() {
        if (!futures.isConfigured()) {
            return Response.status(400).entity(Map.of("error",
                "Clé API Binance Futures non configurée. " +
                "Ajoutez market.binance.futures.api-key et market.binance.futures.secret dans application.properties.")).build();
        }
        autoTrade.enable();
        return Response.ok(Map.of("enabled", true, "message", "Auto-trade activé ✅")).build();
    }

    @POST @Path("/disable")
    public Response disable() {
        autoTrade.disable();
        return Response.ok(Map.of("enabled", false, "message", "Auto-trade désactivé")).build();
    }

    // ── Manual trigger ────────────────────────────────────────────────────────

    @POST @Path("/trigger")
    public BinanceAutoTradeService.AutoTradeResult trigger() {
        return autoTrade.checkAndTrade();
    }

    // ── Config update ─────────────────────────────────────────────────────────

    /**
     * Update runtime auto-trade settings.
     * Only fields > 0 are applied; others are unchanged.
     */
    @POST @Path("/config")
    public Response config(ConfigRequest req) {
        if (req == null) return Response.status(400).entity(Map.of("error", "body required")).build();
        if (req.minConfidence > 0) autoTrade.setMinConfidence(req.minConfidence);
        if (req.amountUsdt   > 0) autoTrade.setAmountUsdt(req.amountUsdt);
        if (req.leverage     > 0) autoTrade.setLeverage(req.leverage);
        if (req.slPct        > 0) autoTrade.setSlPct(req.slPct);
        if (req.tpPct        > 0) autoTrade.setTpPct(req.tpPct);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("minConfidence", autoTrade.getMinConfidence());
        r.put("amountUsdt",    autoTrade.getAmountUsdt());
        r.put("leverage",      autoTrade.getLeverage());
        r.put("slPct",         autoTrade.getSlPct());
        r.put("tpPct",         autoTrade.getTpPct());
        return Response.ok(r).build();
    }

    // ── Live positions ────────────────────────────────────────────────────────

    /**
     * Returns open Binance Futures positions enriched with SL/TP from local DB.
     *
     * Each position object:
     *   symbol, direction, quantity, leverage, margin,
     *   entryPrice, markPrice, unrealizedPnl, pnlPct, liquidationPrice,
     *   sl, tp1, tradeId, openedAt (from DB if available)
     */
    @GET @Path("/positions")
    @Transactional
    public Response positions() {
        if (!futures.isConfigured()) {
            return Response.status(400).entity(Map.of("error", "API key not configured")).build();
        }
        try {
            String      binJson   = futures.getPositionRisk("BTCUSDT");
            JsonNode    arr       = mapper.readTree(binJson);
            List<Trade> openReal  = Trade.findActiveReal();
            List<Map<String, Object>> result = new ArrayList<>();

            if (arr.isArray()) {
                for (JsonNode pos : arr) {
                    double posAmt = pos.path("positionAmt").asDouble(0);
                    if (Math.abs(posAmt) < 0.0001) continue; // skip flat positions

                    String dir      = posAmt > 0 ? "LONG" : "SHORT";
                    double entry    = pos.path("entryPrice").asDouble(0);
                    double mark     = pos.path("markPrice").asDouble(0);
                    double unrealPnl= pos.path("unRealizedProfit").asDouble(0);
                    double liqPrice = pos.path("liquidationPrice").asDouble(0);
                    int    leverage = pos.path("leverage").asInt(1);
                    double notional = Math.abs(pos.path("notional").asDouble(0));
                    double margin   = leverage > 0 ? notional / leverage : notional;
                    double pnlPct   = margin > 0 ? unrealPnl / margin * 100 : 0;

                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("symbol",          pos.path("symbol").asText("BTCUSDT"));
                    view.put("direction",       dir);
                    view.put("quantity",        Math.abs(posAmt));
                    view.put("leverage",        leverage);
                    view.put("margin",          round2(margin));
                    view.put("entryPrice",      entry);
                    view.put("markPrice",       mark);
                    view.put("unrealizedPnl",   round2(unrealPnl));
                    view.put("pnlPct",          round2(pnlPct));
                    view.put("liquidationPrice",round2(liqPrice));

                    // Enrich with SL/TP from local DB (most recent matching direction)
                    Trade.Direction tradeDir = posAmt > 0 ? Trade.Direction.LONG : Trade.Direction.SHORT;
                    openReal.stream()
                            .filter(t -> t.direction == tradeDir)
                            .max(Comparator.comparing(t -> t.openedAt))
                            .ifPresent(t -> {
                                view.put("sl",       t.sl);
                                view.put("tp1",      t.tp1);
                                view.put("tradeId",  t.id);
                                view.put("openedAt", t.openedAt != null ? t.openedAt.toEpochMilli() : 0);
                            });

                    result.add(view);
                }
            }
            return Response.ok(result).build();
        } catch (Exception e) {
            return Response.serverError().entity(Map.of("error", e.getMessage())).build();
        }
    }

    // ── Request DTO ───────────────────────────────────────────────────────────

    public static class ConfigRequest {
        public int    minConfidence;
        public double amountUsdt;
        public int    leverage;
        public double slPct;
        public double tpPct;
    }

    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}

