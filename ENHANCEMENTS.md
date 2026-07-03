# Implementation Hardening — 5 Bug Fixes Explained

This document details five concurrency and correctness bugs discovered during code review, how each was fixed, and the reasoning behind each decision. These are production-grade hardening measures that go beyond the initial design.

---

## Enhancement 1: BalanceReservationService — Stale DB Balance in CAS Loop

**File**: `src/main/java/com/cryptotrading/service/BalanceReservationService.java`

### The Bug

The `tryReserve()` method uses a CAS (compare-and-set) loop to atomically reserve balance. The original implementation read `dbBalance` from the database **once**, before entering the CAS retry loop:

```java
// BEFORE (buggy)
public boolean tryReserve(Long userId, String currency, BigDecimal amount) {
    BigDecimal dbBalance = walletRepository.findByUserIdAndCurrency(userId, currency)
            .map(Wallet::getBalance).orElse(BigDecimal.ZERO);  // read ONCE

    AtomicReference<BigDecimal> ref = getOrCreateRef(key);

    while (true) {
        BigDecimal currentReserved = ref.get();
        if (dbBalance.subtract(currentReserved).compareTo(amount) < 0) {
            return false;  // using potentially STALE dbBalance
        }
        if (ref.compareAndSet(currentReserved, currentReserved.add(amount))) {
            return true;
        }
        // CAS failed, retry — but still using the SAME stale dbBalance!
    }
}
```

### Why This Is Dangerous

Consider this timeline with two concurrent threads (Thread A and Thread B) operating on the same user's USDT wallet (balance = 50,000):

```
Time    Thread A (BUY 30,000 USDT)              Thread B (BUY 30,000 USDT)
────    ──────────────────────────              ──────────────────────────
T1      reads dbBalance = 50,000
T2                                              reads dbBalance = 50,000
T3      CAS: reserved 0 → 30,000 ✓
T4      DB: wallet 50,000 → 20,000
T5      release: reserved 30,000 → 0
T6                                              CAS attempt: dbBalance(50,000) - reserved(0) = 50,000 >= 30,000 ✓
T7                                              CAS: reserved 0 → 30,000 ✓
T8                                              DB: wallet 20,000 → -10,000  ← OVERDRAFT!
```

At T6, Thread B still thinks `dbBalance` is 50,000 (the value it read at T2), but the actual DB balance is now 20,000 (Thread A committed at T4). Thread B's reservation check passes incorrectly.

The reverse scenario is also problematic: Thread B could **incorrectly reject** a valid trade if Thread A's completed trade increased the balance (e.g., a SELL that added USDT).

### The Fix

Re-read `dbBalance` from the database on **every CAS retry attempt**, and cap retries at 10:

```java
// AFTER (fixed)
public boolean tryReserve(Long userId, String currency, BigDecimal amount) {
    String key = key(userId, currency);
    AtomicReference<BigDecimal> ref = getOrCreateRef(key);

    for (int attempt = 0; attempt < MAX_CAS_RETRIES; attempt++) {
        // Re-read dbBalance on EVERY attempt — always uses latest committed value
        BigDecimal dbBalance = walletRepository.findByUserIdAndCurrency(userId, currency)
                .map(Wallet::getBalance).orElse(BigDecimal.ZERO);

        BigDecimal currentReserved = ref.get();
        if (dbBalance.subtract(currentReserved).compareTo(amount) < 0) {
            return false;
        }
        BigDecimal newReserved = currentReserved.add(amount);
        if (ref.compareAndSet(currentReserved, newReserved)) {
            return true;
        }
        // CAS failed — another thread modified reservations, retry with FRESH dbBalance
    }
    return false;  // extreme contention — treat as insufficient balance
}
```

### How CAS Works Here

CAS (Compare-And-Set) is a CPU-level atomic instruction. `AtomicReference.compareAndSet(expected, newValue)` does:
1. Atomically read the current value
2. If current value == expected → set to newValue, return `true`
3. If current value != expected → do nothing, return `false`

This is a single atomic CPU operation — no locks needed. When CAS fails, it means another thread changed the value between our `ref.get()` and our `compareAndSet()`. We simply retry with fresh data.

### Why Cap at 10 Retries?

Without a cap, the CAS loop could spin indefinitely under extreme contention (many threads all trying to reserve the same wallet simultaneously). 10 retries is generous — in practice, CAS succeeds on the first or second attempt. If 10 retries are exhausted, something unusual is happening, and returning `false` (insufficient balance) is the safe default.

</text>
</invoke>

---

## Enhancement 2: RateLimitFilter — O(n) Data Structure Replaced with O(1)

**File**: `src/main/java/com/cryptotrading/filter/RateLimitFilter.java`

### The Bug

The original implementation used `CopyOnWriteArrayList<Long>` to store request timestamps per user:

