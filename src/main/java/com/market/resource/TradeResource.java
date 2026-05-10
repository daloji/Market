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

    @Inject
    TradeService tradeService;

    // ─── Open a new trade ──────────────────────────────────────────────────────

    @POST
    @Transactional
    public Response openTrade(TradeRequest req) {
        if (req == null || req.amount <= 0 || req.entryPrice <= 0) {
            return Response.status(400).entity(Map.of("error", "amount and entryPrice are required")).build();
        }
        Trade.Direction dir;
        try {
            dir = Trade.Direction.valueOf(req.direction.toUpperCase());
        } catch (Exception e) {
            return Response.status(400).entity(Map.of("error", "direction must be LONG or SHORT")).build();
        }

        double atr = req.atr > 0 ? req.atr : req.entryPrice * 0.01;
        Trade trade = tradeService.openTrade(
                req.amount, dir, req.leverage > 0 ? req.leverage : 10,
                req.entryPrice, req.feeRate > 0 ? req.feeRate : 0.0004, atr);
        return Response.ok(toDTO(trade)).build();
    }

    // ─── List active trades ────────────────────────────────────────────────────

    @GET
    @Path("/active")
    public List<TradeDTO> getActive() {
        return tradeService.getActiveTrades().stream().map(this::toDTO).toList();
    }

    // ─── List all trades ───────────────────────────────────────────────────────

    @GET
    public List<TradeDTO> getAll() {
        return tradeService.getAllTrades().stream().map(this::toDTO).toList();
    }

    // ─── Get single trade ──────────────────────────────────────────────────────

    @GET
    @Path("/{id}")
    public Response getById(@PathParam("id") Long id) {
        Trade t = tradeService.getById(id);
        if (t == null) return Response.status(404).build();
        return Response.ok(toDTO(t)).build();
    }

    // ─── Close a trade ─────────────────────────────────────────────────────────

    @DELETE
    @Path("/{id}")
    @Transactional
    public Response closeTrade(@PathParam("id") Long id, @QueryParam("reason") String reason) {
        Trade t = tradeService.closeTrade(id, reason != null ? reason : "USER_CLOSED");
        if (t == null) return Response.status(404).build();
        return Response.ok(toDTO(t)).build();
    }

    // ─── DTOs ──────────────────────────────────────────────────────────────────

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
        d.openedAt     = t.openedAt != null ? t.openedAt.toEpochMilli() : 0;
        d.closedAt     = t.closedAt != null ? t.closedAt.toEpochMilli() : 0;
        d.status       = t.status.name();
        d.currentPrice = t.currentPrice;
        d.pnlUsd       = t.pnlUsd;
        d.pnlNet       = t.pnlNet;
        d.pnlPct       = t.pnlPct;
        d.feesTotal    = t.feesTotal;
        d.closeReason  = t.closeReason;
        return d;
    }

    // ─── Request / Response POJOs ──────────────────────────────────────────────

    public static class TradeRequest {
        public double amount;
        public String direction;
        public int    leverage;
        public double entryPrice;
        public double feeRate;
        public double atr;
    }

    public static class TradeDTO {
        public Long   id;
        public String direction;
        public double amount;
        public int    leverage;
        public double entryPrice;
        public double feeRate;
        public double tp1, tp2, tp3, sl, liq;
        public long   openedAt;
        public long   closedAt;
        public String status;
        public double currentPrice;
        public double pnlUsd;
        public double pnlNet;
        public double pnlPct;
        public double feesTotal;
        public String closeReason;
    }
}
