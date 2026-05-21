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

## Paramètres actuels (v3 — 2026-05-21)

```
ATR gate       : 0.15% minimum (break-even frais taker 0.10% round-trip)
Seuils signal  : 55 pts avec tendance / 72 pts contre tendance (SMA50)
DEFAULT_AMOUNT : 50 USDT à 10× levier
TP1 = 1.0×ATR (60% qty), TP2 = 2.0×ATR (40% qty), SL = 0.5×ATR
R:R ~2.8 — gross 1.4×ATR > frais 0.10% notional
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
