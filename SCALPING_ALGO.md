# SCALPING ALGO — v4 "Confluence Sniper" (2026-05-21)

## Philosophie

Inspiré des traders institutionnels et scalpers professionnels : **3 piliers obligatoires** avant d'entrer.
Moins de trades mais de meilleure qualité. Jamais trader contre le trend macro (15m).

## Architecture

```
GET /api/crypto/btc/scalping
    ↓
ScalpingAnalysisService.compute()
    ├─ 1m candles  (200 bars, Binance)
    ├─ 15m candles (100 bars) — NOUVEAU macro trend
    ├─ 5m candles  (100 bars) — meso trend
    │
    ├─ GATE 1 : TTM Squeeze (BB dans KC) → WAIT
    ├─ GATE 2 : ATR(14) < 0.20%          → WAIT
    │
    ├─ PILIER 1 — Multi-TF Trend Alignment (max 40 pts)
    │   ├─ 15m EMA(20) vs EMA(50)  → +15 pts macro
    │   ├─ 5m  EMA(9)  vs EMA(21)  → +15 pts meso
    │   └─ 1m  SMA50 + Supertrend  → +10 pts micro
    │
    ├─ GATE 3 : TF alignment
    │   ├─ 0/3 ou 1/3 TF alignés   → WAIT absolu
    │   ├─ 2/3 TF alignés          → seuil 75 pts
    │   └─ 3/3 TF alignés          → seuil 60 pts
    │
    ├─ PILIER 2 — Momentum Quality (max ~40 pts)
    │   ├─ RSI(14) zone momentum    → +10–20 pts
    │   ├─ MACD(12,26,9) histogram  → +6–10 pts
    │   ├─ Stochastic(14,3) crossover → +6–10 pts
    │   └─ RSI divergence           → +8 pts
    │
    ├─ PILIER 3 — Volume & Order Flow (max 25 pts)
    │   ├─ Taker buy delta          → +8–15 pts
    │   ├─ CVD slope (20 bars)      → +5 pts
    │   └─ Volume ratio vs moyenne  → +2–5 pts
    │
    ├─ BONUS VWAP                   → +5 pts (bounce avec momentum)
    ├─ Market Structure (pivots)    → +5–8 pts
    └─ Body dernière bougie         → +3 pts
```

## Paramètres v4

```
ATR gate        : 0.20% minimum (was 0.15%)
MACD            : (12, 26, 9) standard (was 6,13,4)
ATR période     : 14 (was 7) — plus stable
RSI période     : 14 (was 7) — lecture momentum pro
Stoch           : (14, 3) (was 5,3)
Supertrend      : (10, 3.0) (was 7,3.0)

Seuils signal:
  3/3 TF alignés : 60 pts
  2/3 TF alignés : 75 pts

TP1 = 1.0×ATR  (60% qty)
TP2 = 2.0×ATR  (40% qty)
SL  = 0.6×ATR  (was 0.5×ATR)
R:R = 1.67 (TP1), 3.33 (TP2)
```

## Logique décision

```
1. Compter TF alignés :
   longTfCount  = (15m=BULLISH ? 1:0) + (5m=LONG ? 1:0) + (1m above SMA50 AND ST=LONG ? 1:0)
   shortTfCount = (15m=BEARISH ? 1:0) + (5m=SHORT ? 1:0) + (1m below SMA50 AND ST=SHORT ? 1:0)

2. Si max(longTfCount, shortTfCount) <= 1 → WAIT

3. Threshold:
   3/3 → 60 pts
   2/3 → 75 pts

4. Calculer score direction dominante
   Si score >= threshold → LONG ou SHORT
   Sinon → WAIT
```

## Changements vs v3

| Aspect | v3 | v4 |
|--------|----|----|
| Timeframes | 1m + 5m bias | 1m + 5m + **15m** |
| RSI | (7), oversold/overbought | **(14)**, momentum zone |
| MACD | (6,13,4) fast | **(12,26,9)** standard |
| ATR gate | 0.15% | **0.20%** |
| Seuil | 55/72 (SMA50) | **60/75** (nb TFs) |
| Logic | 14 indicateurs additifs | **3 piliers + gate TF** |
| Stoch | (5,3) | **(14,3)** |
| SL | 0.5×ATR | **0.6×ATR** |
| Score max | ~290 pts | **~115 pts** (plus réaliste) |

## Anti-patterns évités

- Plus de trade contre le trend 15m (gate absolu)
- RSI oversold en downtrend = signal court, pas long
- Volume trop faible → moins de signal
- Compression TTM Squeeze = wait (inchangé)

## Indicateurs WAIT normaux

Sur WAIT : ADX, Stoch, CVD, Supertrend, MarketStructure, VWAP, MACD = 0/null dans la réponse. Normal.
