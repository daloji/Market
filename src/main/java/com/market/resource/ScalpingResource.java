package com.market.resource;

import com.market.model.ScalpingTradeLog;
import com.market.service.BinanceFuturesService;
import com.market.service.BinanceScalpingTradeService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * REST API for Binance Futures scalping auto-trade (1m signals).
 *
 * GET  /api/scalping/status   — current config, state and last result
 * POST /api/scalping/enable   — enable auto-scalping (immediate first check)
 * POST /api/scalping/disable  — disable auto-scalping
 * POST /api/scalping/trigger  — manually trigger one check-and-trade cycle
 * POST /api/scalping/config   — update runtime settings (amount, TP%, SL%, leverage, minConf)
 */
@Path("/api/scalping")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ScalpingResource {

    @Inject BinanceScalpingTradeService scalping;
    @Inject BinanceFuturesService       futures;

    @GET @Path("/status")
    public Response status() {
        return Response.ok(scalping.statusMap()).build();
    }

    @POST @Path("/enable")
    public Response enable() {
        if (!futures.isConfigured()) {
            return Response.status(400).entity(Map.of("error",
                "Clé API Binance Futures non configurée. " +
                "Ajoutez market.binance.futures.api-key et market.binance.futures.secret.")).build();
        }
        scalping.enable();
        BinanceScalpingTradeService.ScalpResult first = scalping.checkAndTrade();
        return Response.ok(Map.of(
            "enabled",    true,
            "message",    "Auto-scalping activé ✅",
            "firstCheck", first
        )).build();
    }

    @POST @Path("/disable")
    public Response disable() {
        scalping.disable();
        return Response.ok(Map.of("enabled", false, "message", "Auto-scalping désactivé")).build();
    }

    @POST @Path("/trigger")
    public BinanceScalpingTradeService.ScalpResult trigger() {
        return scalping.checkAndTrade();
    }

    /**
     * Reconcile internal position state with Binance.
     * Use when a trade appears stuck as "open" in the UI but was already closed on Binance.
     * POST /api/scalping/sync
     */
    @POST @Path("/sync")
    public Response sync() {
        BinanceScalpingTradeService.ScalpResult result = scalping.syncWithBinance();
        return Response.ok(result).build();
    }

    /**
     * Force-places a trade in the given direction regardless of signal/cooldown.
     * For testing only — bypasses all gates.
     * POST /api/scalping/force/LONG  or  POST /api/scalping/force/SHORT
     */
    @POST @Path("/force/{direction}")
    public Response force(@PathParam("direction") String direction) {
        if (!"LONG".equalsIgnoreCase(direction) && !"SHORT".equalsIgnoreCase(direction)) {
            return Response.status(400).entity(Map.of("error", "direction must be LONG or SHORT")).build();
        }
        BinanceScalpingTradeService.ScalpResult result = scalping.forceExecute(direction);
        return Response.ok(result).build();
    }

    /** Simulates TP1 hit on active position — places real partial close on Binance (testnet). */
    @POST @Path("/sim-tp1")
    public Response simTp1() {
        return Response.ok(scalping.simTp1()).build();
    }

    /** Simulates TP2 hit after TP1 — places real final close on Binance (testnet). */
    @POST @Path("/sim-tp2")
    public Response simTp2() {
        return Response.ok(scalping.simTp2()).build();
    }

    @GET @Path("/diagnose")
    public BinanceScalpingTradeService.ScalpDiag diagnose() {
        return scalping.diagnose();
    }

    @GET @Path("/history")
    public java.util.List<BinanceScalpingTradeService.ScalpTrade> history() {
        return scalping.history();
    }

    /**
     * Logs de chaque analyse scalping — WAIT, skipped, placed.
     * GET /api/scalping/logs?limit=100&outcome=all   → tous les cycles
     * GET /api/scalping/logs?outcome=wait            → uniquement les WAIT
     * GET /api/scalping/logs?outcome=placed          → uniquement les trades placés
     * GET /api/scalping/logs?outcome=low_conf        → signaux rejetés pour confiance
     * Valeurs outcome: placed | wait | disabled | cooldown | loss_streak | low_conf | coordinator | pos_exists
     */
    @GET @Path("/logs")
    public java.util.List<ScalpingTradeLog> logs(
            @QueryParam("limit")   @DefaultValue("100") int    limit,
            @QueryParam("outcome") @DefaultValue("all") String outcome) {
        return scalping.signalLogs(limit, outcome);
    }

    /**
     * Log par son propre id (colonne id de scalping_signal_log).
     * GET /api/scalping/logs/{id}
     */
    @GET @Path("/logs/{id}")
    public Response logById(@PathParam("id") long id) {
        ScalpingTradeLog log = scalping.signalLogById(id);
        if (log == null) return Response.status(404).entity(Map.of("error", "log introuvable pour id=" + id)).build();
        return Response.ok(log).build();
    }

    /**
     * Log lié à un trade précis (tradeId = id de scalping_trade).
     * GET /api/scalping/logs/trade/{tradeId}
     */
    @GET @Path("/logs/trade/{tradeId}")
    public Response logByTradeId(@PathParam("tradeId") long tradeId) {
        ScalpingTradeLog log = scalping.signalLogByTradeId(tradeId);
        if (log == null) return Response.status(404).entity(Map.of("error", "log introuvable pour tradeId=" + tradeId)).build();
        return Response.ok(log).build();
    }

    /** Returns open orders on Binance for BTCUSDT — useful to verify SL/TP were placed */
    @GET @Path("/orders")
    public jakarta.ws.rs.core.Response openOrders() {
        try {
            String json = futures.getOpenOrders("BTCUSDT");
            return jakarta.ws.rs.core.Response.ok(json).type("application/json").build();
        } catch (Exception e) {
            return jakarta.ws.rs.core.Response.serverError()
                .entity(Map.of("error", e.getMessage())).build();
        }
    }

    /** Returns open algo (SL/TP) orders on Binance for BTCUSDT */
    @GET @Path("/algo-orders")
    public jakarta.ws.rs.core.Response openAlgoOrders() {
        try {
            String json = futures.getOpenAlgoOrders("BTCUSDT");
            return jakarta.ws.rs.core.Response.ok(json).type("application/json").build();
        } catch (Exception e) {
            return jakarta.ws.rs.core.Response.serverError()
                .entity(Map.of("error", e.getMessage())).build();
        }
    }

    @POST @Path("/config")
    public Response config(ConfigRequest req) {
        if (req == null) return Response.status(400).entity(Map.of("error", "body required")).build();
        if (req.minConfidence > 0)  scalping.setMinConfidence(req.minConfidence);
        if (req.amountUsdt    > 0)  scalping.setAmountUsdt(req.amountUsdt);
        if (req.leverage      > 0)  scalping.setLeverage(req.leverage);
        if (req.tpPct         > 0)  scalping.setTpPct(req.tpPct);
        if (req.slPct         > 0)  scalping.setSlPct(req.slPct);
        return Response.ok(scalping.statusMap()).build();
    }

    public static class ConfigRequest {
        public int    minConfidence;
        public double amountUsdt;
        public int    leverage;
        public double tpPct;
        public double slPct;
    }
}
