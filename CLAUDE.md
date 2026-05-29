# CLAUDE.md — Market / BTC Scalping Bot

## Stack
- **Quarkus 3.8** — `@ApplicationScoped` singleton CDI, JAX-RS
- **Java 17**, Maven, JUnit 5 + Mockito, `@QuarkusTest`
- **Binance Futures API** — HMAC-SHA256 signed, testnet flag

## Fichiers clés

| Fichier | Rôle |
|---------|------|
| `ScalpingAnalysisService.java` | Calcule le signal 1m (14 indicateurs, cache 10s) |
| `BinanceScalpingTradeService.java` | Gère l'exécution et le suivi de position |
| `BinanceFuturesService.java` | Client REST Binance bas niveau (signing, clock sync) |
| `TechnicalAnalysisService.java` | Calculs purs (RSI, EMA, ATR, VWAP, ADX, Stoch…) |
| `SCALPING_ALGO.md` | Spécification complète de l'algorithme |

Tests :
- `ScalpingAnalysisServiceTest.java` — 24 tests signal
- `BinanceScalpingTradeServiceTest.java` — 29 tests trading
- `BinanceFuturesServiceTest.java` — 17 tests API

## Paramètres actuels (v5 — 2026-05-29)

```
ATR gate       : max(0.08% plancher, medianTR50×50%) — adaptatif avec hard floor
ADX gate       : 22 minimum (increased from 18) — filtre marché range
Timeframes     : 1m + 5m + 15m (macro gate obligatoire)
Seuils signal  : 60 pts (3/3 TF alignés) / 75 pts (2/3 TF alignés)
                 WAIT absolu si ≤1/3 TF aligné
DEFAULT_AMOUNT : 50 USDT à 10× levier
TP1 = 1.3×ATR (60% qty), TP2 = 2.6×ATR (40% qty), SL = 0.8×ATR
R:R ~1.625 (TP1) / ~3.25 (TP2)

Indicateurs v4: RSI(14), MACD(12,26,9), Stoch(14,3), Supertrend(10,3), ATR(14)
3 piliers: Multi-TF Alignment (40) + Momentum Quality (40) + Volume/Flow (25)
```

## Patterns critiques

### Tests — reset singleton obligatoire
`@ApplicationScoped` = singleton global. Chaque `setUp()` DOIT resetter tous les champs via réflexion :
```java
void setField(Object bean, String name, Object value) { /* reflection */ }
// Dans setUp(): tous les champs volatile + activeTp1Pnl + activeTp1Price + activeTp2Price
```

### Double TP — Java side (pas Binance)
- Seul le SL est placé sur Binance → **1 seul appel `placeCloseOrder`** (important pour `times(1)` dans les tests)
- TP1/TP2 déclenchés par `closeWithMarket()` Java → évite OCO Binance qui annule TP2 quand TP1 se déclenche
- `canSplit = qty >= 0.002` — si trop petit → `activeTp2Price=0` → single-TP automatique

### Indicateurs à 0 en mode WAIT — NORMAL
ADX, Stoch, CVD, Supertrend, MarketStructure, VWAP, MACD = 0/null dans la réponse API quand `direction=WAIT`.
C'est attendu : ils ne sont calculés qu'APRÈS les gates TTM Squeeze et ATR.

### Clock sync Binance
`BinanceFuturesService` maintient `timeOffset` vs server time pour éviter -1021.
`tsSuffix()` = `"&timestamp=" + ts() + "&recvWindow=10000"`.

### Réconciliation position
Quand `binanceHasPos=false` détecté, `reconcileClosedPosition()` appelle d'abord
`fetchLastBinanceFillPrice()` → `/fapi/v1/userTrades` → prix réel du fill, pas le mark price courant.

## Endpoint scalping
```
GET /api/crypto/btc/scalping   → signal complet + indicateurs
GET /api/scalping/status       → état position active, wallet, cooldown
POST /api/scalping/enable      → activer l'auto-trading
POST /api/scalping/sync        → resync position si état incohérent
```

## Commandes utiles
```bash
mvn test -Dtest="ScalpingAnalysisServiceTest,BinanceScalpingTradeServiceTest,BinanceFuturesServiceTest"
mvn test  # tous les tests
mvn quarkus:dev  # dev mode
```
