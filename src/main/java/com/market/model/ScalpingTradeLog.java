package com.market.model;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.List;

/**
 * Snapshot complet du signal + ordres réels au moment du placement d'un trade scalping.
 * Lié à ScalpingTrade via tradeId. Utilisé pour diagnostiquer les entrées perdantes.
 *
 * GET /api/scalping/logs           → 50 derniers logs (newest first)
 * GET /api/scalping/logs/{tradeId} → log d'un trade spécifique
 */
@Entity
@Table(name = "scalping_trade_log")
public class ScalpingTradeLog extends PanacheEntity {

    // ── Lien trade ────────────────────────────────────────────────────────────
    public Long    tradeId;
    public Instant loggedAt;

    // ── Décision ─────────────────────────────────────────────────────────────
    public String direction;
    public int    confidence;
    public double entryPrice;

    // ── Niveaux signal vs niveaux réels placés ────────────────────────────────
    /** TP1 calculé par le signal (1.0×ATR) */
    public double sigTp1;
    /** TP2 calculé par le signal (2.0×ATR) */
    public double sigTp2;
    /** SL calculé par le signal (0.6×ATR) */
    public double sigStopLoss;
    /** TP1 réellement placé sur Binance (recalculé sur fill price) */
    public double placedTp1;
    /** TP2 réellement suivi en Java */
    public double placedTp2;
    /** SL réellement placé sur Binance */
    public double placedSl;

    // ── Volatilité ───────────────────────────────────────────────────────────
    public double atr;
    public double atrPct;

    // ── Scores 3 piliers ─────────────────────────────────────────────────────
    public int pillar1Score;   // Multi-TF Alignment (max 40)
    public int pillar2Score;   // Momentum Quality  (max 40)
    public int pillar3Score;   // Volume & Order Flow (max 25)

    // ── Alignement multi-TF ──────────────────────────────────────────────────
    public int    longTfCount;
    public int    shortTfCount;
    public String trend15m;      // BULLISH | BEARISH | NEUTRAL
    public String bias5m;        // LONG | SHORT | NEUTRAL
    public String supertrendDirection;  // 1m micro

    // ── Indicateurs clés 1m ──────────────────────────────────────────────────
    public double rsi;           // RSI(14) sur 1m
    public double macdHistogram;
    public double adx;
    public double plusDI;
    public double minusDI;
    public String marketRegime;  // TREND | RANGE | NEUTRAL
    public double stochK;
    public double stochD;
    public double vwap;
    public double cvdPct;
    public String cvdTrend;
    public String marketStructure1m;
    public String bbState;
    public double bbWidth;
    public double volumeDeltaPct;
    public String volumeDeltaTrend;
    public double volumeRatio;

    // ── EMAs 1m ──────────────────────────────────────────────────────────────
    public double ema8;    // champ ema5 dans ScalpingSignal = EMA(8) v4
    public double ema13;
    public double ema21;

    // ── Contexte macro (5m/15m) ───────────────────────────────────────────────
    public double ema9_5m;
    public double ema21_5m;
    public double rsi14_5m;
    public double ema20_15m;
    public double ema50_15m;
    public double rsi14_15m;

    // ── Raisonnement complet ─────────────────────────────────────────────────
    @Column(length = 4000)
    public String reasoning;

    // ── Finders ──────────────────────────────────────────────────────────────

    public static List<ScalpingTradeLog> findRecent(int limit) {
        return find("ORDER BY loggedAt DESC").page(0, limit).list();
    }

    public static ScalpingTradeLog findByTradeId(long tradeId) {
        return find("tradeId", tradeId).firstResult();
    }
}