```java
// BEFORE (slow)
ConcurrentHashMap<String, CopyOnWriteArrayList<Long>> requestLog = new ConcurrentHashMap<>();
```

### Why This Is a Problem

`CopyOnWriteArrayList` is designed for read-heavy, write-rare workloads. Every mutating operation (`add()`, `remove()`) **copies the entire backing array**:

```
Operation       CopyOnWriteArrayList    ConcurrentLinkedDeque
─────────       ────────────────────    ─────────────────────
add()           O(n) — full array copy  O(1) — append to tail
pollFirst()     O(n) — full array copy  O(1) — unlink head
size()          O(1)                    O(n) — but acceptable
```

For a rate limiter, every single request triggers an `add()`. Under load (e.g., 10 requests per second per user), each `add()` copies the entire array. With 10 entries, that's 10 element copies per request. Worse, each copy creates a new array object, generating garbage for the GC.

This is on the **hot path** — every trade request passes through this filter. Under high concurrency, the O(n) copies create:
- Unnecessary CPU work (array copying)
- GC pressure (discarded arrays)
- Latency spikes during GC pauses

### The Fix

Replace with `ConcurrentLinkedDeque<Long>`:

```java
// AFTER (fast)
ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>> requestLog = new ConcurrentHashMap<>();
```

### How the Sliding Window Works

The rate limiter uses a **sliding window** algorithm:

```
Timeline (10-second window):
├─────────────────────────────────────────────┤
│  windowStart                            now │
│  (now - 10s)                                │
│                                             │
│  [expired] [expired] [valid] [valid] [valid]│
│  ← pollFirst()              addLast() →     │
```

1. On each request, calculate `windowStart = now - 10,000ms`
2. Evict all timestamps older than `windowStart` from the **head** (oldest first)
3. Count remaining timestamps — if >= 10, reject with HTTP 429
4. Otherwise, append current timestamp to the **tail**

`ConcurrentLinkedDeque` is ideal because:
- Timestamps are naturally ordered by insertion time (oldest at head, newest at tail)
- `addLast()` is O(1) — just link a new node at the tail
- `pollFirst()` is O(1) — just unlink the head node
- It's thread-safe without external synchronization
- No array copying, no GC pressure from discarded arrays

### Cleanup Scheduler

A `@Scheduled(fixedRate = 30000)` task runs every 30 seconds to evict expired entries and remove empty deques from the map. This prevents memory leaks from users who made requests in the past but are no longer active.

---

## Enhancement 3: HuobiTicker — Floating-Point Precision Loss

**File**: `src/main/java/com/cryptotrading/dto/exchange/HuobiTicker.java`

### The Bug

The original `HuobiTicker` DTO used `double` for bid and ask prices:

```java
// BEFORE (lossy)
public class HuobiTicker {
    private String symbol;
    private double bid;   // IEEE 754 double
    private double ask;   // IEEE 754 double
}
```

And the scheduler converted them to `BigDecimal` via `BigDecimal.valueOf()`:

```java
// BEFORE (in PriceAggregationScheduler)
.map(t -> new ExchangeTicker(
    t.getSymbol().toUpperCase(),
    BigDecimal.valueOf(t.getBid()),   // converts double → BigDecimal
    BigDecimal.valueOf(t.getAsk()),   // preserves the double's error
    "huobi"))
```

### Why This Is Dangerous

IEEE 754 `double` cannot represent most decimal fractions exactly. When Jackson deserializes a JSON number like `3000.12345678` into a `double`, the actual stored value is an approximation:

```
JSON value:     3000.12345678
double value:   3000.1234567800000123...  (IEEE 754 approximation)
BigDecimal:     3000.1234567800000123...  (error preserved!)
```

The `BigDecimal.valueOf(double)` method converts the double's **decimal string representation** to BigDecimal, which is better than `new BigDecimal(double)`, but the damage is already done at deserialization time — the `double` field already lost precision.

In a financial system, this matters because:
- Fee calculations multiply by the price: `fee = price × quantity × 0.001`
- Small errors compound: 0.00000001 USDT per trade × 1,000,000 trades = 10 USDT discrepancy
- Wallet balances drift from expected values over time
- Audit trails become inconsistent

### The Fix

Change the fields to `BigDecimal`:

```java
// AFTER (exact)
public class HuobiTicker {
    private String symbol;
    private BigDecimal bid;   // exact decimal
    private BigDecimal ask;   // exact decimal
}
```

And use them directly in the scheduler (no conversion needed):

```java
// AFTER (in PriceAggregationScheduler)
.map(t -> new ExchangeTicker(
    t.getSymbol().toUpperCase(),
    t.getBid(),    // already BigDecimal — no conversion
    t.getAsk(),    // already BigDecimal — no conversion
    "huobi"))
```

