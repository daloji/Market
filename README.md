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
9. [Bitcoin / Crypto](#-bitcoin--crypto)
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
| http://localhost:8080/swagger-ui | Documentation API interactive |
| http://localhost:8025 | Mailhog (emails de dev) |

### Lancer les tests
```bash
./mvnw test                                          # tous les tests
./mvnw test -Dtest=StockResourceTest                 # une classe
./mvnw test -Dtest=StockResourceTest#testAddStock    # une méthode
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
| `GET` | `/api/stocks/{symbol}/fundamental` | Fetch/cache pour un stock (déclenche Yahoo si besoin) |
| `GET` | `/api/stocks/{symbol}/fundamental/cached` | Lecture cache uniquement (pas de fetch) |

### Crypto

| Méthode | Endpoint | Description |
|---------|----------|-------------|
| `GET` | `/api/crypto/btc/signal` | Signal intraday BTC avec TP1/TP2/TP3 et SL |
| `GET` | `/api/crypto/btc/candles?interval=1h&limit=100` | Bougies brutes Binance |

### Trades (simulateur)

| Méthode | Endpoint | Description |
|---------|----------|-------------|
| `POST` | `/api/trades` | Ouvrir un trade `{amount, direction, leverage, entryPrice}` |
| `GET` | `/api/trades` | Tous les trades |
| `GET` | `/api/trades/active` | Trades ouverts |
| `GET` | `/api/trades/{id}` | Un trade |
| `DELETE` | `/api/trades/{id}` | Clôturer un trade |

---

## ₿ Bitcoin / Crypto

Page **http://localhost:8080/bitcoin.html** — simulateur de trading BTC avec effet de levier.

### Signal intraday BTC
- Données Binance (public, sans clé API)
- Indicateurs : RSI, MACD, Bollinger Bands, ATR
- Niveaux calculés automatiquement :
  - **TP1** = entrée + 1× ATR, **TP2** = entrée + 2× ATR, **TP3** = entrée + 3× ATR
  - **SL** = entrée ± 1.5× ATR
  - **Liquidation** ≈ entrée ± (100% / levier)

### Calcul du P&L
```
pnlUsd = mise × (ΔPrix / prixEntrée) × levier
```
> La `mise` est la marge engagée (ex. 100 USDT). Le levier est appliqué séparément.

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
    │   │   │   ├── Trade.java               # Trade simulé BTC
    │   │   │   ├── BitcoinSignal.java       # Signal intraday BTC
    │   │   │   └── CandleDTO.java           # Bougie OHLCV
    │   │   ├── provider/                    # Abstraction multi-sources
    │   │   │   ├── StockDataProvider.java   # Interface commune
    │   │   │   ├── StockDataAggregator.java # Cascade Yahoo→TwelveData→Alpha
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
    │   │   │   ├── TradeService.java                # Simulation trades levierés
    │   │   │   ├── AlertService.java                # Email sur transition → BUY
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
    │           └── bitcoin.css
    └── test/
        └── java/com/market/
            └── StockResourceTest.java
```

---

## 🔧 Conventions de code

- **Entités** étendent `PanacheEntity` — `id` hérité, finders statiques sur l'entité
- **`@Transactional`** sur les méthodes de service, pas sur les resources (sauf CRUD simple)
- **Self-calls CDI** : toujours passer par le proxy injecté (ne jamais appeler `this.method()` pour une méthode `@Transactional` depuis la même classe)
- **REST client** Yahoo Finance : `configKey = "yahoo-finance"` → `quarkus.rest-client.yahoo-finance.*`
- **Scheduler désactivé en test** : `%test.quarkus.scheduler.enabled=false`
- **Déduplication alertes** : `AlertService` appelé uniquement sur les transitions vers BUY (signal précédent ≠ BUY)
