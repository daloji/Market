# SCALPING ALGO — v5 "Confluence Sniper" (2026-06-07)

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
    ├─ GATE 1 : TTM Squeeze (BB dans KC)              → WAIT
    ├─ GATE 2 : ATR < max(0.08%, medianTR50×50%)      → WAIT (adaptatif + hard floor)
    ├─ GATE 3 : ADX(14) < 28                          → WAIT (marché en range)
    │
    ├─ PILIER 1 — Multi-TF Trend Alignment (max 40 pts)
    │   ├─ 15m EMA(20) vs EMA(50)  → +15 pts macro
    │   ├─ 5m  EMA(9)  vs EMA(21)  → +15 pts meso
    │   └─ 1m  SMA50 + Supertrend  → +10 pts micro (partial : +5)
    │
    ├─ GATE 4 : TF alignment (après scoring P1)
    │   ├─ TFs conflictuels (long + short simultanés) → WAIT absolu
    │   ├─ ≤1/3 TF alignés                           → WAIT absolu
    │   ├─ 2/3 TF alignés                            → seuil 75 pts
    │   └─ 3/3 TF alignés                            → seuil 60 pts
    │
    ├─ GATE 5 : volumeRatio < 1.0                    → WAIT (volume sous la moyenne)
    ├─ GATE 6 : MarketStructure1m contredit direction → WAIT
    │
    ├─ PILIER 2 — Momentum Quality (max ~46 pts avec divergence)
    │   ├─ RSI(14) zone momentum          → +5–20 pts
    │   ├─ MACD(12,26,9) histogram        → +6–10 pts
    │   ├─ Stoch(14,3)                    → +6–10 pts
    │   └─ Divergence RSI                 → +8 pts
    │
    ├─ PILIER 3 — Volume & Order Flow (max 25 pts)
    │   ├─ Taker buy delta (20 bars)      → +8–15 pts
    │   ├─ CVD slope (20 bars)            → +5 pts
    │   └─ Volume ratio vs moyenne        → +2–5 pts
    │
    ├─ BONUS VWAP                   → +5 pts (bounce avec momentum RSI slope)
    ├─ Market Structure (pivots)    → +5–8 pts (BULL_TREND/BEAR_TREND/BREAKOUT)
    └─ Corps dernière bougie        → +3 pts
```

## Paramètres v5 (code source — 2026-06-07)

```
ATR gate        : max(plancher 0.08% du prix, médiane TR(50) × 50%)
                  Adaptatif : s'ajuste à la volatilité récente
ADX gate        : 28 minimum (marché RANGE → WAIT)
MACD            : (12, 26, 9) standard
ATR période     : max(ATR7, ATR14) — capture pics récents ET volatilité soutenue
RSI période     : 14
Stoch           : (14, 3)
Supertrend      : (10, 3.0)
EMA 15m         : EMA(20) vs EMA(50) — seuil 0.01% pour BULLISH/BEARISH
EMA 5m          : EMA(9) vs EMA(21) — seuil 0.01% pour LONG/SHORT

Seuils signal:
  3/3 TF alignés : 60 pts
  2/3 TF alignés : 75 pts
  ≤1/3 TF aligné : WAIT absolu (même score très élevé → pas de trade)
  TFs conflictuels: WAIT absolu

TP/SL (ATR-based, recalculés sur le filledPrice réel) :
  TP1 = filledPrice ± 1.3×ATR  (60% qty, TAKE_PROFIT_MARKET Binance)
  TP2 = filledPrice ± 2.6×ATR  (40% qty, surveillé côté Java)
  SL  = filledPrice ∓ 0.8×ATR  (STOP_MARKET Binance, qty totale)
  R:R ≈ 1.625 (TP1) · 3.25 (TP2)

  ⚠ Le SL ET le TP sont entièrement ATR-based.
    slPct/tpPct (config) ne servent qu'en fallback si sig.atr == 0.
