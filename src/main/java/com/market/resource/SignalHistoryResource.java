package com.market.resource;

import com.market.model.BitcoinSignalHistory;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * REST API for Bitcoin LONG/SHORT signal detection history.
 *
 * GET /api/signals/history?limit=50       — most recent N signals (LONG + SHORT)
 * GET /api/signals/history/long?limit=50  — LONG signals only
 * GET /api/signals/history/short?limit=50 — SHORT signals only
 */
@Path("/api/signals")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SignalHistoryResource {

    @GET
    @Path("/history")
    public List<BitcoinSignalHistory> history(@QueryParam("limit") @DefaultValue("50") int limit) {
        return BitcoinSignalHistory.findRecent(Math.min(limit, 200));
    }

    @GET
    @Path("/history/long")
    public List<BitcoinSignalHistory> longHistory(@QueryParam("limit") @DefaultValue("50") int limit) {
        return BitcoinSignalHistory.findByDirection("LONG", Math.min(limit, 200));
    }

    @GET
    @Path("/history/short")
    public List<BitcoinSignalHistory> shortHistory(@QueryParam("limit") @DefaultValue("50") int limit) {
        return BitcoinSignalHistory.findByDirection("SHORT", Math.min(limit, 200));
    }
}
