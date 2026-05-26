# Copilot Instructions — Market (Quarkus Stock Analyzer)

## Project overview
Quarkus 3.8 application that watches stock tickers, fetches daily OHLCV data from Yahoo Finance every 5 minutes, computes technical indicators (RSI, SMA, MACD, Bollinger Bands), and exposes buy/hold/sell recommendations via REST API + a dark-theme web dashboard.

## Build & run

```bash
# Dev mode (hot reload, Swagger UI + frontend auto-enabled)
./mvnw quarkus:dev

# Run all tests
./mvnw test

# Run a single test class / method
./mvnw test -Dtest=StockResourceTest
./mvnw test -Dtest=StockResourceTest#testAddAndRemoveStock

# Production (PostgreSQL + real SMTP)
docker-compose up -d          # starts PostgreSQL + Mailhog
./mvnw package -DskipTests
java -Dquarkus.profile=prod -jar target/quarkus-app/quarkus-run.jar
```

Swagger UI: http://localhost:8080/swagger-ui  
Frontend dashboard: http://localhost:8080  
Mailhog (dev emails): http://localhost:8025

## Architecture & data flow

```
DataInitializer (startup)
MarketScheduler (@Scheduled every 5 min, 30 s delayed)
        │
        ├─► StockDataService.fetchAndStoreQuotes(symbol)
        │       └─► YahooFinanceClient  GET /v8/finance/chart/{symbol}?interval=1d&range=3mo
        │           Upserts StockQuote rows (daily OHLCV, unique on symbol+date)
        │
        └─► RecommendationService.generateRecommendation(symbol)
                └─► TechnicalAnalysisService (pure math — no I/O)
                    RSI(14) · SMA(20) · SMA(50) · EMA · MACD(12,26,9) · Bollinger(20,±2σ)
                    Composite score 0–100 → BUY (≥65) / HOLD / SELL (≤35)
                Persists StockRecommendation
                On BUY transition → AlertService.sendBuyAlert()
```

## Scoring formula

| Component | Max pts | Signal criteria |
|-----------|---------|-----------------|
| RSI       | 40      | <30 oversold=40 pts · >70 overbought=0 pts |
| Trend     | 40      | price > SMA20 > SMA50 (golden-cross)=40 pts |
| Volume    | 20      | ≥2× avg volume=20 pts |

`score ≥ 65` → **BUY**, `score ≤ 35` → **SELL**, otherwise **HOLD**.

## Key conventions

- **Entities extend `PanacheEntity`** — `id` is inherited; custom finders are static methods on the entity class. Do NOT inject a repository.
- **`@Transactional` lives on service methods**, not resource methods (except simple CRUD). Resources call services; services call `entity.persist()`.
- **REST client** — `configKey = "yahoo-finance"` maps to `quarkus.rest-client.yahoo-finance.*`. Change URL only in `application.properties`.
- **Database** — H2 file for dev/test, PostgreSQL for `%prod` profile. No code change required, only `quarkus.datasource.*` properties.
- **Mailer** — `quarkus.mailer.mock=true` by default (dev). Set `market.alert.email=your@email.com` to enable BUY alerts. `%prod` profile uses real SMTP.
- **Alert deduplication** — `AlertService` is called only on BUY *transitions* (previous signal ≠ BUY). The previous recommendation is read before `rec.persist()`.
- **Scheduler disabled in tests** — `%test.quarkus.scheduler.enabled=false` prevents Yahoo Finance calls during `./mvnw test`.
- **`DataInitializer`** uses `QuarkusTransaction.run/call` for programmatic transactions to avoid CDI self-call proxy issues.
- **MACD stored as 4 dp** (`round4`), prices as 2 dp (`round2`), displayed in front-end accordingly.
- **Frontend** (`src/main/resources/META-INF/resources/`) is pure HTML/CSS/JS served by Quarkus. Chart.js is loaded from CDN. Bollinger bands and SMA lines are computed client-side from raw quotes data returned by `/api/stocks/{symbol}/quotes?limit=60`.

## REST API

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/stocks` | All watched stocks |
| POST | `/api/stocks` | Add stock `{"symbol":"ASML.AS","name":"ASML"}` |
| DELETE | `/api/stocks/{symbol}` | Soft-remove (`active=false`) |
| GET | `/api/stocks/{symbol}/quotes` | Historical OHLCV (`?limit=30`) |
| GET | `/api/recommendations` | Latest signal per stock |
| GET | `/api/recommendations/buy` | Only BUY signals |
| GET | `/api/recommendations/sell` | Only SELL signals |
| GET | `/api/recommendations/{symbol}` | Latest signal for one stock |
| GET | `/api/recommendations/{symbol}/history` | Signal history (`?limit=20`) |

## Package layout

```
com.market
├── client/
│   ├── YahooFinanceClient.java      MicroProfile REST Client interface
│   └── dto/YahooChartResponse.java  Jackson DTOs for Yahoo Finance response
├── model/
│   ├── Stock.java                   Watchlist entity
│   ├── StockQuote.java              Daily OHLCV entity (unique: symbol+date)
│   ├── StockRecommendation.java     Analysis result (RSI,SMA,MACD,Bollinger)
│   └── RecommendationSignal.java    BUY / HOLD / SELL enum
├── service/
│   ├── AlertService.java            BUY transition email alerts
│   ├── DataInitializer.java         Startup seeding & initial analysis
│   ├── StockDataService.java        API fetch + DB upsert
│   ├── TechnicalAnalysisService.java RSI/SMA/EMA/MACD/Bollinger (pure functions)
│   └── RecommendationService.java   Orchestrates analysis → persists → alerts
├── scheduler/
│   └── MarketScheduler.java         @Scheduled every 5 min
└── resource/
    ├── StockResource.java           /api/stocks
    └── RecommendationResource.java  /api/recommendations

src/main/resources/META-INF/resources/
    index.html   Dark-theme dashboard
    style.css    CSS variables, card grid, modal, responsive
    app.js       Fetch, render cards, Chart.js modal, SMA/Bollinger client-side
```
