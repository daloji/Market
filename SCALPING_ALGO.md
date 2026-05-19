# Algorithme de Scalping BTC/USDT

## Données d'entrée

- **200 bougies 1m** Binance BTCUSDT (≈ 3h20 d'historique)
- Refresh : toutes les **10 secondes** (cache TTL)
- Levier configuré : **×10**
- Endpoint : `GET /api/crypto/btc/scalping`

---

## Régime de marché — ADX(14)

```
ADX > 25  → TREND   : EMA/MACD ×1.5, RSI/Stoch ×0.7
ADX < 20  → RANGE   : RSI/Stoch ×1.5, EMA/MACD ×0.7  + VWAP = mean-reversion
ADX 20–25 → NEUTRAL : tous ×1.0
```

> Le régime est affiché dans le reasoning : `ADX=22.4[RANGE](osc×1.5,trend×0.7)`

---

## Filtres préalables (→ WAIT immédiat)

| Ordre | Condition | Raison |
|-------|-----------|--------|
| 1 | **BB Width < 0.3%** | Bollinger squeeze — marché sans direction |
| 2 | **ATR(7) < 0.08%** du prix | Volatilité insuffisante pour scalper |

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

### Dynamiques RSI — max ±17 pts

| Signal | Calcul | Points |
|--------|--------|--------|
| Pente  | `rsi7 − rsiPrev` | ±5 si \|pente\| > 0.5 |
| Accélération | `Δpente` | ±5 si \|accel\| > 0.3 |
| Divergence prix/RSI | sur 5 bougies, seuil strict **3.0** | ±7 |

> Le seuil de divergence à 3.0 (vs 1.5 historique) filtre le bruit des bougies 1m.

### EMA(5) / EMA(13) — max ±25 pts × trendMult (×0.7–×1.5)

```
prix > EMA5 > EMA13  → +25×trendMult LONG
prix < EMA5 < EMA13  → +25×trendMult SHORT
prix > EMA13 seul    → +10×trendMult LONG
prix < EMA13 seul    → +10×trendMult SHORT
```

### MACD(6,13,4) — max ±20 pts × trendMult (continu)

```
force = |histogram| / ATR(7)
pts   = min(20, max(3, force × 40))
```

| Force | Points |
|-------|--------|
| 0.03 (faible) | ~4 pts |
| 0.30 (modéré) | ~14 pts |
| 0.50+ (fort)  | 20 pts |

> Formule continue — élimine le problème du binaire (±25 peu importe la taille).

### Volume Delta — max ±25 pts

```
takerBuyVol / totalVol sur les 20 dernières bougies :

> 60%  (STRONG_BUY)  → +25 LONG
52–60% (BUY)         → +12 LONG
48–52% (NEUTRAL)     →   0
40–48% (SELL)        → +12 SHORT
< 40%  (STRONG_SELL) → +25 SHORT
```

### VWAP — max ±20 pts (régime-aware)

```
VWAP = Σ( (H+L+C)/3 × volume ) / Σ(volume)   sur 200 bougies

distance = |prix − VWAP| / VWAP × 100%
pts      = min(20, max(0, (distance − 0.05%) / 0.25% × 20))
```

**Régime VWAP via ADX (remplace BB Width) :**

| BB Width | Régime | Comportement |
|----------|--------|--------------|
| ADX > 25 (TREND)   | Trend  | **Momentum** — prix > VWAP = +pts LONG |
| ADX < 20 (RANGE)   | Range  | **Mean-reversion** — prix > VWAP = +pts SHORT (fade) |
| ADX 20–25 (NEUTRAL)| Neutre | **Momentum** (comportement par défaut) |
| BB Width < 0.3%    | Squeeze | déjà filtré WAIT — pas de pts VWAP |

| Distance | Points |
|----------|--------|
| < 0.05%  | 0 (neutre — collé au VWAP) |
| 0.15%    | ~8 pts |
| 0.30%+   | 20 pts |

### Stochastique(5,3) — max ±15 pts × oscMult (×0.7–×1.5)

```
K < 20 ET D < 20 :
  K < 10 → +15 LONG
  sinon  → +10 LONG

K > 80 ET D > 80 :
  K > 90 → +15 SHORT
  sinon  → +10 SHORT
```

### ATR(7) bonus volatilité — max +10 pts (symétrique)

```
pts = min(10, max(0, (ATR% − 0.08%) / 0.17% × 10))
```

| ATR% | Points |
|------|--------|
| 0.08% | 0 pts (juste au-dessus du gate) |
| 0.16% | ~5 pts |
| 0.25%+ | 10 pts |

> Appliqué **aux deux côtés** (LONG et SHORT) — récompense les conditions actives.

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

> Empêche un pic de bruit dans un seul indicateur d'atteindre le seuil seul.

---

## Score total max théorique

| Indicateur | Max pts |
|-----------|---------|
| RSI(7) | 25 |
| RSI dynamiques (pente + accél + divergence) | 17 |
| EMA cross | 25 |
| MACD(6,13,4) | 20 |
| Volume Delta | 25 |
| VWAP | 20 |
| Stochastique(5,3) | 15 |
| ATR bonus | 10 |
| Bougie corps | 5 |
| **TOTAL** | **162** |

---

## Décision finale

```
Filtre de tendance SMA(50) :
  prix > SMA50 → uptrend    (easier LONG,  harder SHORT)
  prix < SMA50 → downtrend  (easier SHORT, harder LONG)

Seuils :
  Avec tendance    → 78 pts
  Contre tendance  → 92 pts

Résultat :
  longScore  ≥ seuil LONG  → LONG
  shortScore ≥ seuil SHORT → SHORT
  sinon                    → WAIT
```

---

## Niveaux TP / SL (basés sur ATR)

```
LONG  :  TP1 = prix + 0.5×ATR   TP2 = prix + 1.0×ATR   SL = prix − 0.4×ATR
SHORT :  TP1 = prix − 0.5×ATR   TP2 = prix − 1.0×ATR   SL = prix + 0.4×ATR
```

| Objectif | Mouvement | P&L levier ×10 |
|----------|-----------|----------------|
| TP1 | ±0.5×ATR | ~+5% |
| TP2 | ±1.0×ATR | ~+10% |
| SL  | ∓0.4×ATR | ~−4% |

**Ratio risque/récompense TP2 : 1.0 / 0.4 = 2.5 R:R**

---

## Architecture du code

```
GET /api/crypto/btc/scalping
        │
        └─► ScalpingAnalysisService.getSignal()          (cache 10s)
                └─► compute()
                        ├─► BinanceClient.getKlines("BTCUSDT", "1m", 200)
                        ├─► TechnicalAnalysisService
                        │       RSI(7) · EMA(5,13) · SMA(50)
                        │       MACD(6,13,4) · ATR(7) · Bollinger(20)
                        │       Stochastic(5,3) · VWAP(200 bougies)
                        ├─► Filtres  (BB squeeze → ATR gate)
                        ├─► Scoring  (somme pondérée)
                        ├─► Pénalité conflit RSI/EMA
                        └─► Décision (seuil 78 / 92 selon SMA50)
                                └─► TP1/TP2/SL ATR-based
```

---

*Généré le 2026-05-19 — `ScalpingAnalysisService.java` + `TechnicalAnalysisService.java`*
