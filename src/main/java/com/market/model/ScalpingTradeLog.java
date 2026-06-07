package com.market.model;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.List;

/**
 * Snapshot complet de chaque cycle d'analyse scalping, quelle que soit la décision.
 *
 * outcome : "placed"      — trade ouvert sur Binance
 *           "wait"        — signal WAIT (ATR gate, TTM Squeeze, TF insuffisants, score bas…)
 *           "disabled"    — auto-scalping désactivé
 *           "cooldown"    — cooldown post-trade actif
 *           "loss_streak" — pause après série de pertes
 *           "low_conf"    — confiance signal < seuil
 *           "coordinator" — trade classique bloque le lock
 *           "pos_exists"  — position Binance déjà ouverte
 *
 * GET /api/scalping/logs               → 100 derniers (newest first)
 * GET /api/scalping/logs?outcome=wait  → filtre par outcome
 * GET /api/scalping/logs/{id}          → un log par son propre id
 * GET /api/scalping/logs/trade/{tid}   → log d'un trade précis
 */
@Entity
@Table(name = "scalping_signal_log", indexes = {
    @Index(name = "idx_ssl_logged_at", columnList = "loggedAt DESC"),
    @Index(name = "idx_ssl_trade_id",  columnList = "tradeId")
})
public class ScalpingTradeLog extends PanacheEntity {

    // ── Outcome ───────────────────────────────────────────────────────────────
    public String  outcome;        // placed | wait | disabled | cooldown | loss_streak | low_conf | coordinator | pos_exists
    public String  outcomeDetail;  // message lisible (raison complète)
    public Instant loggedAt;

    // ── Lien trade (set uniquement si outcome=placed) ─────────────────────────
    public Long    tradeId;

    // ── Signal ────────────────────────────────────────────────────────────────
    public String direction;   // LONG | SHORT | WAIT
    public int    confidence;
    public double currentPrice;

    // ── Niveaux signal (ATR-based, calculés par ScalpingAnalysisService) ──────
    public double sigTp1;
    public double sigTp2;
    public double sigStopLoss;

    // ── Niveaux réels placés sur Binance (0 si non tradé) ────────────────────
    public double entryPrice;
    public double placedTp1;
    public double placedTp2;
    public double placedSl;

    // ── Volatilité ───────────────────────────────────────────────────────────
    public double atr;
    public double atrPct;

    // ── Scores 3 piliers ─────────────────────────────────────────────────────
    public int pillar1Score;
    public int pillar2Score;
    public int pillar3Score;

    // ── Alignement multi-TF ──────────────────────────────────────────────────
    public int    longTfCount;
    public int    shortTfCount;
    public String trend15m;             // BULLISH | BEARISH | NEUTRAL
    public String bias5m;               // LONG | SHORT | NEUTRAL
    public String supertrendDirection;  // 1m micro

    // ── Indicateurs 1m ───────────────────────────────────────────────────────
    public double rsi;
    public double macdHistogram;
    public double adx;
    public double plusDI;
    public double minusDI;
    public String marketRegime;         // TREND | RANGE | NEUTRAL
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
    public double ema8;    // ema5 field = EMA(8) en v4
    public double ema13;
    public double ema21;

    // ── Contexte 5m / 15m ────────────────────────────────────────────────────
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

    public static List<ScalpingTradeLog> findRecentByOutcome(String outcome, int limit) {
        return find("outcome = ?1 ORDER BY loggedAt DESC", outcome).page(0, limit).list();
    }

    public static ScalpingTradeLog findByTradeId(long tradeId) {
        return find("tradeId", tradeId).firstResult();
    }

    public static List<ScalpingTradeLog> findPlacedAfter(java.time.Instant since) {
        return find("outcome = 'placed' AND tradeId IS NOT NULL AND loggedAt >= ?1 ORDER BY loggedAt DESC",
            since).list();
    }

    public static List<ScalpingTradeLog> findWaitAfter(java.time.Instant since) {
        return find("outcome IN ('wait', 'low_conf') AND loggedAt >= ?1", since).list();
    }
}
