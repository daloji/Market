# CLAUDE.md — Market / BTC Scalping Bot

## Stack
- **Quarkus 3.8** — `@ApplicationScoped` singleton CDI, JAX-RS
- **Java 17**, Maven, JUnit 5 + Mockito, `@QuarkusTest`
- **Binance Futures API** — HMAC-SHA256 signed, testnet flag

## Fichiers clés

| Fichier | Rôle |
|---------|------|
| `ScalpingAnalysisService.java` | Calcule le signal 1m (14 indicateurs, cache 10s) |
| `BinanceScalpingTradeService.java` | Gère l'exécution, suivi, analytics et réconciliation |
| `BinanceFuturesService.java` | Client REST Binance bas niveau (signing, clock sync) |
| `TechnicalAnalysisService.java` | Calculs purs (RSI, EMA, ATR, VWAP, ADX, Stoch…) |
| `TelegramAlertService.java` | Notifications sortantes + polling commandes entrantes |
| `MarketScheduler.java` | Tâches planifiées — scalping 1m + polling Telegram 5s |
| `ScalpingTrade.java` | Entité JPA (table `scalping_trade`) — finders : `findRecent`, `findOpenTrade`, `findClosedAfter` |
| `ScalpingTradeLog.java` | Snapshot signal chaque cycle (table `scalping_signal_log`) — finders : `findPlacedAfter`, `findWaitAfter` |
| `SCALPING_ALGO.md` | Spécification complète de l'algorithme |

Tests :
- `ScalpingAnalysisServiceTest.java` — 24 tests signal
- `BinanceScalpingTradeServiceTest.java` — 53 tests trading (dont 9 reconcileHistory)
- `BinanceFuturesServiceTest.java` — 17 tests API
- `TelegramAlertServiceTest.java` — 13 tests alertes

## Paramètres actuels (v5 — 2026-05-29)

```
ATR gate       : max(0.08% plancher, medianTR50×50%) — adaptatif avec hard floor
ADX gate       : 28 minimum — filtre marché range (seuil strict)
Timeframes     : 1m + 5m + 15m (macro gate obligatoire)
Seuils signal  : 60 pts (3/3 TF alignés) / 75 pts (2/3 TF alignés)
                 WAIT absolu si ≤1/3 TF aligné
DEFAULT_AMOUNT : 50 USDT à 10× levier
TP1 = 1.3×ATR (60% qty), TP2 = 2.6×ATR (40% qty), SL = 0.8×ATR
R:R ~1.625 (TP1) / ~3.25 (TP2)

Indicateurs v5: RSI(14), MACD(12,26,9), Stoch(14,3), Supertrend(10,3), ATR(14)
Gates additionnels: volumeRatio < 1.0 → WAIT, MarketStructure1m contredit direction → WAIT
3 piliers: Multi-TF Alignment (40) + Momentum Quality (40) + Volume/Flow (25)
+ bonus VWAP (+5), Market Structure (+5/+8), dernière bougie (+3)
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
GET  /api/crypto/btc/scalping          → signal complet + indicateurs
GET  /api/scalping/status              → état position active, wallet, cooldown
POST /api/scalping/enable              → activer l'auto-trading
POST /api/scalping/sync                → resync position si état incohérent
GET  /api/scalping/analytics?days=30   → win rate empirique par indicateur/heure/pilier
GET  /api/scalping/reconcile-history?days=7 → comparaison historique local vs fills Binance
```

### Analytics — `GET /api/scalping/analytics`
Analyse empirique des trades fermés : win rate par bucket (ADX, RSI, CVD, heure UTC, piliers,
TF alignment, volume, confidence). Permet de mesurer quelle variable prédit réellement l'outcome.
Retourne aussi `waitBreakdown` : quelle gate bloque le plus de signaux WAIT.

### Réconciliation — `GET /api/scalping/reconcile-history`
Compare les trades locaux (DB) aux fills réels Binance sur N jours.
Détecte : `MISSING_ENTRY_FILL`, `MISSING_EXIT_FILL`, `ENTRY_PRICE_MISMATCH`,
`EXIT_PRICE_MISMATCH`, `PNL_MISMATCH`, `FEE_MISMATCH`, `ORPHAN_FILL`.
Chaque anomalie indique son `origin` (LOCAL ou BINANCE) et sa `severity` (ERROR/WARNING).

## Notifications Telegram

### Configuration (application.properties)
```
market.telegram.bot-token=7123456789:AAFxxx...
market.telegram.chat-id=123456789
market.telegram.min-confidence=60
```

### Alertes sortantes (bot → utilisateur)
| Événement | Méthode | Contenu |
|-----------|---------|---------|
| Trade ouvert | `sendScalpingAlert()` | Direction, prix entrée, TP1, TP2, SL, levier, mise |
| TP1 partiel (60%) | `sendCloseAlert()` | Prix fill, PnL partiel |
| TP2 / SL / réconciliation | `sendCloseAlert()` + `sendTradeSummary()` | Prix fill, PnL net + bilan global |

`sendTradeSummary()` est `public` — appelable depuis `MarketScheduler` via `/bilan`.  
`notifyIfNeeded()` existe toujours mais **n'est plus appelé** (supprimé de `CryptoAnalysisService`).

### Commandes entrantes (utilisateur → bot)
Le scheduler `processTelegramCommands()` poll `getUpdates` **toutes les 5 s**.  
Seuls les messages venant du `chat_id` configuré sont traités (filtre sécurité).

| Commande | Action |
|----------|--------|
| `/start` | Active l'auto-scalping |
| `/stop` | Désactive l'auto-scalping |
| `/status` ou `/s` | État position + wallet + cooldown |
| `/bilan` | Résumé wins/losses/PnL + 5 derniers trades |
| `/help` | Liste des commandes |

Le suffixe `@BotName` (ajouté par Telegram dans les groupes) est strippé avant le dispatch.

## Commandes utiles
```bash
mvn test -Dtest="ScalpingAnalysisServiceTest,BinanceScalpingTradeServiceTest,BinanceFuturesServiceTest,TelegramAlertServiceTest"
mvn test  # tous les tests
mvn quarkus:dev  # dev mode
```
