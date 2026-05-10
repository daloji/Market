package com.market.resource;

import com.market.client.BinanceClient;
import com.market.model.BitcoinSignal;
import com.market.model.CandleDTO;
import com.market.service.CryptoAnalysisService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.List;
import java.util.stream.Collectors;

@Path("/api/crypto")
@Produces(MediaType.APPLICATION_JSON)
public class CryptoResource {

    @Inject CryptoAnalysisService cryptoService;

    @Inject @RestClient BinanceClient binanceClient;

    /** Intraday signal with entry/TP/SL and last 100 candles for chart. */
    @GET
    @Path("/btc/signal")
    public BitcoinSignal getBtcSignal() {
        return cryptoService.getSignal();
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
}

