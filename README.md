# Crypto Trading System

A Spring Boot crypto trading platform that aggregates real-time pricing from Binance and Huobi, determines best bid/ask prices for ETHUSDT and BTCUSDT, and exposes REST APIs for trading, wallet balance inquiry, and transaction history.

## Tech Stack

- Java 21
- Spring Boot 3.2.5
- Spring Data JPA
- H2 In-Memory Database
- Lombok 1.18.38
- Maven

## Prerequisites

- Java 21+
- Maven 3.8+

## Quick Start

### 1. Build

```bash
./mvnw clean package -DskipTests
```

Or with system Maven:

```bash
mvn clean package -DskipTests
```

### 2. Run

```bash
./mvnw spring-boot:run
```

Or run the packaged jar:

```bash
java -jar target/crypto-trading-system-0.0.1-SNAPSHOT.jar
```

The app starts on `http://localhost:8080`. Wait ~10 seconds for the price aggregation scheduler to fetch initial prices from Binance and Huobi before making trade requests.

### 3. Verify

```bash
curl http://localhost:8080/api/prices
```

You should see a JSON response with status `HEALTHY` and prices for ETHUSDT and BTCUSDT.

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/prices` | Latest aggregated best prices with system status |
| POST | `/api/trades` | Execute a trade (requires `X-Idempotency-Key` header) |
| GET | `/api/trades` | Trade history (chronological order) |
| GET | `/api/wallets` | Wallet balances (USDT, ETH, BTC) |

### Example: Buy 0.5 ETH

```bash
curl -X POST http://localhost:8080/api/trades \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: $(uuidgen)" \
  -d '{"tradingPair":"ETHUSDT","tradeType":"BUY","quantity":0.5}'
```

### Example: Sell 0.1 BTC

```bash
curl -X POST http://localhost:8080/api/trades \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: $(uuidgen)" \
  -d '{"tradingPair":"BTCUSDT","tradeType":"SELL","quantity":0.1}'
```

## Running Tests

### API Integration Tests (curl)

The project includes a comprehensive curl-based test suite with 42 assertions covering prices, wallets, BUY/SELL trades, trade history, idempotency, validation errors, insufficient balance, fee security, and rate limiting.

```bash
# 1. Start the app in one terminal
./mvnw spring-boot:run

# 2. Wait ~10 seconds for price aggregation

# 3. Run the test suite in another terminal
chmod +x curl-test.sh
./curl-test.sh
```

The test script will output pass/fail for each assertion and a summary at the end.

> **Note**: The test script includes sleep intervals between some tests to handle rate limit windows. A full run takes about 20 seconds.

## H2 Database Console

Available at `http://localhost:8080/h2-console` while the app is running.

- JDBC URL: `jdbc:h2:mem:cryptodb`
- Username: `sa`
- Password: *(empty)*

## Project Structure

```
src/main/java/com/cryptotrading/
├── CryptoTradingApplication.java       # Entry point
├── config/                             # RestTemplate config
├── controller/                         # REST controllers (Price, Trade, Wallet)
├── dto/                                # Request/response DTOs
│   └── exchange/                       # Exchange-specific DTOs (Binance, Huobi)
├── entity/                             # JPA entities
├── exception/                          # Global exception handler + custom exceptions
├── filter/                             # RateLimitFilter (POST /api/trades only)
├── repository/                         # Spring Data JPA repositories
├── scheduler/                          # Price aggregation scheduler (10s interval)
└── service/                            # Business logic
    ├── BalanceReservationService.java   # CAS-based concurrent balance reservation
    ├── FeeService.java                 # Trading fee calculation
    ├── HealthService.java              # Exchange health monitoring (internal only)
    ├── PriceService.java               # Best price aggregation
    ├── TradeService.java               # Trade execution with idempotency
    └── WalletService.java              # Wallet balance queries
```

## Key Design Decisions

- **No optimistic locking on Wallet** — concurrent balance safety is handled by `BalanceReservationService` using in-memory CAS operations, avoiding DB-level lock contention
- **Idempotency** via `X-Idempotency-Key` header + DB unique constraint with `DataIntegrityViolationException` handling for concurrent races
- **Rate limiting** only on `POST /api/trades` (10 req/10s per user) — read endpoints are unrestricted
- **BigDecimal everywhere** — no `double` or `float` for monetary values
- **HealthService is internal** — no `/api/health` endpoint; status is embedded in the `/api/prices` response
