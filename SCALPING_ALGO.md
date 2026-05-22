# SCALPING ALGO — v4 "Confluence Sniper" (2026-05-22)

## Philosophie

Inspiré des traders institutionnels et scalpers professionnels : **3 piliers obligatoires** avant d'entrer.
Seuils calibrés pour maximiser la fréquence de trades tout en conservant un bon ratio risque/récompense.

## Architecture

```
GET /api/crypto/btc/scalping
    ↓
ScalpingAnalysisService.compute()
    ├─ 1m candles  (200 bars, Binance)
    ├─ 15m candles (100 bars) — macro trend
    ├─ 5m candles  (100 bars) — meso trend
    │
    ├─ GATE 1 : TTM Squeeze (BB dans KC) → WAIT
    ├─ GATE 2 : ATR(14) < 0.12%          → WAIT
    │
    ├─ PILIER 1 — Multi-TF Trend Alignment (max 40 pts)
    │   ├─ 15m EMA(20) vs EMA(50)  → +15 pts macro  (seuil 0.01%)
    │   ├─ 5m  EMA(9)  vs EMA(21)  → +15 pts meso   (seuil 0.01%)
    │   └─ 1m  SMA50 + Supertrend  → +10 pts micro
    │
    ├─ GATE 3 : TF alignment
    │   ├─ TFs conflictuels (LONG+SHORT)  → WAIT absolu
    │   ├─ 1/3 TF aligné (non-conflit)   → seuil 88 pts  ← NOUVEAU
    │   ├─ 2/3 TF alignés               → seuil 68 pts
    │   └─ 3/3 TF alignés               → seuil 60 pts
    │
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
ATR gate        : 0.08% minimum
MACD            : (12, 26, 9) standard
ATR période     : 7 (réactif au mouvement récent)
RSI période     : 14
Stoch           : (14, 3)
Supertrend      : (10, 3.0)
EMA 15m         : seuil 0.01% pour BULLISH/BEARISH (was 0.03%)
EMA 5m          : seuil 0.01% pour LONG/SHORT      (was 0.02%)

Seuils signal:
  3/3 TF alignés : 60 pts
  2/3 TF alignés : 68 pts  (was 75)
  1/3 TF aligné  : 88 pts  (was WAIT)
  TFs conflictuels: WAIT

TP1 = 1.0×ATR  (60% qty)
TP2 = 2.0×ATR  (40% qty)
SL  = 0.6×ATR
R:R = 1.67 (TP1), 3.33 (TP2)
```

## Logique décision

```
1. Compter TF alignés :
   longTfCount  = (15m=BULLISH ? 1:0) + (5m=LONG ? 1:0) + (1m above SMA50 AND ST=LONG ? 1:0)
   shortTfCount = (15m=BEARISH ? 1:0) + (5m=SHORT ? 1:0) + (1m below SMA50 AND ST=SHORT ? 1:0)

2. Si longTfCount >= 1 ET shortTfCount >= 1 → WAIT (TFs conflictuels)
   Si longTfCount == 0 ET shortTfCount == 0 → WAIT (tout neutre)

3. Threshold selon direction dominante:
   3/3 → 60 pts
   2/3 → 68 pts
   1/3 → 88 pts (exige momentum+volume très forts)

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
