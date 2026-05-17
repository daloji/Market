# 📈 Market — Analyseur Boursier Intelligent

Application **Quarkus 3.8** de surveillance boursière qui récupère les données OHLCV quotidiennes, calcule des indicateurs techniques (RSI, SMA, MACD, Bollinger Bands), effectue une analyse fondamentale (P/E, PEG, P/B, ROE…) et expose des recommandations BUY/HOLD/SELL via une API REST et un dashboard web en thème sombre.

---

## Sommaire

1. [Démarrage rapide](#-démarrage-rapide)
2. [Architecture](#-architecture)
3. [Flux de données](#-flux-de-données)
4. [Analyse technique](#-analyse-technique)
5. [Analyse fondamentale](#-analyse-fondamentale)
6. [Indicateur de tendance](#-indicateur-de-tendance)
7. [Dashboard web](#-dashboard-web)
8. [API REST](#-api-rest)
9. [Bot Trading BTC/USDT Futures](#-bot-de-trading-btcusdt-futures-binance)
10. [Configuration](#-configuration)
11. [Déploiement](#-déploiement)
12. [Structure du projet](#-structure-du-projet)

---

## 🚀 Démarrage rapide

### Prérequis
- Java 21+
- Maven 3.9+
- Docker (optionnel, pour PostgreSQL et l'image native)

### Mode développement (H2 + hot reload)
```bash
./mvnw quarkus:dev
```

| URL | Description |
|-----|-------------|
| http://localhost:8080 | Dashboard principal |
| http://localhost:8080/bitcoin.html | Simulateur Bitcoin |
| http://localhost:8080/trade-history.html | Historique des trades BTC |
| http://localhost:8080/diag.html | Page de diagnostic |
| http://localhost:8080/swagger-ui | Documentation API interactive |
| http://localhost:8025 | Mailhog (emails de dev) |

### Lancer les tests
```bash
./mvnw test                                                          # tous les tests
./mvnw test -Dtest=StockResourceTest                                 # une classe
./mvnw test -Dtest=StockResourceTest#testAddStock                    # une méthode
./mvnw test -Dtest=RecommendationResourceTest                        # recommandations
./mvnw test -Dtest=TradeResourceTest                                 # trades
./mvnw test -Dtest=TechnicalAnalysisServiceTest                      # calcul indicateurs
./mvnw test -Dtest=TradeServiceTest                                  # logique de trading
```

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Frontend (SPA)                           │
│  index.html · style.css · app.js     bitcoin.html · bitcoin.js  │
└────────────────────────┬────────────────────────────────────────┘
                         │ REST / JSON
┌────────────────────────▼────────────────────────────────────────┐
│                     API REST (JAX-RS)                           │
│  StockResource  RecommendationResource  FundamentalResource     │
│  CryptoResource  TradeResource  MarketIndexResource             │
└──────┬──────────────────┬──────────────────┬────────────────────┘
       │                  │                  │
┌──────▼──────┐  ┌────────▼────────┐  ┌─────▼──────────────────┐
│   Services  │  │   Scheduler     │  │   Providers de données  │
│             │  │                 │  │                         │
│ StockData   │  │ MarketScheduler │  │ YahooFinanceProvider    │
│ Recommenda- │  │  · toutes 5 min │  │ AlphaVantageProvider    │
│  tion       │  │  · BTC 15 s     │  │ TwelveDataProvider      │
│ Fundamental │  │  · fondamentaux │  │ StockDataAggregator     │
│  Analysis   │  │    8h05/jour    │  └─────────────────────────┘
│ Technical   │  └─────────────────┘
│  Analysis   │
│ YahooCrumb  │
│ Alert       │
│ Trade       │
└──────┬──────┘
       │
┌──────▼────────────────────────────────────────────────────────┐
│                       Base de données                          │
│   H2 (dev)  ·  PostgreSQL (prod)  ·  Hibernate ORM / Panache  │
│                                                                │
│   Stock · StockQuote · StockRecommendation                     │
│   FundamentalData · Trade · BitcoinSignal                      │
└────────────────────────────────────────────────────────────────┘
```

---

## 🔄 Flux de données

```
Démarrage
    └─► DataInitializer
            ├─ Insère les ~60 actions par défaut (si DB vide)
            └─ Déclenche une analyse initiale pour chaque stock

Toutes les 5 minutes (MarketScheduler)
    └─► Pour chaque stock actif :
            ├─ StockDataService.fetchAndStoreQuotes()
            │       └─► YahooFinanceClient → GET /v8/finance/chart/{symbol}
            │           Upsert StockQuote (OHLCV quotidien, unique symbol+date)
            └─► RecommendationService.generateRecommendation()
                    ├─► TechnicalAnalysisService (calcul RSI/SMA/MACD/Bollinger)
                    ├─ Persist StockRecommendation
                    └─ (Si signal BUY nouveau) AlertService.sendBuyAlert()

Chaque jour à 08h05 (MarketScheduler)
    └─► Pour chaque stock actif sans données fondamentales du jour :
            └─► FundamentalAnalysisService.analyzeAndStore()
                    ├─ Cache mémoire → DB → Yahoo Finance quoteSummary
                    └─ Persist FundamentalData (score + verdict)
```

---

## 📊 Analyse technique

### Indicateurs calculés

| Indicateur | Paramètres | Usage |
|------------|-----------|-------|
| RSI | 14 périodes | Détection surachat/survente |
| SMA | 20 et 50 jours | Tendance court/long terme |
| EMA | Variable | Base du MACD |
| MACD | 12, 26, 9 | Momentum et croisements |
| Bollinger Bands | 20 jours, ±2σ | Volatilité et position du prix |

### Formule de scoring BUY/HOLD/SELL (0–100 pts)

| Composant | Max pts | Critère |
|-----------|---------|---------|
| RSI | 40 pts | < 30 (survente) = 40 · > 70 (surachat) = 0 |
| Tendance | 40 pts | prix > SMA20 > SMA50 (golden cross) = 40 |
| Volume | 20 pts | ≥ 2× volume moyen = 20 |

| Score | Signal |
|-------|--------|
| ≥ 65 | **🟢 BUY** |
| 36–64 | **🟡 HOLD** |
| ≤ 35 | **🔴 SELL** |

---

## 🔍 Analyse fondamentale

### Source des données
**Yahoo Finance `quoteSummary`** — aucune clé API requise.  
Authentification via cookie `fc.yahoo.com` + crumb, gérée automatiquement par `YahooCrumbService` (refresh toutes les heures).

### Formule de valorisation (0–100 pts)

| Critère | Max pts | Seuils |
|---------|---------|--------|
| Forward P/E | 20 | < 10 = 20 · 10-15 = 17 · 15-20 = 13 · 20-30 = 7 · > 30 = 0 |
| PEG ratio | 25 | < 1 = 25 · 1-1.5 = 18 · 1.5-2 = 10 · > 2 = 0 |
| Price/Book | 15 | < 1 = 15 · 1-2 = 12 · 2-4 = 6 · > 4 = 0 |
| Marge nette | 15 | > 20% = 15 · > 10% = 10 · > 0% = 5 · < 0 = 0 |
| ROE | 10 | > 20% = 10 · > 10% = 7 · > 0% = 3 · < 0 = 0 |
| Croissance BPA | 8 | > 20% = 8 · > 5% = 5 · > 0% = 2 · < 0 = 0 |
| Croissance CA | 7 | > 10% = 7 · > 5% = 4 · > 0% = 1 · < 0 = 0 |

| Score | Verdict |
|-------|---------|
| ≥ 65 | 🟢 **UNDERVALUED** (sous-évalué) |
| 40–64 | 🟡 **FAIRLY_VALUED** (juste valeur) |
| < 40 | 🔴 **OVERVALUED** (sur-évalué) |

### Cache à 3 niveaux

```
Requête analyzeAndStore(symbol)
    1. Cache mémoire (ConcurrentHashMap) → réponse instantanée si données du jour
    2. Base de données → rechargé au démarrage depuis la DB
    3. Yahoo Finance → réseau, uniquement si aucune donnée fraîche
```
Les données sont invalidées automatiquement à minuit (comparaison `LocalDate.now()`).  
Le scheduler quotidien (8h05) pre-fetch tous les stocks actifs avec **2 secondes de délai** entre chaque requête.

---

## 📉 Indicateur de tendance

Affiché sur chaque carte du dashboard, calculé à partir de 3 signaux :

| Signal | Haussier si... | Baissier si... |
|--------|---------------|----------------|
| Prix vs SMA20 | prix > SMA20 | prix < SMA20 |
| SMA20 vs SMA50 | SMA20 > SMA50 (golden cross) | SMA20 < SMA50 (death cross) |
| MACD histogramme | > 0 | < 0 |

| Badge | Condition |
|-------|-----------|
| 📈 **Haussière forte** | 3/3 signaux haussiers |
| ↗ **Haussière** | 2/3 signaux haussiers |
| 📉 **Baissière forte** | 3/3 signaux baissiers |
| ↘ **Baissière** | 2/3 signaux baissiers |
| ➡ **Neutre** | signaux mixtes |

---

## 🖥️ Dashboard web

Accessible à **http://localhost:8080**

### Filtres disponibles

**Barre 1 — Signal technique :**  
`Tous` · `BUY` · `HOLD` · `SELL` · `CAC 40`

**Barre 2 — Valorisation fondamentale :**  
`Tous` · `🟢 Sous-évalués` · `🟡 Juste valeur` · `🔴 Sur-évalués`

Les deux filtres sont cumulables (logique AND).

### Carte stock

Chaque carte affiche :
- Badge signal (BUY / HOLD / SELL) + indicateur de tendance (📈/↗/➡/↘/📉)
- Badge valorisation (🟢/🟡/🔴) si données fondamentales disponibles
- Prix actuel, barre de score
- RSI · SMA20 · MACD · SMA50
- Barre de position Bollinger
- Analyse textuelle (raisons du signal)
- Bouton **📊 Graphique** — modal avec Chart.js (prix + SMA20/50 + Bollinger)
- Bouton **🔍 Valorisation** — modal avec tous les ratios fondamentaux

---

## 📡 API REST

### Stocks

| Méthode | Endpoint | Description |
|---------|----------|-------------|
| `GET` | `/api/stocks` | Liste de tous les stocks surveillés |
| `GET` | `/api/stocks/{symbol}` | Détail d'un stock |
| `POST` | `/api/stocks` | Ajouter un stock `{"symbol":"ASML.AS","name":"ASML"}` |
| `DELETE` | `/api/stocks/{symbol}` | Retirer un stock (soft-delete : `active=false`) |
| `GET` | `/api/stocks/{symbol}/quotes?limit=30` | Historique OHLCV |

### Recommandations

| Méthode | Endpoint | Description |
|---------|----------|-------------|
| `GET` | `/api/recommendations` | Dernier signal de chaque stock |
| `GET` | `/api/recommendations/buy` | Stocks en signal BUY |
| `GET` | `/api/recommendations/sell` | Stocks en signal SELL |
| `GET` | `/api/recommendations/{symbol}` | Dernier signal d'un stock |
| `GET` | `/api/recommendations/{symbol}/history?limit=20` | Historique des signaux |

### Analyse fondamentale

| Méthode | Endpoint | Description |
|---------|----------|-------------|
| `GET` | `/api/fundamentals` | Données fondamentales en cache pour tous les stocks |
| `GET` | `/api/fundamentals/health` | Santé du service fondamental (quota, cache) |
| `GET` | `/api/fundamentals/{symbol}` | Fetch/cache pour un stock (déclenche Yahoo si besoin) |
| `GET` | `/api/fundamentals/{symbol}/cached` | Lecture cache uniquement (pas de fetch) |

### Crypto

| Méthode | Endpoint | Description |
|---------|----------|-------------|
| `GET` | `/api/crypto/btc/signal` | Signal intraday BTC avec TP1/TP2/TP3 et SL |
| `GET` | `/api/crypto/btc/candles?interval=1h&limit=100` | Bougies brutes Binance |
| `POST` | `/api/crypto/btc/whatsapp-test` | Envoie un message WhatsApp de test via CallMeBot |

### Indices de marché

| Méthode | Endpoint | Description |
|---------|----------|-------------|
| `GET` | `/api/indices/{symbol}` | Données temps réel d'un indice (ex: `^FCHI` pour CAC 40) |

### Trades (simulateur & réels)

| Méthode | Endpoint | Description |
|---------|----------|-------------|
| `POST` | `/api/trades` | Ouvrir un trade simulé `{amount, direction, leverage, entryPrice, …}` |
| `GET` | `/api/trades/active` | Trades simulés ouverts |
| `GET` | `/api/trades/history` | Trades simulés clôturés |
| `POST` | `/api/trades/real` | Ouvrir un trade réel |
| `GET` | `/api/trades/real/active` | Trades réels ouverts |
| `GET` | `/api/trades/real/history` | Trades réels clôturés |
| `GET` | `/api/trades/all/history` | Tous les trades clôturés (simulés + réels) |
| `GET` | `/api/trades/{id}` | Un trade par id |
| `DELETE` | `/api/trades/{id}` | Clôturer un trade (`?reason=…&closePrice=…`) |

---

## ₿ Bot de Trading BTC/USDT Futures (Binance)

Page **http://localhost:8080/bitcoin.html** — dashboard de trading automatique BTC/USDT perpetuel sur Binance Futures.

---

### 🏗️ Architecture du bot

```
Binance API (toutes les 5 min via @Scheduled)
         │
         ▼
CryptoAnalysisService  ──→  BitcoinSignal (direction + confidence 0–100%)
         │
         ▼
BinanceAutoTradeService  ──→  Filtres durs ──→  Exécution trade
         │                                            │
         ▼                                            ▼
  checkSlTp() (chaque cycle)                Binance Futures
  (surveillance SL/TP interne)              + ordres SL/TP
```

---

### 📊 Indicateurs & score directionnel

Le signal est un **score brut** converti en **confiance 0–100%** via `(score + 100) / 2`.

| Indicateur | Timeframe | Pts max | Logique |
|------------|-----------|---------|---------|
| **RSI(14)** | 1h | ±30 | RSI<35 en uptrend → +30 · RSI>75 en downtrend → −25 · *context-aware* |
| **EMA(9/21)** | 1h | ±30 | Écart relatif EMA9/EMA21 : >0.5% = +30, <−0.5% = −30 |
| **MACD(12,26,9)** | 1h | ±20 | Histogramme positif/négatif, pondéré par amplitude |
| **Bollinger(20,±2σ)** | 1h | ±15 | Prix proche bande basse = achat · bande haute en downtrend = short · *context-aware* |
| **Stochastique(14,3)** | 1h | ±15 | %K <20 en uptrend = achat · %K >80 en downtrend = short · *context-aware* |
| **OBV slope** | 1h | ±10 | Volume confirme ou contredit la direction |
| **Structure marché** | 1h | ±20 | Pivots HH/HL/LH/LL → UPTREND / DOWNTREND / CONSOLIDATION |
| **EMA(9/21) 4h** | 4h | ±25 | Tendance structurelle dominante (biais BULL/BEAR) |
| **EMA(9/21) + MACD** | 5m | ±15 | Timing d'entrée — momentum UP/DOWN |
| **Volume delta** | 1h | ±8 | `2×takerBuy − totalVol` sur 5 bougies → pression achat/vente nette |
| **Funding rate** | Live | ±7 | >+0.05% = trop de longs → −pts · <−0.05% = trop de shorts → +pts |
| **OI Trend** | 1h hist | ±10 | OI↑ + prix↑ = nouveaux longs (+10) · OI↑ + prix↓ = nouveaux shorts (−10) |
| **Filtre volatilité** | 1h | −20 à 0 | ATR>3% du prix → −20 · BB Squeeze → −8 · bougie extrême → −15 |
| **ADX(14)** | 1h | atténuation | ADX<15 → confiance atténuée 30% vers 50 |

---

### 🎯 Décision de direction

```
confidence ≥ 57%  →  LONG
confidence ≤ 43%  →  SHORT
43% < confidence < 57%  →  WAIT (aucun trade)
```

> Quand ADX < 25 (marché en range) : seuil LONG relevé à 62%, SHORT abaissé à 38% (anti-whipsaw).

---

### 🚦 Filtres durs (bloquent le trade même si confiance OK)

| # | Filtre | Condition de blocage |
|---|--------|---------------------|
| 1 | **Structure** | CONSOLIDATION détectée |
| 2 | **Alignement 4h** | Direction opposée au bias 4h fort (`|score4h| ≥ 15`) |
| 3 | **5m momentum** | LONG si 5m DOWN · SHORT si 5m UP |
| 4 | **Funding extrême** | LONG si funding >+0.05% · SHORT si funding <−0.05% |
| 5 | **Volatilité EXTREME** | ATR > 3% du prix (flash crash / news event) |
| 6 | **Bougie extrême** | Range dernière bougie > 3×ATR |
| 7 | **BB Squeeze** | BB width < 1% (breakout imminent, direction inconnue) |
| 8 | **Cooldown** | 4h obligatoires entre 2 trades |
| 9 | **Perte journalière** | P&L jour ≤ −100 USDT → bot suspendu jusqu'à minuit UTC |
| 10 | **Anti-empilement** | Même direction déjà ouverte (LONG+SHORT peuvent coexister) |
| 11 | **Balance insuffisante** | USDT disponible < montant configuré |

---

### ⚡ Exécution d'un trade

```
Montant   : configurable (défaut 50 USDT)
Levier    : configurable (défaut ×10)
Quantité  : (montant × levier) / prix  (min 0.001 BTC)
SL        : entrée ± SL% (défaut 1.5%)  → ordre STOP_MARKET reduceOnly=true
TP        : entrée ± TP% (défaut 3.0%)  → ordre TAKE_PROFIT_MARKET reduceOnly=true
Confirmation : 2 signaux consécutifs dans la même direction requis avant exécution
```

---

### 🔒 Gestion des positions

| Événement | Action |
|-----------|--------|
| SL atteint | `closeWithMarket()` (reduceOnly) → DB fermé → notification Telegram |
| TP atteint | `closeWithMarket()` (reduceOnly) → DB fermé → notification Telegram |
| Fermeture manuelle | `closeWithMarket()` → DB fermé → notification Telegram |
| 🛑 Kill switch | Annule tous les ordres + ferme toutes positions au marché + désactive le bot |
| Redémarrage | Restaure l'état depuis la DB → vérifie la position sur Binance → reprend la surveillance SL/TP |

---

### 📱 Notifications Telegram

- **Trade ouvert** : direction, confiance %, prix, SL, TP, levier, montant
- **Trade fermé** : raison (SL / TP / manuel), prix de sortie, P&L estimé

---

### 🖥️ Dashboard bitcoin.html

- Carte signal temps réel : direction, confiance, RSI, MACD, ADX, Bollinger
- Panel multi-TF : biais 4h + momentum 5m + structure marché
- Panel volumétrique : funding rate, OI, volume delta
- Positions ouvertes avec P&L non réalisé
- Historique des trades réels avec P&L
- Bouton **🛑 KILL SWITCH** (rouge) — fermeture d'urgence de toutes les positions

---

### 🔧 Configuration bot (`application.properties`)

```properties
# Clés API Binance Futures
market.binance.futures.api-key=YOUR_API_KEY
market.binance.futures.secret=YOUR_SECRET

# Telegram (notifications)
market.telegram.bot-token=YOUR_BOT_TOKEN
market.telegram.chat-id=YOUR_CHAT_ID

# Paramètres de trading
market.binance.futures.auto-trade.min-confidence=60
market.binance.futures.auto-trade.amount-usdt=50
market.binance.futures.auto-trade.leverage=10
market.binance.futures.auto-trade.sl-pct=1.5
market.binance.futures.auto-trade.tp-pct=3.0
market.binance.futures.auto-trade.daily-loss-limit=100
```

---

### 📡 API REST Futures

| Méthode | Endpoint | Description |
|---------|----------|-------------|
| GET | `/api/futures/status` | État du bot, config, dernier résultat |
| POST | `/api/futures/enable` | Activer le bot |
| POST | `/api/futures/disable` | Désactiver le bot |
| POST | `/api/futures/trigger` | Déclencher manuellement un cycle |
| POST | `/api/futures/config` | Modifier les paramètres en live |
| GET | `/api/futures/diagnose` | Diagnostic complet (signal + tous les filtres) |
| GET | `/api/futures/positions` | Positions ouvertes en temps réel |
| POST | `/api/futures/close-position` | Fermer la position manuellement |
| POST | `/api/futures/emergency-close` | 🛑 Kill switch — fermeture d'urgence |

---

### 🧪 Tests

**176 tests** — couverture des services principaux :

| Classe | Tests |
|--------|-------|
| `BinanceAutoTradeServiceTest` | 47 — cooldown, daily limit, kill switch, anti-empilement, filtres |
| `TechnicalAnalysisServiceTest` | 41 — RSI, MACD, EMA, ATR, ADX, Bollinger, structure |
| `TradeServiceTest` | 29 — CRUD trades, P&L |
| `FuturesResourceTest` | 17 — tous les endpoints REST |
| `CryptoAnalysisServiceTest` | 6 — cache, erreur, structure signal |
| `BinanceFuturesServiceTest` | 9 — signature HMAC, config, méthodes |

---

## ⚙️ Configuration

Fichier : `src/main/resources/application.properties`

### Variables importantes

| Propriété | Valeur par défaut | Description |
|-----------|-------------------|-------------|
| `quarkus.datasource.jdbc.url` | H2 fichier `./data/marketdb` | Base de données dev |
| `quarkus.mailer.mock` | `true` | Emails simulés en dev |
| `market.alert.email` | _(vide)_ | Destinataire des alertes BUY (laisser vide pour désactiver) |
| `market.datasource.alphavantage.api-key` | _(clé free tier)_ | Fallback données US |
| `market.whatsapp.phone` | _(vide)_ | Numéro WhatsApp sans `+` (ex: `33612345678`) |
| `market.whatsapp.apikey` | _(vide)_ | Clé API CallMeBot (voir ci-dessous) |
| `market.whatsapp.min-confidence` | `70` | Seuil de confiance minimum pour déclencher une alerte WhatsApp |

### Profils

| Profil | Commande | DB | Mailer |
|--------|----------|----|--------|
| `dev` (défaut) | `./mvnw quarkus:dev` | H2 fichier | Mock |
| `test` | `./mvnw test` | H2 mémoire | Mock · scheduler désactivé |
| `prod` | `-Dquarkus.profile=prod` | PostgreSQL | SMTP Gmail |

### Activer les alertes email BUY

```properties
# application.properties
quarkus.mailer.mock=false
quarkus.mailer.host=localhost
quarkus.mailer.port=1025        # Mailhog
market.alert.email=ton@email.com
```

### Activer les alertes WhatsApp BTC (CallMeBot)

1. Ajouter le numéro `+34 644 59 30 06` dans vos contacts WhatsApp
2. Lui envoyer le message : `I allow callmebot to send me messages`
3. Récupérer la clé API reçue en réponse
4. Configurer dans `application.properties` :

```properties
market.whatsapp.phone=33612345678   # code pays sans +
market.whatsapp.apikey=VOTRE_CLE
market.whatsapp.min-confidence=70   # optionnel, défaut : 70
```

> Les alertes ne se déclenchent que sur les transitions LONG/SHORT (pas WAIT) et respectent un cooldown de 5 minutes.

---

## 🐳 Déploiement

### Développement avec Docker (PostgreSQL + Mailhog)

```bash
docker-compose up -d
./mvnw quarkus:dev
```

### Production (JAR)

```bash
docker-compose up -d          # PostgreSQL
./mvnw package -DskipTests
java -Dquarkus.profile=prod -jar target/quarkus-app/quarkus-run.jar
```

### Image native Docker (GraalVM)

```bash
./build-native.sh
# Enchaîne : mvn package -Pnative → docker build → docker compose up
```

> Le binaire natif démarre en < 100 ms et consomme ~50 Mo de RAM.

---

## 📁 Structure du projet

```
market/
├── build-native.sh                          # Script build image native
├── docker-compose.yml                       # PostgreSQL + Mailhog
├── docker-compose.native.yml                # Compose pour l'image native
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/market/
    │   │   ├── client/                      # Clients REST externes
    │   │   │   ├── YahooFinanceClient.java  # OHLCV quotidien
    │   │   │   ├── BinanceClient.java       # Bougies BTC
    │   │   │   ├── TwelveDataClient.java    # Fallback données
    │   │   │   ├── AlphaVantageClient.java  # Fallback données US
    │   │   │   └── dto/                     # DTOs des réponses API
    │   │   ├── model/                       # Entités JPA (Panache)
    │   │   │   ├── Stock.java               # Liste de surveillance
    │   │   │   ├── StockQuote.java          # OHLCV quotidien
    │   │   │   ├── StockRecommendation.java # Signal + métriques techniques
    │   │   │   ├── FundamentalData.java     # Ratios fondamentaux + verdict
    │   │   │   ├── ValuationVerdict.java    # UNDERVALUED / FAIRLY_VALUED / OVERVALUED
    │   │   │   ├── RecommendationSignal.java# BUY / HOLD / SELL
    │   │   │   ├── Trade.java               # Trade simulé ou réel BTC
    │   │   │   ├── BitcoinSignal.java       # Signal intraday BTC
    │   │   │   └── CandleDTO.java           # Bougie OHLCV
    │   │   ├── provider/                    # Abstraction multi-sources
    │   │   │   ├── StockDataProvider.java   # Interface commune
    │   │   │   ├── StockDataAggregator.java # Cascade Yahoo→TwelveData→Alpha
    │   │   │   ├── DailyQuote.java          # DTO OHLCV normalisé source-agnostique
    │   │   │   ├── YahooFinanceProvider.java
    │   │   │   ├── TwelveDataProvider.java
    │   │   │   └── AlphaVantageProvider.java
    │   │   ├── service/
    │   │   │   ├── TechnicalAnalysisService.java  # RSI/SMA/EMA/MACD/Bollinger
    │   │   │   ├── RecommendationService.java     # Orchestration → persist → alerte
    │   │   │   ├── StockDataService.java           # Fetch + upsert quotes
    │   │   │   ├── FundamentalAnalysisService.java # Scoring fondamental + cache 3 niveaux
    │   │   │   ├── YahooCrumbService.java          # Cookie + crumb Yahoo Finance
    │   │   │   ├── CryptoAnalysisService.java      # Signal BTC intraday
    │   │   │   ├── TradeService.java                # Simulation et trades réels levierés
    │   │   │   ├── AlertService.java                # Email sur transition → BUY
    │   │   │   ├── WhatsAppAlertService.java        # WhatsApp via CallMeBot sur signal BTC
    │   │   │   └── DataInitializer.java             # Seed + analyse au démarrage
    │   │   ├── scheduler/
    │   │   │   └── MarketScheduler.java    # @Scheduled : marché 5min, BTC 15s, fond. 8h05
    │   │   └── resource/
    │   │       ├── StockResource.java
    │   │       ├── RecommendationResource.java
    │   │       ├── FundamentalResource.java
    │   │       ├── CryptoResource.java
    │   │       ├── TradeResource.java
    │   │       └── MarketIndexResource.java
    │   └── resources/
    │       ├── application.properties
    │       └── META-INF/resources/         # Frontend statique
    │           ├── index.html              # Dashboard principal
    │           ├── style.css               # Thème sombre, grid, modals
    │           ├── app.js                  # Fetch, rendu cards, Chart.js, filtres
    │           ├── bitcoin.html            # Simulateur BTC
    │           ├── bitcoin.js
    │           ├── bitcoin.css
    │           ├── trade-history.html      # Historique des trades BTC
    │           └── diag.html              # Page de diagnostic des API
    └── test/
        └── java/com/market/
            ├── resource/
            │   ├── StockResourceTest.java
            │   ├── RecommendationResourceTest.java
            │   └── TradeResourceTest.java
            └── service/
                ├── TechnicalAnalysisServiceTest.java
                └── TradeServiceTest.java
```

---

## 🔧 Conventions de code

- **Entités** étendent `PanacheEntity` — `id` hérité, finders statiques sur l'entité
- **`@Transactional`** sur les méthodes de service, pas sur les resources (sauf CRUD simple)
- **Self-calls CDI** : toujours passer par le proxy injecté (ne jamais appeler `this.method()` pour une méthode `@Transactional` depuis la même classe)
- **REST client** Yahoo Finance : `configKey = "yahoo-finance"` → `quarkus.rest-client.yahoo-finance.*`
- **Scheduler désactivé en test** : `%test.quarkus.scheduler.enabled=false`
- **Déduplication alertes** : `AlertService` appelé uniquement sur les transitions vers BUY (signal précédent ≠ BUY)
