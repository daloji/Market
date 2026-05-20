# Algorithme de Scalping BTC/USDT

## Données d'entrée

- **200 bougies 1m** Binance BTCUSDT (≈ 3h20 d'historique)
- **50 bougies 5m** Binance BTCUSDT (≈ 4h) — pour le biais macro
- Refresh : toutes les **10 secondes** (cache TTL)
- Levier configuré : **×10**
- Endpoint : `GET /api/crypto/btc/scalping`

---

## Régime de marché — ADX(14)

```
ADX > 25  → TREND   : EMA/MACD/Supertrend ×1.5, RSI/Stoch ×0.7
ADX < 20  → RANGE   : RSI/Stoch ×1.5, EMA/MACD/Supertrend ×0.7  + VWAP = mean-reversion
ADX 20–25 → NEUTRAL : tous ×1.0
```

---

## Filtres préalables (→ WAIT immédiat)

| Ordre | Condition | Raison |
|-------|-----------|--------|
| 1 | **TTM Squeeze** (BB bands à l'intérieur des Keltner Channels) | Compression de volatilité — mouvement imminent mais sans direction définie |
| 2 | **ATR(7) < 0.05%** du prix | Volatilité insuffisante pour scalper (seuil abaissé de 0.08→0.05% pour couvrir les sessions asiatiques calmes) |

> Le check `bbWidth < 0.30%` standalone a été supprimé : il est redondant avec le TTM Squeeze et se déclenchait trop souvent sur 1m, bloquant le bot en permanence.

---

## Indicateurs & pondération

### RSI(7) — max ±25 pts × oscMult (×0.7–×1.5)

```
< 30  (oversold)    → +25×oscMult LONG
30–45               → +12×oscMult LONG
55–70               → +12×oscMult SHORT
> 70  (overbought)  → +25×oscMult SHORT
neutre (45–55)      →   0
```

### Dynamiques RSI — max ±17 pts × oscMult

| Signal | Calcul | Points |
|--------|--------|--------|
| Pente  | `rsi7 − rsiPrev` | ±5×oscMult si \|pente\| > 0.5 |
| Accélération | `Δpente` | ±5×oscMult si \|accel\| > 0.3 |
| Divergence prix/RSI | sur 5 bougies, seuil strict **3.0** | ±7×oscMult |

### EMA(5) / EMA(13) — max ±25 pts × trendMult (×0.7–×1.5)

```
prix > EMA5 > EMA13  → +25×trendMult LONG
prix < EMA5 < EMA13  → +25×trendMult SHORT
prix > EMA13 seul    → +10×trendMult LONG
prix < EMA13 seul    → +10×trendMult SHORT
```

### EMA(21) Triple Stack — max ±10 pts × trendMult *(NOUVEAU)*

```
prix > EMA5 > EMA13 > EMA21  → +10×trendMult LONG  (alignement complet haussier)
prix < EMA5 < EMA13 < EMA21  → +10×trendMult SHORT (alignement complet baissier)
sinon                         →   0
```

> Un alignement EMA5 > EMA13 > EMA21 est un signal de tendance forte supplémentaire au croisement classique.

### MACD(6,13,4) — max ±20 pts × trendMult (continu) — O(n) corrigé

```
force = |histogram| / ATR(7)
pts   = min(20, max(3, force × 40)) × trendMult
```

### Supertrend(7, 3) — max ±15 pts × trendMult *(NOUVEAU)*

```
Supertrend = trailing stop ATR-based
  prix > ligne supertrend → direction LONG  → +15×trendMult LONG
  prix < ligne supertrend → direction SHORT → +15×trendMult SHORT
```

> Plus robuste que le seul croisement EMA : moins de whipsaw sur 1m, signaux de tendance plus nets.

### Volume Delta — max ±25 pts

```
takerBuyVol / totalVol sur les 20 dernières bougies :

> 60%  (STRONG_BUY)  → +25 LONG
52–60% (BUY)         → +12 LONG
48–52% (NEUTRAL)     →   0
40–48% (SELL)        → +12 SHORT
< 40%  (STRONG_SELL) → +25 SHORT
```

### CVD — Cumulative Volume Delta — max ±15 pts *(NOUVEAU)*

```
CVD = Σ(buyVol − sellVol) cumulatif sur toute la session 1m
cvdPct = (CVD[n-1] − CVD[n-21]) / totalVol20 × 100

> 20%  (forte accumulation)  → +15 LONG
5–20%  (accumulation)        →  +7 LONG
< -20% (forte distribution)  → +15 SHORT
-20–-5% (distribution)       →  +7 SHORT
```

> Différent du Volume Delta : le CVD est cumulatif et détecte l'accumulation/distribution sur la durée. Une divergence CVD/prix est l'un des meilleurs signaux de scalping.

### VWAP + Bandes ±1σ / ±2σ — max ±20 pts (régime-aware) *(AMÉLIORÉ)*

```
VWAP = Σ( (H+L+C)/3 × volume ) / Σ(volume)   sur 200 bougies
σ    = écart-type volume-pondéré des prix typiques autour du VWAP

Zones & points :
  ≥ VWAP+2σ  → 20 pts  (extrême)
  ≥ VWAP+1σ  → 12 pts
  > VWAP      →  5 pts
  ≤ VWAP-2σ  → 20 pts  (extrême)
  ≤ VWAP-1σ  → 12 pts
  < VWAP      →  5 pts
```

**Régime via ADX :**

| Régime | Comportement |
|--------|--------------|
| TREND / NEUTRAL | **Momentum** — prix > VWAP = +pts LONG |
| RANGE  | **Mean-reversion** — prix > VWAP = +pts SHORT (fade extrêmes) |

> Les bandes ±1σ/±2σ remplacent le calcul de distance proportionnel précédent. ±2σ = zone de reversion forte en RANGE, zone de continuation forte en TREND.

### Biais 5m — max ±12 pts *(NOUVEAU)*

```
EMA(9) sur 5m vs EMA(21) sur 5m :
  EMA9 > EMA21 (+0.02%) → bias5m LONG  → +12 LONG
  EMA9 < EMA21 (−0.02%) → bias5m SHORT → +12 SHORT
  sinon                  → NEUTRAL      →   0
```

> Le bot de swing utilise déjà 4h et 5m comme timeframes supérieurs. Le scalping doit également avoir un contexte macro. La 5m EMA agit comme filtre de tendance plus propre que la SMA50 1m.

### Stochastique(5,3) — max ±15 pts × oscMult (×0.7–×1.5)

```
K < 20 ET D < 20 :
  K < 10 → +15 LONG
  sinon  → +10 LONG

K > 80 ET D > 80 :
  K > 90 → +15 SHORT
  sinon  → +10 SHORT
```

### Structure de marché 1m — max ±15 pts *(NOUVEAU)*

```
Mini-pivots sur 200 bougies 1m (pivotStrength=3) :

BULL_TREND   (HH+HL) → +15 LONG
BREAKOUT_UP          → +10 LONG
BEAR_TREND   (LH+LL) → +15 SHORT
BREAKOUT_DOWN        → +10 SHORT
CONSOLIDATION        →   0 (neutre)
```

> La méthode detectMarketStructure() était déjà utilisée par le bot swing (1h). Appliquée sur 1m avec pivotStrength=3, elle détecte les micro-structures HH/HL/LH/LL.

### ATR(7) bonus volatilité — max +10 pts (symétrique)

```
pts = min(10, max(0, (ATR% − 0.08%) / 0.17% × 10))
```

### Bougie corps (dernière) — ±5 pts

```
close > open (bougie verte) → +5 LONG
close < open (bougie rouge) → +5 SHORT
```

### Pénalité conflit RSI / EMA — −10 pts

```
Si RSI lean (rsi7 < 50) ≠ EMA lean (prix vs EMA13)
ET |rsiScore| ≥ 12 ET |emaScore| ≥ 10
→ longScore  −= 10
→ shortScore −= 10
```

---

## Score total max théorique

| Indicateur | Max pts (oscMult/trendMult ×1.5) |
|-----------|---------|
| RSI(7) | 37 |
| RSI dynamiques | 25 |
| EMA cross | 37 |
| EMA21 triple stack *(NEW)* | 15 |
| MACD(6,13,4) | 30 |
| Supertrend(7,3) *(NEW)* | 22 |
| Volume Delta | 25 |
| CVD *(NEW)* | 15 |
| VWAP SD bands *(IMPROVED)* | 20 |
| Biais 5m *(NEW)* | 12 |
| Stochastique(5,3) | 22 |
| Structure marché 1m *(NEW)* | 15 |
| ATR bonus | 10 |
| Bougie corps | 5 |
| **TOTAL max théorique** | **~290** |

> Le max pratique est ~170–200 pts. Les seuils de décision restent à 78/92.

---

## Décision finale

```
Filtre de tendance SMA(50) 1m :
  prix > SMA50 → uptrend    (easier LONG,  harder SHORT)
  prix < SMA50 → downtrend  (easier SHORT, harder LONG)

Seuils (calibrés pour régime 1m BTC — ~3-4 indicateurs alignés suffisent) :
  Avec tendance    → 55 pts
  Contre tendance  → 72 pts

Résultat :
  longScore  ≥ seuil LONG  → LONG
  shortScore ≥ seuil SHORT → SHORT
  sinon                    → WAIT
```

---

## Niveaux TP / SL (ATR-based) — Double TP *(AMÉLIORÉ)*

```
LONG  :  TP1 = prix + 0.5×ATR  (60% qty)
         TP2 = prix + 1.0×ATR  (40% qty)
         SL  = prix − 0.4×ATR

SHORT :  TP1 = prix − 0.5×ATR  (60% qty)
         TP2 = prix − 1.0×ATR  (40% qty)
         SL  = prix + 0.4×ATR
```

| Objectif | Mouvement | P&L levier ×10 | Qty |
|----------|-----------|----------------|-----|
| TP1 | ±0.5×ATR | ~+5% | 60% |
| TP2 | ±1.0×ATR | ~+10% | 40% |
| SL  | ∓0.4×ATR | ~−4% | restant |

**Ratio risque/récompense moyen : ~1.7 R:R** (vs 1.25 TP1 seul, 2.5 TP2 seul)

### Logique double TP dans BinanceScalpingTradeService :

```
1. Entrée marché (qty total)
2. Ordre SL STOP_MARKET (qty total — reduceOnly)
3. Ordre TP1 TAKE_PROFIT_MARKET (qty×60%)
4. Ordre TP2 TAKE_PROFIT_MARKET (qty×40%)

Suivi interne :
  - TP1 atteint → activeTp1Hit=true, activeQty←40%, suivi vers TP2
  - TP2 atteint → fermeture complète
  - SL atteint  → fermeture du restant
```

---

## Architecture du code

```
GET /api/crypto/btc/scalping
        │
        └─► ScalpingAnalysisService.getSignal()          (cache 10s)
                └─► compute()
                        ├─► BinanceClient.getKlines("BTCUSDT", "1m", 200)
                        ├─► BinanceClient.getKlines("BTCUSDT", "5m", 50)  ← NEW
                        ├─► TechnicalAnalysisService
                        │       RSI(7)+dynamics · EMA(5,13,21) · SMA(50)
                        │       MACD(6,13,4) · ATR(7) · Bollinger(20)
                        │       Keltner(20,1.5) · Supertrend(7,3)        ← NEW
                        │       Stochastic(5,3) · VWAP+SD bands          ← IMPROVED
                        │       CVD(20 bars) · ADX(14)                    ← NEW
                        │       Market Structure 1m (pivotStrength=3)    ← NEW
                        ├─► Filtres  (TTM Squeeze → ATR gate)            ← IMPROVED
                        ├─► Scoring  (14 indicateurs)                    ← EXPANDED
                        ├─► Pénalité conflit RSI/EMA
                        └─► Décision (seuil 78 / 92 selon SMA50)
                                └─► TP1/TP2/SL ATR-based (double TP)    ← NEW
```

---

*Mis à jour le 2026-05-20 — `ScalpingAnalysisService.java` + `TechnicalAnalysisService.java` + `BinanceScalpingTradeService.java`*