### How Jackson Handles BigDecimal Deserialization

When Jackson encounters a JSON number and the target field is `BigDecimal`, it uses `JsonParser.getDecimalValue()` which reads the raw character sequence from the JSON and constructs a `BigDecimal` directly from the string — **no intermediate `double` representation**:

```
JSON:  {"bid": 3000.12345678}
       ↓
Jackson reads characters: "3000.12345678"
       ↓
new BigDecimal("3000.12345678")  ← exact, no floating-point involved
       ↓
BigDecimal: 3000.12345678  ← perfect precision
```

This is why `BigDecimal` fields in DTOs are the correct choice for any financial data.

### Note on Binance

Binance returns prices as **strings** in JSON (`"bidPrice": "3000.12345678"`), so `BinanceTicker` uses `String` fields and the scheduler does `new BigDecimal(t.getBidPrice())` — this is already safe because `new BigDecimal(String)` is exact. Only Huobi had this issue because Huobi returns prices as JSON numbers (`"bid": 3000.12345678`).


---

## Enhancement 4: TradeService — Concurrent Idempotency Key Race Condition

**File**: `src/main/java/com/cryptotrading/service/TradeService.java`

### The Bug

The idempotency check is a classic **TOCTOU (Time-Of-Check-Time-Of-Use)** race:

```java
// Step 1: Check if trade already exists
Optional<Trade> existing = tradeRepository.findByUserIdAndIdempotencyKey(userId, key);
if (existing.isPresent()) {
    return existing.get();  // idempotent return
}

// ... validation, price lookup, fee calculation ...

// Step N: Insert new trade
Trade trade = tradeRepository.save(newTrade);  // ← can fail if another thread inserted first
```

### The Race Condition Timeline

```
Time    Thread A (key="abc-123")                Thread B (key="abc-123", retry)
────    ────────────────────────                ────────────────────────────────
T1      SELECT: no trade with key="abc-123"
T2                                              SELECT: no trade with key="abc-123"
T3      (both threads think the key is unused)
T4      INSERT trade (key="abc-123") ✓
T5                                              INSERT trade (key="abc-123") ✗
T6                                              DataIntegrityViolationException!
T7                                              → HTTP 500 Internal Server Error
```

At T2, Thread B's SELECT returns empty because Thread A hasn't committed yet. Both threads proceed to insert. Thread A wins. Thread B hits the DB unique constraint `UNIQUE(user_id, idempotency_key)` and throws `DataIntegrityViolationException`.

Without handling this exception, the client gets a 500 error — which is wrong. The trade was actually executed successfully by Thread A. The client might then retry again, creating confusion.

### Why This Happens in Practice

This isn't just a theoretical concern. It happens when:
- A client sends a trade request, gets a network timeout, and immediately retries with the same idempotency key
- The original request is still being processed when the retry arrives
- Both requests hit the server within milliseconds of each other
- Load balancers or proxies automatically retry on timeout

### The Fix

Catch `DataIntegrityViolationException` and return the existing trade:

```java
// AFTER (fixed)
try {
    if (type == TradeType.BUY) {
        return executeBuy(user, pair, ...);
    } else {
        return executeSell(user, pair, ...);
    }
} catch (DataIntegrityViolationException e) {
    // The other thread won the insert race — the trade exists.
    // Re-fetch and return it instead of a 500 error.
    log.warn("Concurrent idempotency collision for userId={}, key={}", userId, idempotencyKey);
    return tradeRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
            .orElseThrow(() -> new RuntimeException("Idempotency conflict but trade not found", e));
}
```

### Defense in Depth

The idempotency guarantee now has **two layers**:

1. **Application-level check** (fast path): `findByUserIdAndIdempotencyKey()` catches 99.9% of duplicate requests — the ones that arrive after the first request has committed.

2. **Database unique constraint** (safety net): `UNIQUE(user_id, idempotency_key)` catches the rare concurrent race where both threads pass the application check. The `DataIntegrityViolationException` handler converts this into a clean idempotent return.

This is a standard pattern in financial systems: the application check is an optimization (avoids unnecessary work), and the DB constraint is the correctness guarantee (prevents duplicates under all conditions).

### Why Not Use SELECT FOR UPDATE?

An alternative would be pessimistic locking:

```java
// Alternative: pessimistic lock (NOT what we chose)
@Query("SELECT t FROM Trade t WHERE t.userId = :userId AND t.idempotencyKey = :key FOR UPDATE")
Optional<Trade> findByUserIdAndIdempotencyKeyForUpdate(Long userId, String key);
```

