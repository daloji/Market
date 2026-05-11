package com.market.resource;

import com.market.model.Trade;
import com.market.service.TradeService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

@Path("/api/trades")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TradeResource {

    @Inject TradeService tradeService;

    // ══════════════════════  SIMULATION  ══════════════════════════════════════

    @POST
    @Transactional
    public Response openTrade(TradeRequest req) {
        return doOpen(req, "SIMULATION");
    }

    @GET @Path("/active")
    public List<TradeDTO> getActive() {
        return tradeService.getActiveTrades().stream().map(this::toDTO).toList();
    }

    @GET @Path("/history")
    public List<TradeDTO> getHistory() {
        return tradeService.getClosedTrades().stream().map(this::toDTO).toList();
    }

    // ══════════════════════  REAL TRADES  ═════════════════════════════════════

    @POST @Path("/real")
    @Transactional
    public Response openRealTrade(TradeRequest req) {
        return doOpen(req, "REAL");
    }

    @GET @Path("/real/active")
    public List<TradeDTO> getRealActive() {
        return tradeService.getActiveReal().stream().map(this::toDTO).toList();
    }

    @GET @Path("/real/history")
    public List<TradeDTO> getRealHistory() {
        return tradeService.getClosedReal().stream().map(this::toDTO).toList();
    }

    // ══════════════════════  SHARED  ══════════════════════════════════════════

    @GET @Path("/all/history")
    public List<TradeDTO> getAllHistory() {
        return tradeService.getAllClosed().stream().map(this::toDTO).toList();
    }

    @GET @Path("/{id}")
    public Response getById(@PathParam("id") Long id) {
        Trade t = tradeService.getById(id);
        if (t == null) return Response.status(404).build();
        return Response.ok(toDTO(t)).build();
    }

    @DELETE @Path("/{id}")
    @Transactional
    public Response closeTrade(@PathParam("id") Long id,
                               @QueryParam("reason") String reason,
                               @QueryParam("closePrice") @DefaultValue("0") double closePrice) {
        Trade t = tradeService.closeTrade(id,
                reason != null ? reason : "USER_CLOSED", closePrice);
        if (t == null) return Response.status(404).build();
        return Response.ok(toDTO(t)).build();
    }

    // ─── Shared open helper ────────────────────────────────────────────────────

    private Response doOpen(TradeRequest req, String type) {
        if (req == null || req.amount <= 0 || req.entryPrice <= 0)
            return Response.status(400).entity(Map.of("error", "amount and entryPrice are required")).build();
        Trade.Direction dir;
        try { dir = Trade.Direction.valueOf(req.direction.toUpperCase()); }
        catch (Exception e) { return Response.status(400).entity(Map.of("error", "direction must be LONG or SHORT")).build(); }

        double atr = req.atr > 0 ? req.atr : req.entryPrice * 0.01;
        Trade trade = tradeService.openTrade(
                req.amount, dir, req.leverage > 0 ? req.leverage : 1,
                req.entryPrice, req.feeRate > 0 ? req.feeRate : 0.0004, atr,
                req.sl, req.tp, type, req.broker, req.symbol, req.note);
        return Response.ok(toDTO(trade)).build();
    }

    // ─── DTO mapping ──────────────────────────────────────────────────────────

    private TradeDTO toDTO(Trade t) {
        TradeDTO d = new TradeDTO();
        d.id           = t.id;
        d.direction    = t.direction.name();
        d.amount       = t.amount;
        d.leverage     = t.leverage;
        d.entryPrice   = t.entryPrice;
        d.feeRate      = t.feeRate;
        d.tp1          = t.tp1;
        d.tp2          = t.tp2;
        d.tp3          = t.tp3;
        d.sl           = t.sl;
        d.liq          = t.liq;
        d.openedAt     = t.openedAt  != null ? t.openedAt.toEpochMilli()  : 0;
        d.closedAt     = t.closedAt  != null ? t.closedAt.toEpochMilli()  : 0;
        d.status       = t.status.name();
        d.currentPrice = t.currentPrice;
        d.pnlUsd       = t.pnlUsd;
        d.pnlNet       = t.pnlNet;
        d.pnlPct       = t.pnlPct;
        d.feesTotal    = t.feesTotal;
        d.closeReason  = t.closeReason;
        d.tradeType    = t.tradeType;
        d.broker       = t.broker;
        d.symbol       = t.symbol;
        d.note         = t.note;
        return d;
    }

    // ─── Request / Response POJOs ─────────────────────────────────────────────

    public static class TradeRequest {
        public double amount;
        public String direction;
        public int    leverage;
        public double entryPrice;
        public double feeRate;
        public double atr;
        public double sl;
        public double tp;
        public String broker;
        public String symbol;
        public String note;
    }

    public static class TradeDTO {
        public Long   id;
        public String direction;
        public double amount;
        public int    leverage;
        public double entryPrice;
        public double feeRate;
        public double tp1, tp2, tp3, sl, liq;
        public long   openedAt, closedAt;
        public String status;
        public double currentPrice;
        public double pnlUsd, pnlNet, pnlPct, feesTotal;
        public String closeReason;
        public String tradeType;
        public String broker;
        public String symbol;
        public String note;
    }
}

