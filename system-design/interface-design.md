
## Components and Interfaces

### Component 1: PriceAggregationScheduler

**Purpose**: Fetches pricing data from Binance and Huobi every 10 seconds and delegates aggregation to PriceService.

**Interface**:
```java
@Component
public class PriceAggregationScheduler {
    @Scheduled(fixedRate = 10000)
    public void aggregatePrices();
}
```

**Responsibilities**:
- Call Binance and Huobi REST APIs
- Parse exchange-specific response formats
- Delegate best-price calculation to PriceService
- Handle API failures gracefully (log and skip cycle)

### Component 2: PriceService

**Purpose**: Computes best aggregated prices and manages price persistence.

**Interface**:
```java
@Service
public class PriceService {
    public void aggregateAndSave(List<ExchangeTicker> binanceTickers, List<ExchangeTicker> huobiTickers);
    public AggregatedPrice getLatestBestPrice(String tradingPair);
    public List<AggregatedPrice> getAllLatestBestPrices();
    public PriceResponse getLatestPricesWithStatus();  // Returns prices with HEALTHY/STALE/MAINTENANCE status
}
```

**Responsibilities**:
- Determine best bid (highest) and best ask (lowest) across exchanges
- Persist aggregated prices to database
- Retrieve latest prices for trading and API responses
- Enrich price responses with system status (HEALTHY/STALE/MAINTENANCE) by consulting HealthService

### Component 3: BalanceReservationService

**Purpose**: Provides in-memory balance reservation (pending balance) to prevent concurrent trades from overdrawing a wallet. Uses CAS (compare-and-set) operations on `AtomicReference<BigDecimal>` for lock-free, thread-safe reservation management. Internally fetches the current DB balance from `WalletRepository` so callers don't need to pre-fetch it.

**Interface**:
```java
@Service
public class BalanceReservationService {
    private final WalletRepository walletRepository;
    // ConcurrentHashMap<"userId:currency", AtomicReference<BigDecimal>> for pending reservations
    private final ConcurrentHashMap<String, AtomicReference<BigDecimal>> reservations = new ConcurrentHashMap<>();

    /**
     * Atomically reserve an amount if available balance (dbBalance - reservedAmount) >= amount.
     * Internally fetches dbBalance from WalletRepository, then uses a CAS loop:
     *   1. Fetch dbBalance from DB via walletRepository.findByUserIdAndCurrency()
     *   2. Read currentReserved from AtomicReference
     *   3. Check: dbBalance - currentReserved >= amount
     *   4. CAS: compareAndSet(currentReserved, currentReserved + amount)
     *   5. If CAS fails (another thread reserved concurrently), retry from step 2
     * @return true if reservation succeeded, false if insufficient available balance
     */
    public boolean tryReserve(Long userId, String currency, BigDecimal amount);

    /**
     * Release a reservation after DB commit or rollback.
     * Uses a CAS loop: read current reserved → compareAndSet(current, current - amount).
     */
    public void release(Long userId, String currency, BigDecimal amount);

    /**
     * @return dbBalance (from WalletRepository) - reservedAmount for the given wallet
     */
    public BigDecimal getAvailableBalance(Long userId, String currency);

    /**
     * @return current total reserved (pending) amount for the given wallet
     */
    public BigDecimal getReservedAmount(Long userId, String currency);
}
```

**Responsibilities**:
- Maintain an in-memory `ConcurrentHashMap<String, AtomicReference<BigDecimal>>` keyed by `"userId:currency"`
- `tryReserve()`: Fetch `dbBalance` from `WalletRepository`, then atomically check `dbBalance - currentReserved >= amount` and add to reserved using CAS loop
- `release()`: Atomically subtract from reserved using CAS loop (called in `finally` block after DB commit or rollback)
- Prevent overdraft by accounting for all in-flight (pending) trades against the same wallet
- Since H2 is also in-memory, both the reservation cache and DB reset on restart — this is acceptable

