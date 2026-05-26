# SCALPING ALGO — v4 "Confluence Sniper" (2026-05-26)

## Philosophie

Inspiré des traders institutionnels et scalpers professionnels : **3 piliers obligatoires** avant d'entrer.
Un trade n'est valide que si **au moins 2 des 3 timeframes** sont alignés dans la même direction.

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
    ├─ GATE 2 : ATR < 0.20%             → WAIT (volatilité insuffisante)
    │
    ├─ PILIER 1 — Multi-TF Trend Alignment (max 40 pts)
    │   ├─ 15m EMA(20) vs EMA(50)  → +15 pts macro
    │   ├─ 5m  EMA(9)  vs EMA(21)  → +15 pts meso
    │   └─ 1m  SMA50 + Supertrend  → +10 pts micro
    │
    ├─ GATE 3 : TF alignment (avant scoring)
    │   ├─ TFs conflictuels (long + short simultanés) → WAIT absolu
    │   ├─ ≤1/3 TF alignés                           → WAIT absolu
    │   ├─ 2/3 TF alignés                            → seuil 75 pts
    │   └─ 3/3 TF alignés                            → seuil 60 pts
    │
    ├─ PILIER 2 — Momentum Quality (max 40 pts)
    │   ├─ RSI(14) zone momentum          → +5–20 pts
    │   ├─ MACD(12,26,9) histogram        → +5–15 pts
    │   ├─ Stoch(14,3)                    → +5–10 pts
    │   └─ Supertrend(10,3) + Market Structure → +5–8 pts
    │
    ├─ PILIER 3 — Volume & Order Flow (max 25 pts)
    │   ├─ Taker buy delta (20 bars)      → +8–15 pts
    │   ├─ CVD slope (20 bars)            → +5 pts
    │   └─ Volume ratio vs moyenne        → +2–5 pts
    │
    ├─ BONUS VWAP                   → +5 pts (bounce avec momentum)
    ├─ Market Structure (pivots)    → +5–8 pts
    └─ Body dernière bougie         → +3 pts
```

## Paramètres v4 (appliqués au code depuis 2026-05-26)

```
ATR gate        : 0.20% minimum (volatilité 1m insuffisante → WAIT)
MACD            : (12, 26, 9) standard
ATR période     : max(ATR7, ATR14) — capture pics récents ET volatilité soutenue
RSI période     : 14
Stoch           : (14, 3)
Supertrend      : (10, 3.0)
EMA 15m         : seuil 0.01% pour BULLISH/BEARISH
EMA 5m          : seuil 0.01% pour LONG/SHORT

Seuils signal:
  3/3 TF alignés : 60 pts
  2/3 TF alignés : 75 pts
  ≤1/3 TF aligné : WAIT absolu (même score très élevé → pas de trade)
  TFs conflictuels: WAIT absolu

TP/SL (ATR-based, calculés dans ScalpingAnalysisService.populateTargets) :
  TP1 = filledPrice ± 1.0×ATR  (60% qty, ordre TAKE_PROFIT_MARKET Binance)
  TP2 = filledPrice ± 2.0×ATR  (40% qty, surveillé côté Java)
  SL  = filledPrice ∓ 0.6×ATR  (ordre STOP_MARKET Binance)
  R:R = 1.67 (TP1), 3.33 (TP2)

  ⚠ Le SL ET le TP sont ATR-based. La config slPct/tpPct n'est utilisée
    qu'en fallback si sig.atr == 0 (cas d'erreur).
```

## Logique décision

```
1. Compter TF alignés :
   longTfCount  = (15m=BULLISH ? 1:0) + (5m=LONG ? 1:0) + (1m above SMA50 AND ST=LONG ? 1:0)
   shortTfCount = (15m=BEARISH ? 1:0) + (5m=SHORT ? 1:0) + (1m below SMA50 AND ST=SHORT ? 1:0)

2. Si longTfCount >= 1 ET shortTfCount >= 1 → WAIT (TFs conflictuels)
   Si longTfCount == 0 ET shortTfCount == 0 → WAIT (tout neutre)
   Si max(longTfCount, shortTfCount) == 1   → WAIT absolu

3. Threshold selon TF count dominant :
   3/3 → 60 pts
   2/3 → 75 pts

4. Calculer score piliers sur la direction dominante
   Si score >= threshold → LONG ou SHORT (confidence = min(100, score))
   Sinon → WAIT
