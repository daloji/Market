package com.market.resource;

import com.market.client.BinanceClient;
import com.market.model.BitcoinSignal;
import com.market.model.CandleDTO;
import com.market.model.ScalpingSignal;
import com.market.service.ScalpingAnalysisService;
import com.market.service.CryptoAnalysisService;
import com.market.service.TelegramAlertService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Path("/api/crypto")
@Produces(MediaType.APPLICATION_JSON)
public class CryptoResource {

    @Inject CryptoAnalysisService    cryptoService;
    @Inject ScalpingAnalysisService  scalpingService;
    @Inject TelegramAlertService     telegramAlertService;

    @Inject @RestClient BinanceClient binanceClient;

    /** Intraday signal with entry/TP/SL and last 100 candles for chart. */
    @GET
    @Path("/btc/signal")
    public BitcoinSignal getBtcSignal() {
        return cryptoService.getSignal();
    }

    /** Scalping signal based on 1m candles (RSI7, EMA5/13, MACD fast, volume delta). */
    @GET
    @Path("/btc/scalping")
    public ScalpingSignal getScalpingSignal() {
        return scalpingService.getSignal();
    }

    /** Raw candles for a given interval (for switching chart timeframe). */
    @GET
    @Path("/btc/candles")
    public List<CandleDTO> getCandles(
            @QueryParam("interval") @DefaultValue("1h")  String interval,
            @QueryParam("limit")    @DefaultValue("100") int    limit) {

        List<List<Object>> raw = binanceClient.getKlines("BTCUSDT", interval, Math.min(limit, 500));
        return raw.stream().map(k -> {
            long   time   = ((Number) k.get(0)).longValue() / 1000L;
            double open   = Double.parseDouble(k.get(1).toString());
            double high   = Double.parseDouble(k.get(2).toString());
            double low    = Double.parseDouble(k.get(3).toString());
            double close  = Double.parseDouble(k.get(4).toString());
            double volume = Double.parseDouble(k.get(5).toString());
            return new CandleDTO(time, open, high, low, close, volume);
        }).collect(Collectors.toList());
    }

    /**
     * Test endpoint — sends a Telegram alert with the current signal immediately,
     * bypassing the conviction/cooldown filters.
     * Call: POST /api/crypto/btc/telegram-test
     */
    @POST
    @Path("/btc/telegram-test")
    public Response testTelegram() {
        if (!telegramAlertService.isEnabled()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error",
                        "Telegram not configured. Set market.telegram.bot-token and market.telegram.chat-id in application.properties."))
                    .build();
        }
        BitcoinSignal signal = cryptoService.getSignal();
        if (signal.error != null) {
            return Response.serverError().entity(Map.of("error", signal.error)).build();
        }
        telegramAlertService.sendTest(signal);
        return Response.ok(Map.of(
            "status",     "sent",
            "direction",  signal.direction,
            "confidence", signal.confidence,
            "price",      signal.currentPrice
        )).build();
    }
}