We didn't use this because:
- It requires a row to already exist (can't lock a non-existent row)
- It would need an advisory lock or a separate lock table — added complexity
- The `DataIntegrityViolationException` approach is simpler, equally correct, and has no lock contention
- The race is rare enough that the exception path is acceptable

---

## Enhancement 5: PriceAggregationScheduler — Overlapping Cycle Protection

**File**: `src/main/java/com/cryptotrading/scheduler/PriceAggregationScheduler.java`

### The Bug

Spring's `@Scheduled(fixedRate = 10000)` fires every 10 seconds **regardless of whether the previous cycle has completed**:

```
Time    Cycle 1                     Cycle 2                     Cycle 3
────    ───────                     ───────                     ───────
0s      START
5s      (waiting for slow Binance API...)
10s     (still waiting...)          START ← overlapping!
15s     FINISH
20s                                 (still running...)          START ← overlapping!
```

### Why This Is a Problem

1. **Redundant API calls**: Two cycles hitting Binance/Huobi simultaneously doubles the load on external APIs. Exchanges may rate-limit or ban the IP.

2. **Duplicate price records**: Both cycles write aggregated prices for the same time window, creating duplicate entries in the database.

3. **Inconsistent state**: If Cycle 1 writes ETHUSDT and Cycle 2 writes BTCUSDT at slightly different times, the "latest" prices are from different aggregation runs.

4. **Resource waste**: Each cycle holds a thread from Spring's scheduler thread pool. Overlapping cycles consume multiple threads unnecessarily.

### The Fix

Add an `AtomicBoolean` guard:

```java
private final AtomicBoolean running = new AtomicBoolean(false);

@Scheduled(fixedRate = 10000)
public void aggregatePrices() {
    if (!running.compareAndSet(false, true)) {
        log.warn("Previous aggregation cycle still running, skipping this cycle");
        return;
    }
    try {
        List<ExchangeTicker> binanceTickers = fetchBinancePrices();
        List<ExchangeTicker> huobiTickers = fetchHuobiPrices();

        if (binanceTickers.isEmpty() && huobiTickers.isEmpty()) {
            log.warn("Both exchanges unreachable, skipping aggregation cycle");
            return;
        }

        priceService.aggregateAndSave(binanceTickers, huobiTickers);
    } finally {
        running.set(false);  // ALWAYS reset, even on exception
    }
}
```

### How `AtomicBoolean.compareAndSet()` Works

`compareAndSet(false, true)` is an atomic CPU instruction that:
1. Reads the current value
2. If it's `false` → sets it to `true` and returns `true` (we got the "lock")
3. If it's `true` → does nothing and returns `false` (someone else has it)

This is a single atomic operation — no race condition is possible between the check and the set. It's the lightest possible synchronization mechanism: no locks, no blocking, no contention.

### Why `try/finally` Is Critical

Without `finally`, if the cycle throws an exception (e.g., `OutOfMemoryError`, `NullPointerException`), the `running` flag stays `true` forever — and **all future cycles are skipped permanently**. The scheduler becomes a no-op.

```java
// WITHOUT finally (dangerous):
running.compareAndSet(false, true);
doWork();          // if this throws...
running.set(false); // ...this never executes → scheduler is dead

// WITH finally (safe):
running.compareAndSet(false, true);
try {
    doWork();      // can throw anything
} finally {
    running.set(false);  // ALWAYS executes → scheduler recovers
}
```

### Why Not Use `fixedDelay` Instead?

An alternative to `fixedRate` + `AtomicBoolean` is `@Scheduled(fixedDelay = 10000)`, which waits 10 seconds **after the previous cycle completes**:

```
fixedRate:  |--cycle--|--cycle--|--cycle--|  (can overlap)
fixedDelay: |--cycle--|   10s   |--cycle--|  (never overlaps)
```

We chose `fixedRate` + `AtomicBoolean` because:
- `fixedRate` maintains a consistent 10-second rhythm even if a cycle is occasionally slow
- With `fixedDelay`, a slow cycle (15s) would push the next cycle to 25s, then 35s — the price data becomes increasingly stale
- The `AtomicBoolean` guard handles the rare overlap case while preserving the regular cadence
- The warning log provides visibility into when overlaps occur, which is useful for monitoring

---

## Summary: Defense-in-Depth Philosophy

All five fixes follow a common pattern: **assume the worst, handle it gracefully**.

| # | Component | Attack Vector | Defense |
|---|-----------|--------------|---------|
| 1 | BalanceReservationService | Stale DB read between CAS retries | Re-read DB on every retry |
| 2 | RateLimitFilter | O(n) array copy on hot path | O(1) deque operations |
| 3 | HuobiTicker | IEEE 754 precision loss | BigDecimal end-to-end |
| 4 | TradeService | TOCTOU race on idempotency check | DB constraint + exception handler |
| 5 | PriceAggregationScheduler | Overlapping scheduler cycles | AtomicBoolean + try/finally |

Each fix is minimal, targeted, and doesn't change the external API contract. The system behaves identically from the client's perspective — it just handles edge cases correctly now.