```

## Scoring — détail par composante

### PILIER 1 — Multi-TF Trend Alignment (max 40 pts)

| Composante | Indicateur | Points |
|------------|-----------|--------|
| Macro 15m | EMA(20) > EMA(50) (seuil 0.01%) | +15 long / +15 short |
| Meso 5m | EMA(9) > EMA(21) (seuil 0.01%) | +15 long / +15 short |
| Micro 1m | SMA(50) + Supertrend(10,3) alignés | +10 |
| Micro 1m partiel | Un seul aligne | +5 |

### PILIER 2 — Momentum Quality (max ~46 pts)

| Composante | Condition | Points |
|------------|-----------|--------|
| RSI(14) zone bull | 52–72 | +10 à +20 (linéaire) |
| RSI(14) oversold | < 30 | +12 long |
| RSI(14) neutre bull | 40–52 | +5 long |
| RSI(14) zone bear | 28–48 | +10 à +20 (linéaire) |
| RSI(14) overbought | > 60 | +12 short |
| MACD histogram positif | histogram > 0 + accélération | +10 ; sans accél. | +6 |
| Stoch K>D (bull) | K > D, K dans 30–80 | +10 (K<50) ou +6 |
| Divergence RSI bullish/bearish | détectée | +8 |

### PILIER 3 — Volume & Order Flow (max 25 pts)

| Composante | Condition | Points |
|------------|-----------|--------|
| Taker buy delta | > 62% buy | +15 long |
| Taker buy delta | 54–62% buy | +8 long |
| Taker buy delta | < 38% sell | +15 short |
| Taker buy delta | 38–46% sell | +8 short |
| CVD slope (20 bars) | pente > 12% ou < -12% | +5 |
| Volume ratio | ≥ 1.3× moyenne | +5 (symétrique) |
| Volume ratio | 1.0–1.3× | +2 (symétrique) |

### BONUS

| Composante | Condition | Points |
|------------|-----------|--------|
| VWAP bounce | Prix à ±0.25% du VWAP + RSI slope confirme | +5 |
| Market Structure | BULL_TREND / BEAR_TREND | ±8 |
| Market Structure | BREAKOUT_UP / BREAKOUT_DOWN | ±5 |
| Corps bougie | Dernière bougie haussière / baissière | ±3 |

**Score max théorique** : 40 (P1) + 46 (P2) + 25 (P3) + 5 (VWAP) + 8 (MS) + 3 (bougie) = **127 pts**
Un trade passe avec 60 pts (3/3 TF) ou 75 pts (2/3 TF).

## Logique décision

```
1. Compter TF alignés :
   longTfCount  = (15m EMA20>EMA50 ?) + (5m EMA9>EMA21 ?) + (prix>SMA50_1m AND ST=LONG ?)
   shortTfCount = idem sens inverse

2. Si longTfCount >= 1 ET shortTfCount >= 1 → WAIT (TFs conflictuels)
   Si max(longTfCount, shortTfCount) <= 1   → WAIT absolu

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
  ① cancelAllOrders + cancelAllAlgoOrders
  ② setLeverage
  ③ placeMarketOrder  → récupère filledPrice réel (getOrder + polling si avgPrice=0)
  ④ placeCloseOrder STOP_MARKET       slPrice = filledPrice ∓ 0.8×sig.atr
  ⑤ placeCloseOrder TAKE_PROFIT_MARKET  tp1 = filledPrice ± 1.3×sig.atr (60% qty)
  TP2 (40% qty) → Java monitor (closeWithMarket quand price ≥/≤ tp2Price)

  canSplit = qty >= 0.002 BTC — si trop petit, single-TP automatique