**Concurrency Design**:
- Each wallet key (`"userId:currency"`) has its own `AtomicReference<BigDecimal>`
- The `dbBalance` is fetched once at the start of `tryReserve()`, then the CAS loop operates on the in-memory reserved amount
- The reservation check + update is a single CAS operation, NOT a check-then-act race
- CAS loop pattern: `do { current = ref.get(); if (dbBalance - current < amount) return false; } while (!ref.compareAndSet(current, current + amount));`
- No locks are held during DB operations — only the lightweight CAS reservation
- The DB read of `dbBalance` is safe because wallet balances only change within the reserve→try/finally→release pattern, so the DB value is stable while reservations are in flight

### Component 4: TradeService

**Purpose**: Executes buy/sell trades against aggregated prices and manages wallet balances atomically. Uses `BalanceReservationService` to prevent concurrent overdraft.

**Interface**:
```java
@Service
public class TradeService {
    @Transactional
    public Trade executeTrade(Long userId, TradeRequest request);
    public List<Trade> getTradeHistory(Long userId);
}
```

**Responsibilities**:
- Validate trade requests (supported pair, positive quantity)
- Fetch latest aggregated price for the trading pair
- Reserve required balance via `BalanceReservationService.tryReserve()` before DB operations
- Execute atomic wallet balance updates (debit + credit) within `try/finally` block
- Release reservation in `finally` block (on both success and failure)
- Record trade transactions
- Reject trade immediately if reservation fails (insufficient available balance)

### Component 5: WalletService

**Purpose**: Manages user wallet balances.

**Interface**:
```java
@Service
public class WalletService {
    public List<Wallet> getWalletBalances(Long userId);
    public Wallet getWalletBalance(Long userId, String currency);
}
```

**Responsibilities**:
- Retrieve wallet balances for a user
- Support USDT, ETH, and BTC currencies

### Component 6: REST Controllers

**Purpose**: Expose HTTP endpoints for the trading system.

**Interface**:
```java
@RestController
@RequestMapping("/api/prices")
public class PriceController {
    @GetMapping
    public ResponseEntity<PriceResponse> getLatestPrices();
    // PriceResponse includes: status (HEALTHY/STALE/MAINTENANCE), message (optional), prices list
}

@RestController
@RequestMapping("/api/trades")
public class TradeController {
    @PostMapping
    public ResponseEntity<TradeResponse> executeTrade(
        @RequestHeader("X-Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody TradeRequest request);

    @GetMapping
    public ResponseEntity<List<TradeResponse>> getTradeHistory();
}

@RestController
@RequestMapping("/api/wallets")
public class WalletController {
    @GetMapping
    public ResponseEntity<List<WalletResponse>> getWalletBalances();
}
```

### Component 7: FeeService

**Purpose**: Calculates and manages trading fees applied to each trade transaction.

**Interface**:
```java
@Service
public class FeeService {
    public BigDecimal calculateFee(BigDecimal tradeAmount);
    public FeeConfig getCurrentFeeConfig();
}
```

**Responsibilities**:
- Calculate fee as a percentage of the trade's total amount (default: 0.1%)
- Retrieve current fee configuration from the database
- Provide fee breakdown data for trade responses and history
- Ensure fee is always non-negative and correctly rounded (scale 8, HALF_UP)

### Component 8: HealthService

**Purpose**: Tracks the availability of external price sources and determines overall system health status.

**Interface**:
```java
@Service
public class HealthService {
    public void updateExchangeStatus(String exchange, ExchangeStatus status);
    public boolean isPriceDataStale(String tradingPair);
    public boolean isSystemInMaintenance();
}
```

**Responsibilities**:
- Track UP/DOWN status for each exchange (Binance, Huobi)
- Record last successful fetch timestamp per exchange
- Determine if price data is stale (no successful update in >30 seconds)
- Compute overall system status: HEALTHY, DEGRADED, or MAINTENANCE
- HEALTHY: All exchanges reachable and prices fresh
- DEGRADED: At least one exchange down or prices partially stale
- MAINTENANCE: All exchanges down or all prices stale
- Provide health status to PriceService for enriching GET /api/prices responses

### Component 9: RateLimitFilter

**Purpose**: Enforces per-user rate limits on the trade execution endpoint to prevent abuse.

