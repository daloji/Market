package com.market.service;

import com.market.model.BitcoinSignal;
import com.market.model.BitcoinSignalHistory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import java.time.Instant;

/**
 * Records LONG/SHORT signal detections with their full indicator snapshot.
 * Called from BinanceAutoTradeService whenever a directional signal passes the confidence filter.
 */
@ApplicationScoped
public class SignalHistoryService {

    private static final Logger LOG = Logger.getLogger(SignalHistoryService.class);

    /**
     * Persists a LONG or SHORT signal with all its technical indicator values.
     * WAIT signals are silently ignored.
     *
     * @param signal         the BitcoinSignal as returned by CryptoAnalysisService
     * @param tradeTriggered whether a real Binance Futures trade was placed
     * @param skipReason     why no trade was placed (null if trade was placed)
     */
    @Transactional
    public void record(BitcoinSignal signal, boolean tradeTriggered, String skipReason) {
        if (signal == null || signal.error != null) return;
        if (!"LONG".equals(signal.direction) && !"SHORT".equals(signal.direction)) return;

        BitcoinSignalHistory h = new BitcoinSignalHistory();
        h.detectedAt    = Instant.now();
        h.direction     = signal.direction;
        h.confidence    = signal.confidence;
        h.price         = signal.currentPrice;

        h.rsi           = signal.rsi;
        h.ema9          = signal.ema9;
        h.ema21         = signal.ema21;

        h.macdLine      = signal.macdLine;
        h.macdSignal    = signal.macdSignal;
        h.macdHistogram = signal.macdHistogram;

        h.bollUpper     = signal.bollingerUpper;
        h.bollMid       = signal.bollingerMid;
        h.bollLower     = signal.bollingerLower;
        h.bollPosition  = signal.bollingerPosition;

        h.adx           = signal.adx;
        h.plusDI        = signal.plusDI;
        h.minusDI       = signal.minusDI;

        h.stochK        = signal.stochK;
        h.stochD        = signal.stochD;
        h.obvSlope      = signal.obvSlope;
        h.atr           = signal.atr;

        h.tradeTriggered = tradeTriggered;
        h.skipReason     = skipReason != null && skipReason.length() > 500
                           ? skipReason.substring(0, 500) : skipReason;
        h.reasoning      = signal.reasoning != null && signal.reasoning.length() > 1000
                           ? signal.reasoning.substring(0, 1000) : signal.reasoning;

        h.persist();
        LOG.infof("[SignalHistory] Recorded %s conf=%d%% @ %.2f — trade=%s",
                h.direction, h.confidence, h.price, tradeTriggered);
    }
}