```

Le TP2 est géré côté Java pour éviter que le déclenchement de TP1 n'annule TP2
via le mécanisme OCO de Binance.

## Surveillance active (checkAndTrade — toutes les minutes)

```
Si position ouverte :
  1. Vérifier que Binance détient encore la position (getPositionRisk)
     → Si absent : reconcileClosedPosition() → fetchFillsAfter() → inferCloseReason()
  2. Side-check SL Java (sécurité, en parallèle de l'ordre Binance)
  3. Vérifier TP1 → si atteint : closeWithMarket(60%), lock activeTp1Pnl
  4. Si TP1 hit : surveiller TP2 (closeWithMarket 40% si price ≥/≤ tp2)

Si aucune position :
  Évaluer signal → WAIT / placed / skipped (cooldown, conf, désactivé…)
  → Chaque décision persistée dans scalping_signal_log
```

## Protection des pertes

```
COOLDOWN_MS       = 10 min après chaque clôture
LOSS_STREAK_LIMIT = 2 SL consécutifs → pause 30 min (lossStreakCoolUntil)
```

## Gestion des codes erreur Binance

| Code | Signification | Comportement |
|------|---------------|--------------|
| `-2022` | ReduceOnly rejected — position déjà fermée | Traité comme fermeture → P&L calculé |
| `-4003` | Quantity ≤ 0 | Identique à -2022 |
| `-1021` | Timestamp out of recvWindow | Re-synchro horloge automatique |

## Indicateurs WAIT normaux

Sur WAIT (avant GATE 3/ADX ou GATE 4/TF) : ADX, Stoch, CVD, Supertrend,
MarketStructure, VWAP, MACD = 0/null dans la réponse API.
Normal — calculés seulement après les gates TTM Squeeze et ATR.

## Log des analyses — scalping_signal_log

Chaque cycle `checkAndTrade()` persiste une ligne dans `scalping_signal_log` :

| outcome | Signification |
|---------|---------------|
| `placed` | Trade ouvert — tradeId + placedTp1/2/sl renseignés |
| `wait` | Signal WAIT (ATR gate, TTM Squeeze, ADX, TF insuffisants, score bas…) |
| `disabled` | Auto-scalping désactivé |
| `cooldown` | Cooldown 10 min actif |
| `loss_streak` | Pause 30 min après 2 SL consécutifs |
| `low_conf` | Signal LONG/SHORT mais confidence < seuil |
| `coordinator` | Trade classique (swing) occupe le verrou |
| `pos_exists` | Position Binance déjà ouverte |

Chaque ligne contient : ATR, piliers 1/2/3, TF counts, RSI, MACD, ADX, Stoch,
VWAP, CVD, Supertrend, EMAs 1m/5m/15m, reasoning complet (jusqu'à 4000 chars).

## API REST scalping

```
GET  /api/crypto/btc/scalping          → signal complet + indicateurs
GET  /api/scalping/status              → état position active, wallet, cooldown
POST /api/scalping/enable              → activer l'auto-trading
POST /api/scalping/disable             → désactiver
POST /api/scalping/trigger             → déclencher un cycle manuellement
POST /api/scalping/sync                → resync position si état incohérent
POST /api/scalping/config              → modifier amountUsdt, leverage, minConf, tpPct, slPct
POST /api/scalping/force/{LONG|SHORT}  → forcer une entrée (bypass toutes gates)
POST /api/scalping/sim-tp1             → simuler TP1 (fermeture partielle 60%)
POST /api/scalping/sim-tp2             → simuler TP2 après TP1
GET  /api/scalping/diagnose            → checklist complète conditions de trade
GET  /api/scalping/history             → 100 derniers trades (in-memory + DB)
GET  /api/scalping/orders              → ordres ouverts BTCUSDT sur Binance
GET  /api/scalping/algo-orders         → ordres algo SL/TP ouverts
GET  /api/scalping/logs                → 100 derniers cycles d'analyse
GET  /api/scalping/logs?outcome=wait   → filtrer (wait/placed/cooldown/low_conf…)
GET  /api/scalping/logs/{id}           → log par son id
GET  /api/scalping/logs/trade/{tid}    → log lié à un tradeId
GET  /api/scalping/analytics?days=30   → win rate empirique par indicateur/heure/pilier
GET  /api/scalping/reconcile-history?days=7 → comparaison local vs fills Binance
```

## Analytics — `/api/scalping/analytics`

Analyse empirique des trades fermés : pour chaque dimension (ADX, RSI, CVD, CVD slope,
volume ratio, heure UTC, TF alignment, piliers 1/2/3, confidence), retourne :
- `count` — nombre de trades dans ce bucket
- `winRate` — % de trades positifs (pnlNet > 0)
- `avgPnlNet` — PnL net moyen en $
- `totalPnlNet` — PnL net cumulé en $

Plus :
- `waitBreakdown` — quelle gate bloque le plus de signaux WAIT
  (adxGate, atrGate, ttmSqueeze, tfAlignment, volumeGate, marketStructure, scoreTooLow)
- `topInsight` — texte auto-généré : meilleure heure, pilier le plus prédictif, comparaison ADX

Utiliser pour mesurer quelle variable a vraiment une valeur prédictive sur les données réelles.

## Réconciliation historique — `/api/scalping/reconcile-history`

Compare les trades fermés locaux aux fills réels Binance sur N jours.
Pour chaque trade, vérifie entry fills et exit fills via `/fapi/v1/userTrades`.

| Type anomalie | Severity | Origin | Signification |
|--------------|----------|--------|---------------|
| `MISSING_ENTRY_FILL` | ERROR | BINANCE | Aucun fill d'entrée trouvé |
| `MISSING_EXIT_FILL` | ERROR | BINANCE | Aucun fill de sortie trouvé |
| `ENTRY_PRICE_MISMATCH` | WARNING/ERROR | LOCAL | Prix entrée local ≠ fill Binance (>0.3% = WARNING, >0.8% = ERROR) |
| `EXIT_PRICE_MISMATCH` | WARNING/ERROR | LOCAL | Prix sortie local ≠ fill Binance |
| `PNL_MISMATCH` | WARNING | LOCAL | PnL brut local ≠ realizedPnl Binance (écart > 1.5$) |
| `FEE_MISMATCH` | WARNING | LOCAL | Frais locaux ≠ commissions Binance (écart > 0.5$) |
| `ORPHAN_FILL` | WARNING | BINANCE | Fill Binance avec realizedPnl ≥ 0.5$ sans trade local |

## Changements v4 → v5

| Aspect | v4 | v5 (actuel) |
|--------|----|----|
| TP1 multiplier | 1.0×ATR | **1.3×ATR** |
| TP2 multiplier | 2.0×ATR | **2.6×ATR** |
| SL multiplier | 0.6×ATR | **0.8×ATR** |
| R:R | 1.67/3.33 | **1.625/3.25** |
| ADX gate | absent | **ADX < 28 → WAIT** |
| ATR gate | 0.20% fixe | **adaptatif max(0.08%, medianTR50×50%)** |
| Volume gate | absent | **volumeRatio < 1.0 → WAIT** |
| MS gate | absent | **MarketStructure1m contredit direction → WAIT** |
| Analytics | absent | **`/api/scalping/analytics` — win rate par indicateur** |
| Réconciliation | position active seulement | **`/api/scalping/reconcile-history` — historique complet** |
| Log analyses | `scalping_signal_log` | idem + finders `findPlacedAfter`, `findWaitAfter` |
| Tests scalping | 44 | **53** (dont 9 reconcileHistory) |