```

## TP/SL — séquence d'ordres Binance

```
execute(sig, dir, filledPrice):
  ① Annuler ordres ouverts (cancelAllOrders)
  ② setLeverage
  ③ placeMarketOrder  → récupère filledPrice réel
  ④ placeCloseOrder STOP_MARKET      slPrice = filledPrice ∓ 0.6×sig.atr
  ⑤ placeCloseOrder TAKE_PROFIT_MARKET  tp1Price = filledPrice ± 1.0×sig.atr (60% qty)
     TP2 (40% qty) → Java monitor : closeWithMarket quand price atteint tp2Price

  canSplit = qty >= 0.002 BTC — si trop petit, mode single-TP automatique
```

Le TP2 est géré côté Java (pas sur Binance) pour éviter que le déclenchement de TP1
n'annule TP2 via le mécanisme OCO de Binance.

## Surveillance active (checkAndTrade — toutes les minutes)

```
Si position ouverte :
  1. Vérifier que Binance détient encore la position
     → Si absent : reconcileClosedPosition() → fetchLastBinanceFillPrice() → inferCloseReason()
  2. Vérifier SL (Java side-check en parallèle de l'ordre Binance)
  3. Vérifier TP1 → si atteint : closePartial(60%)
  4. Si TP1 hit : surveiller TP2

Si aucune position :
  Evaluer signal → WAIT / placed / skipped (cooldown, conf, désactivé…)
  → Chaque décision est loggée dans scalping_signal_log
```

## Protection des pertes

```
COOLDOWN_MS      = 10 min après chaque clôture
LOSS_STREAK_LIMIT = 2 SL consécutifs → pause 30 min
```

## Indicateurs WAIT normaux

Sur WAIT (avant GATE 2 ou 3) : ADX, Stoch, CVD, Supertrend, MarketStructure, VWAP, MACD = 0/null.
Normal — ces indicateurs ne sont calculés qu'après les gates TTM Squeeze et ATR.

## Changements v3 → v4

| Aspect | v3 | v4 (actuel) |
|--------|----|----|
| Timeframes | 1m + 5m bias | 1m + 5m + **15m** (gate absolu) |
| RSI | (7), oversold/overbought | **(14)**, momentum zone 50–70 / 28–48 |
| MACD | (6,13,4) fast | **(12,26,9)** standard |
| ATR gate | 0.15% | **0.20%** |
| Seuil 2/3 TF | 68 pts | **75 pts** |
| Seuil 1/3 TF | 88 pts | **WAIT absolu** |
| SL calcul | 0.15% fixe du prix | **0.6×ATR** (ATR-based, cohérent avec TP) |
| TP calcul | 0.30% fixe / sig.tp1 | **1.0×ATR recalculé sur filledPrice** |
| Score max | ~290 pts (additif) | **~115 pts** (3 piliers structurés) |
| Log analyses | Aucun | **scalping_signal_log** (toutes décisions) |

## Log des analyses — scalping_signal_log

Chaque cycle `checkAndTrade()` persiste une ligne dans `scalping_signal_log` :

| outcome | Signification |
|---------|---------------|
| `placed` | Trade ouvert — tradeId + placedTp1/2/sl renseignés |
| `wait` | Signal WAIT (ATR gate, TTM Squeeze, TF insuffisants, score bas…) |
| `disabled` | Auto-scalping désactivé |
| `cooldown` | Cooldown 10 min actif |
| `loss_streak` | Pause 30 min après 2 SL consécutifs |
| `low_conf` | Signal LONG/SHORT mais confidence < seuil |
| `coordinator` | Trade classique (swing) occupe le verrou |
| `pos_exists` | Position Binance déjà ouverte |

Chaque ligne contient tous les indicateurs (ATR, piliers, TF counts, RSI, MACD, ADX,
Stoch, VWAP, CVD, Supertrend, EMAs 1m/5m/15m, reasoning complet).

API :
```
GET /api/scalping/logs                  → 100 derniers cycles
GET /api/scalping/logs?outcome=wait     → tous les WAIT (pourquoi bloqué)
GET /api/scalping/logs?outcome=placed   → uniquement les trades
GET /api/scalping/logs/{id}            → log par id
GET /api/scalping/logs/trade/{tid}     → log lié à un tradeId
```
