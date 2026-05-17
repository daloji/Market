package com.market.service;

import com.market.client.BinanceClient;
import com.market.model.BitcoinSignal;
import com.market.service.BinanceFuturesService;
import com.market.service.CryptoAnalysisService;
import com.market.service.TelegramAlertService;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Integration tests for CryptoAnalysisService.
 *
 * BinanceClient is mocked to avoid real HTTP calls. Tests verify:
 *   - getSignal() always returns a non-null BitcoinSignal
 *   - The signal carries expected structural fields
 *   - Error handling: when Binance returns no data, signal.error is set and the signal is non-null
 *   - Caching: a second call within the TTL returns the same object
 */
@QuarkusTest
class CryptoAnalysisServiceTest {

    @Inject
    CryptoAnalysisService analysisService;

    @InjectMock
    @RestClient
    BinanceClient binanceClient;

    @InjectMock
    BinanceFuturesService futuresService;

    @InjectMock
    TelegramAlertService telegramAlertService;

    @BeforeEach
    void setup() throws Exception {
        // Reset signal cache between tests for isolation
        CryptoAnalysisService real = analysisService instanceof ClientProxy
                ? (CryptoAnalysisService) ((ClientProxy) analysisService).arc_contextualInstance()
                : analysisService;
        Field fCached = CryptoAnalysisService.class.getDeclaredField("cached");
        fCached.setAccessible(true);
        fCached.set(real, null);
        Field fCachedAt = CryptoAnalysisService.class.getDeclaredField("cachedAt");
        fCachedAt.setAccessible(true);
        fCachedAt.set(real, 0L);

        // Default: futures service not configured → OI/funding calls are skipped
        when(futuresService.isConfigured()).thenReturn(false);
        // Default: no candles → error signal
        when(binanceClient.getKlines(anyString(), anyString(), anyInt()))
                .thenReturn(Collections.emptyList());
    }

    // ── getSignal() returns non-null even on error ────────────────────────────

    @Test
    void getSignal_binanceReturnsNull_signalIsNotNull() {
        when(binanceClient.getKlines(anyString(), anyString(), anyInt())).thenReturn(null);

        BitcoinSignal signal = analysisService.getSignal();

        assertNotNull(signal, "getSignal() must never return null");
    }

    @Test
    void getSignal_binanceReturnsEmptyList_signalHasError() {
        when(binanceClient.getKlines(anyString(), anyString(), anyInt()))
                .thenReturn(Collections.emptyList());

        BitcoinSignal signal = analysisService.getSignal();

        assertNotNull(signal, "getSignal() must never return null");
        assertNotNull(signal.error, "Signal should carry an error when data is insufficient");
    }

    @Test
    void getSignal_binanceThrows_signalHasError() {
        when(binanceClient.getKlines(anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("Connection refused"));

        BitcoinSignal signal = analysisService.getSignal();

        assertNotNull(signal, "getSignal() must never return null even on exception");
        assertNotNull(signal.error, "Signal should carry an error message on exception");
    }

    // ── Signal structure ──────────────────────────────────────────────────────

    @Test
    void getSignal_signalTimestampIsSet() {
        when(binanceClient.getKlines(anyString(), anyString(), anyInt()))
                .thenReturn(Collections.emptyList());

        BitcoinSignal signal = analysisService.getSignal();

        assertNotNull(signal.timestamp, "Signal timestamp should always be populated");
    }

    @Test
    void getSignal_signalLeverageIsSet() {
        when(binanceClient.getKlines(anyString(), anyString(), anyInt()))
                .thenReturn(Collections.emptyList());

        BitcoinSignal signal = analysisService.getSignal();

        assertEquals(10, signal.leverage, "Default leverage should be 10 for BTC futures");
    }

    // ── Caching ───────────────────────────────────────────────────────────────

    @Test
    void getSignal_twoCallsWithinTtl_returnsSameCachedInstance() {
        // Provide enough candles (30+ required) for a successful compute,
        // so the result gets cached.
        List<List<Object>> candles = buildMinimalCandles(35);
        when(binanceClient.getKlines(anyString(), anyString(), anyInt())).thenReturn(candles);

        BitcoinSignal first  = analysisService.getSignal();
        BitcoinSignal second = analysisService.getSignal();

        assertNotNull(first);
        assertNotNull(second);
        // Within cache TTL (15 s) both calls return the same cached object
        assertSame(first, second, "Within TTL the same cached BitcoinSignal instance should be returned");
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /**
     * Builds {@code count} minimal kline rows that satisfy the Binance format.
     * Each row: [openTime, open, high, low, close, volume, closeTime, quoteVol, trades, ...]
     */
    private List<List<Object>> buildMinimalCandles(int count) {
        List<List<Object>> result = new java.util.ArrayList<>(count);
        long now = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            long ts = now - (long)(count - i) * 3_600_000L;
            result.add(java.util.Arrays.asList(
                    (Object) ts,          // openTime
                    "50000.0",            // open
                    "51000.0",            // high
                    "49000.0",            // low
                    "50500.0",            // close
                    "100.0",              // volume
                    ts + 3_599_999L,      // closeTime
                    "5000000.0",          // quoteAssetVolume
                    100,                  // numberOfTrades
                    "50.0",               // takerBuyBaseVol
                    "2500000.0",          // takerBuyQuoteVol
                    "0"                   // ignore
            ));
        }
        return result;
    }
}