**Interface**:
```java
@Component
public class RateLimitFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only apply rate limiting to POST /api/trades
        return !(request.getMethod().equals("POST") && request.getRequestURI().equals("/api/trades"));
    }
}
```

**Responsibilities**:
- Intercept only `POST /api/trades` requests (trade execution endpoint)
- Skip filtering for all read endpoints (`GET /api/prices`, `GET /api/wallets`, `GET /api/trades`)
- Extract user identity from the request
- Apply sliding window rate limiting: 10 requests per 10-second window per user
- Return HTTP 429 with `Retry-After` header when limit is exceeded
- Store rate limit counters in-memory (ConcurrentHashMap with scheduled cleanup)


## Key Functions with Formal Specifications

### Function 1: aggregateAndSave()

```java
public void aggregateAndSave(List<ExchangeTicker> binanceTickers, List<ExchangeTicker> huobiTickers)
```

**Preconditions:**
- At least one of `binanceTickers` or `huobiTickers` is non-empty
- Each `ExchangeTicker` has valid `symbol`, `bidPrice > 0`, `askPrice > 0`
- Symbols are normalized to uppercase (e.g., ETHUSDT, BTCUSDT)

**Postconditions:**
- For each supported pair, an `AggregatedPrice` record is persisted with:
  - `bidPrice` = max bid across all exchanges for that pair
  - `askPrice` = min ask across all exchanges for that pair
  - `bidSource` and `askSource` correctly identify the winning exchange
- If a pair has data from only one exchange, that exchange's price is used
- If a pair has no data from any exchange, no record is created for that pair
- Previously stored aggregated prices are not modified

**Loop Invariants:** N/A

### Function 2: executeTrade()

```java
@Transactional
public Trade executeTrade(Long userId, TradeRequest request)
```

**Preconditions:**
- `userId` references an existing user
- `request.tradingPair` is one of: ETHUSDT, BTCUSDT
- `request.tradeType` is one of: BUY, SELL
- `request.quantity` > 0
- A valid, non-stale aggregated price exists for the requested trading pair (last aggregation ≤ 30 seconds ago)
- System is not in MAINTENANCE status
- `X-Idempotency-Key` header is present and non-empty

**Postconditions:**
- For BUY: `usdtWallet.balance` decreased by `(quantity × askPrice) + feeAmount`, crypto wallet balance increased by `quantity`
- For SELL: crypto wallet balance decreased by `quantity`, `usdtWallet.balance` increased by `(quantity × bidPrice) - feeAmount`
- `feeAmount` = `totalAmount × feeRate` (default feeRate = 0.001, i.e., 0.1%)
- A `Trade` record is persisted with correct price, quantity, totalAmount, feePercentage, feeAmount, netAmount, and timestamp
- `totalAmount` = `price × quantity`
- For BUY: `netAmount` = `totalAmount + feeAmount` (total cost to user)
- For SELL: `netAmount` = `totalAmount - feeAmount` (net proceeds to user)
- All wallet balances remain >= 0
- Balance reservation is acquired via `BalanceReservationService.tryReserve()` before any DB mutation
- If reservation fails (insufficient available balance = dbBalance - reservedAmount), `InsufficientBalanceException` is thrown immediately with no state changes
- Reservation is always released in a `finally` block — on success (DB committed, balance updated) or failure (rollback)
- If the same `idempotencyKey` is submitted again, the original trade result is returned without re-execution


**Loop Invariants:** N/A (atomic operation)

### Function 3: Best Price Aggregation Logic

```java
public AggregatedPrice computeBestPrice(String pair, List<ExchangeTicker> allTickers)
```

**Preconditions:**
- `pair` is a supported trading pair
- `allTickers` contains at least one ticker for the given pair
- All ticker prices are positive BigDecimal values

**Postconditions:**
- `result.bidPrice` = max(ticker.bidPrice) for all tickers matching the pair
- `result.askPrice` = min(ticker.askPrice) for all tickers matching the pair
- `result.bidSource` identifies the exchange providing the best bid
- `result.askSource` identifies the exchange providing the best ask

**Loop Invariants:**
- After processing i tickers: `currentBestBid >= all bids seen so far` and `currentBestAsk <= all asks seen so far`