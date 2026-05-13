package com.market.service;

import com.market.model.RecommendationSignal;
import com.market.model.Stock;
import com.market.model.StockQuote;
import com.market.model.StockRecommendation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class RecommendationService {

    private static final Logger LOG = Logger.getLogger(RecommendationService.class);
    private static final int MIN_QUOTES = 35; // enough for MACD(26) + signal(9)

    @Inject TechnicalAnalysisService analysis;
    @Inject AlertService alertService;

    /**
     * Computes and persists a fresh {@link StockRecommendation} for the given
     * symbol. Returns {@code null} if there is not enough historical data yet.
     * Triggers a BUY email alert when the signal transitions from non-BUY → BUY.
     */
    @Transactional
    public StockRecommendation generateRecommendation(String symbol) {
        List<StockQuote> quotes = StockQuote.findRecentBySymbol(symbol, 60);

        if (quotes.size() < MIN_QUOTES) {
            LOG.warnf("Not enough data for %s (%d quotes, need %d)", symbol, quotes.size(), MIN_QUOTES);
            return null;
        }

        // Reverse to chronological order (oldest → newest)
        Collections.reverse(quotes);

        List<Double> closes  = quotes.stream().map(q -> q.close).collect(Collectors.toList());
        List<Long>   volumes = quotes.stream()
                .map(q -> q.volume != null ? q.volume : 0L).collect(Collectors.toList());
        List<Double> volumesD = volumes.stream().map(Long::doubleValue).collect(Collectors.toList());

        int qn = quotes.size();
        double[] highs  = new double[qn];
        double[] lows   = new double[qn];
        double[] closesArr = new double[qn];
        for (int i = 0; i < qn; i++) {
            highs[i]     = quotes.get(i).high  != null ? quotes.get(i).high  : quotes.get(i).close;
            lows[i]      = quotes.get(i).low   != null ? quotes.get(i).low   : quotes.get(i).close;
            closesArr[i] = quotes.get(i).close;
        }

        double currentPrice  = closes.get(closes.size() - 1);
        long   currentVolume = volumes.get(volumes.size() - 1);

        double rsi       = analysis.calculateRSI(closes, 14);
        double sma20     = analysis.calculateSMA(closes, 20);
        double sma50     = analysis.calculateSMA(closes, 50);
        double avgVolume = analysis.calculateAverageVolume(volumes, 20);

        double[] macd      = analysis.calculateMACD(closes);
        double[] bollinger = analysis.calculateBollingerBands(closes, 20);
        double[] adxArr    = analysis.calculateADX(highs, lows, closesArr, 14);
        double[] stoch     = analysis.calculateStochastic(highs, lows, closesArr, 14, 3);

        int baseScore = analysis.calculateScore(rsi, currentPrice, sma20, sma50,
                currentVolume, avgVolume);

        // Stochastic adjustment (±10 pts)
        double stochK = stoch[0];
        int stochBonus = 0;
        if      (stochK < 20) stochBonus = +10;
        else if (stochK < 35) stochBonus = +5;
        else if (stochK > 80) stochBonus = -10;
        else if (stochK > 65) stochBonus = -5;

        // ADX: pull score towards neutral when market is ranging
        int score = baseScore + stochBonus;
        double adx = adxArr[0];
        if      (adx < 15) score = (int)(score * 0.65 + 50 * 0.35);
        else if (adx < 22) score = (int)(score * 0.85 + 50 * 0.15);
        score = Math.max(0, Math.min(100, score));

        RecommendationSignal signal;
        if      (score >= 65) signal = RecommendationSignal.BUY;
        else if (score <= 35) signal = RecommendationSignal.SELL;
        else                  signal = RecommendationSignal.HOLD;

        // ── Capture previous signal before persisting ─────────────────────────
        StockRecommendation previous = StockRecommendation.findLatestBySymbol(symbol);

        StockRecommendation rec = new StockRecommendation();
        rec.symbol       = symbol;
        Stock stock = Stock.findBySymbol(symbol);
        rec.stockName    = stock != null ? stock.name : symbol;
        rec.timestamp    = LocalDateTime.now();
        rec.signal       = signal;
        rec.score        = score;
        rec.currentPrice = round2(currentPrice);

        // Daily change: compare last two quotes
        if (closes.size() >= 2) {
            double prevClose = closes.get(closes.size() - 2);
            rec.previousClose = round2(prevClose);
            if (prevClose != 0) {
                rec.dailyChangePercent = round2((currentPrice - prevClose) / prevClose * 100);
            }
        }

        rec.rsi          = round2(rsi);
        rec.sma20        = round2(sma20);
        rec.sma50        = round2(sma50);

        // MACD
        rec.macdLine      = round4(macd[0]);
        rec.macdSignal    = round4(macd[1]);
        rec.macdHistogram = round4(macd[2]);

        // Bollinger
        rec.bollingerUpper = round2(bollinger[0]);
        rec.bollingerLower = round2(bollinger[2]);
        double bandWidth   = bollinger[0] - bollinger[2];
        rec.bollingerPosition = bandWidth > 0
                ? round2((currentPrice - bollinger[2]) / bandWidth)
                : 0.5;

        // ADX + Stochastic
        rec.adx    = round2(adxArr[0]);
        rec.plusDI = round2(adxArr[1]);
        rec.minusDI= round2(adxArr[2]);
        rec.stochK = round2(stoch[0]);
        rec.stochD = round2(stoch[1]);

        rec.reasons = buildReasons(rsi, currentPrice, sma20, sma50,
                currentVolume, avgVolume, macd, bollinger, adxArr, stoch);
        rec.persist();

        LOG.infof("[%s] %s  score=%d  RSI=%.1f  MACD=%.4f  price=%.2f",
                signal, symbol, score, rsi, macd[0], currentPrice);

        // ── BUY transition alert ───────────────────────────────────────────────
        if (signal == RecommendationSignal.BUY
                && (previous == null || previous.signal != RecommendationSignal.BUY)) {
            alertService.sendBuyAlert(rec);
        }

        return rec;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String buildReasons(double rsi, double price, double sma20, double sma50,
                                long volume, double avgVolume,
                                double[] macd, double[] bollinger,
                                double[] adxArr, double[] stoch) {
        List<String> parts = new ArrayList<>();

        // RSI
        if      (rsi < 30) parts.add(String.format("RSI oversold (%.1f)", rsi));
        else if (rsi > 70) parts.add(String.format("RSI overbought (%.1f)", rsi));
        else               parts.add(String.format("RSI neutre (%.1f)", rsi));

        // Trend
        if      (price > sma20 && sma20 > sma50) parts.add("Uptrend: price > SMA20 > SMA50");
        else if (price < sma20 && sma20 < sma50) parts.add("Downtrend: price < SMA20 < SMA50");
        else if (price > sma20)                  parts.add("Haussier CT: price > SMA20");
        else                                     parts.add("Sous SMA20 — prudence");

        // MACD
        if (macd[2] > 0)       parts.add("MACD haussier (histogramme > 0)");
        else if (macd[2] < 0)  parts.add("MACD baissier (histogramme < 0)");

        // Bollinger
        double bandWidth = bollinger[0] - bollinger[2];
        if (bandWidth > 0) {
            double pos = (price - bollinger[2]) / bandWidth;
            if      (pos < 0.2) parts.add("Prix près de la bande basse Bollinger");
            else if (pos > 0.8) parts.add("Prix près de la bande haute Bollinger");
        }

        // Volume
        if (avgVolume > 0 && (volume / avgVolume) >= 1.5) {
            parts.add(String.format("Volume élevé (%.1fx moy)", volume / avgVolume));
        }

        // ADX
        double adx = adxArr[0];
        if      (adx > 40) parts.add(String.format("ADX=%.1f (tendance forte)", adx));
        else if (adx > 22) parts.add(String.format("ADX=%.1f (tendance modérée)", adx));
        else               parts.add(String.format("ADX=%.1f (marché en range)", adx));

        // Stochastic
        double sk = stoch[0];
        if      (sk < 20) parts.add(String.format("Stoch survendu (%%K=%.1f)", sk));
        else if (sk > 80) parts.add(String.format("Stoch suracheté (%%K=%.1f)", sk));

        return String.join("; ", parts);
    }

    private double round2(double v) { return Math.round(v * 100.0)   / 100.0; }
    private double round4(double v) { return Math.round(v * 10000.0) / 10000.0; }
}

