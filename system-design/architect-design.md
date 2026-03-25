# Design Document: Crypto Trading System

## Overview

This system is a Spring Boot-based crypto trading platform that aggregates real-time pricing from Binance and Huobi exchanges, determines the best bid/ask prices for ETHUSDT and BTCUSDT trading pairs, and exposes REST APIs for trading, wallet balance inquiry, and transaction history. An in-memory H2 database stores all persistent state including user wallets, aggregated prices, and trade history.

The system operates under the assumption that users are pre-authenticated and start with an initial USDT balance of 50,000. A scheduled task runs every 10 seconds to fetch and aggregate pricing from external sources, storing only the best prices. Trading is executed against these aggregated prices without integration to any third-party order execution system.

## Architecture

```mermaid
graph TD
    subgraph External Sources
        BIN[Binance API]
        HUO[Huobi API]
    end

    subgraph Spring Boot Application
        SCH[Price Aggregation Scheduler<br/>10s interval]
        PS[PriceService]
        TS[TradeService]
        BRS[BalanceReservationService<br/>ConcurrentHashMap + CAS]
        WS[WalletService]
        FS[FeeService]
        HS[HealthService]
        RL[RateLimitFilter<br/>POST /api/trades only]
        
        PC[PriceController]
        TC[TradeController]
        WC[WalletController]
    end

    subgraph H2 Database
        APT[aggregated_price]
        TT[trade]
        WT[wallet]
        UT[user]
        FT[fee_config]
        EST[exchange_status]
    end

    BIN -->|bookTicker| SCH
    HUO -->|market/tickers| SCH
    SCH --> PS
    SCH --> HS
    PS --> APT
    HS --> EST

    RL -->|rate limit| TC

    PC -->|GET /api/prices| PS
    TC -->|POST /api/trades| TS
    TC -->|GET /api/trades| TS
    WC -->|GET /api/wallets| WS
    TS --> BRS
    TS --> APT
    TS --> TT
    TS --> WT
    TS --> FS
    BRS --> WT
    FS --> FT
    WS --> WT
    PS --> APT
```

## Sequence Diagrams

### Price Aggregation Flow

```mermaid
sequenceDiagram
    participant SCH as Scheduler
    participant BIN as Binance API
    participant HUO as Huobi API
    participant PS as PriceService
    participant DB as H2 Database

    loop Every 10 seconds
        SCH->>BIN: GET /api/v3/ticker/bookTicker
        BIN-->>SCH: [{symbol, bidPrice, askPrice}, ...]
        SCH->>HUO: GET /market/tickers
        HUO-->>SCH: {data: [{symbol, bid, ask}, ...]}
        SCH->>PS: aggregate(binanceData, huobiData)
        PS->>PS: For each pair (ETHUSDT, BTCUSDT):<br/>bestBid = max(binanceBid, huobiBid)<br/>bestAsk = min(binanceAsk, huobiAsk)
        PS->>DB: INSERT aggregated_price
    end
```

### Trade Execution Flow (with Balance Reservation and Fee Calculation)

```mermaid
sequenceDiagram
    participant U as User
    participant RL as RateLimitFilter
    participant TC as TradeController
    participant TS as TradeService
    participant BRS as BalanceReservationService
    participant FS as FeeService
    participant DB as H2 Database

    U->>RL: POST /api/trades {pair, type, quantity}
    RL->>RL: Check rate limit for userId
    alt Rate limit exceeded
        RL-->>U: 429 Too Many Requests
    else Within limit
        RL->>TC: Forward request
        TC->>TS: executeTrade(userId, request)
        TS->>DB: SELECT latest aggregated_price for pair
        DB-->>TS: bestPrice
        TS->>FS: calculateFee(totalAmount)
        FS-->>TS: feeAmount

        alt BUY order
            TS->>TS: cost = (quantity × askPrice) + feeAmount
            TS->>BRS: tryReserve(userId, "USDT", cost)
            BRS->>DB: SELECT balance FROM wallets WHERE user_id AND currency
            DB-->>BRS: dbBalance
            BRS->>BRS: CAS loop: check dbBalance - reservedAmount >= cost
            alt Reservation failed
                BRS-->>TS: false
                TS-->>TC: InsufficientBalanceException
                TC-->>U: 400 Insufficient balance
            else Reservation succeeded
                BRS-->>TS: true
                TS->>DB: Deduct cost from USDT wallet
                TS->>DB: Add quantity to crypto wallet
                TS->>DB: INSERT trade record
                TS->>BRS: release(userId, "USDT", cost)
                TS-->>TC: TradeResponse (includes fee breakdown)
                TC-->>U: 200 OK {tradeDetails, fee}
            end
        else SELL order
            TS->>TS: proceeds = (quantity × bidPrice) - feeAmount
            TS->>BRS: tryReserve(userId, cryptoCurrency, quantity)
            BRS->>DB: SELECT balance FROM wallets WHERE user_id AND currency
            DB-->>BRS: dbBalance
            BRS->>BRS: CAS loop: check dbBalance - reservedAmount >= quantity
            alt Reservation failed
                BRS-->>TS: false
                TS-->>TC: InsufficientBalanceException
                TC-->>U: 400 Insufficient balance
            else Reservation succeeded
                BRS-->>TS: true
                TS->>DB: Deduct quantity from crypto wallet
                TS->>DB: Add proceeds to USDT wallet
                TS->>DB: INSERT trade record
                TS->>BRS: release(userId, cryptoCurrency, quantity)
                TS-->>TC: TradeResponse (includes fee breakdown)
                TC-->>U: 200 OK {tradeDetails, fee}
            end
        end
    end
```


### Wallet Balance Retrieval Flow

```mermaid
sequenceDiagram
    participant U as User
    participant WC as WalletController
    participant WS as WalletService
    participant DB as H2 Database

    U->>WC: GET /api/wallets
    WC->>WS: getWalletBalances(userId)
    WS->>DB: SELECT wallets WHERE user_id = userId
    DB-->>WS: [USDT, ETH, BTC wallets]
    WS-->>WC: List<Wallet>
    WC-->>U: 200 OK [{currency, balance}, ...]
```

### Trading History Retrieval Flow

```mermaid
sequenceDiagram
    participant U as User
    participant TC as TradeController
    participant TS as TradeService
    participant DB as H2 Database

    U->>TC: GET /api/trades
    TC->>TS: getTradeHistory(userId)
    TS->>DB: SELECT trades WHERE user_id = userId ORDER BY created_at ASC
    DB-->>TS: List<Trade>
    TS-->>TC: List<Trade>
    TC-->>U: 200 OK [{id, pair, type, price, quantity, totalAmount, fee, netAmount, timestamp}, ...]
```

### Rate Limiting Flow

```mermaid
sequenceDiagram
    participant U as User
    participant RL as RateLimitFilter
    participant TC as TradeController

    U->>RL: POST /api/trades {pair, type, quantity}
    RL->>RL: Check shouldNotFilter(request)<br/>Only applies to POST /api/trades
    RL->>RL: Extract userId, check sliding window count
    
    alt count >= 10 in current 10s window
        RL-->>U: 429 Too Many Requests<br/>{retryAfter: seconds}
    else Within limit
        RL->>RL: Increment request count for userId
        RL->>TC: Forward request
        TC-->>RL: Response
        RL-->>U: Forward response
    end

    Note over U,TC: GET endpoints (/api/prices, /api/wallets,<br/>/api/trades) bypass RateLimitFilter entirely
```
