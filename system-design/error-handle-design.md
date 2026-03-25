## Error Handling

### Error Scenario 1: Exchange API Unavailable

**Condition**: Binance or Huobi API returns an error or times out during price aggregation.
**Response**: Log the error, continue with data from the available exchange. If both fail, skip the aggregation cycle entirely.
**Recovery**: Next scheduled cycle (10 seconds later) will attempt to fetch from both exchanges again.

### Error Scenario 2: Insufficient Balance

**Condition**: User attempts a BUY with insufficient available USDT (dbBalance - reservedAmount) or a SELL with insufficient available crypto balance. The `BalanceReservationService.tryReserve()` check fails before any DB operations.
**Response**: Return HTTP 400 with error message "Insufficient balance for {currency}". No database mutation occurs.
**Recovery**: User must adjust quantity or wait for pending trades to complete (releasing their reservations).

### Error Scenario 3: No Aggregated Price Available

**Condition**: User attempts to trade a pair for which no aggregated price has been stored yet (e.g., system just started).
**Response**: Return HTTP 400 with error message "No price available for {pair}. Please try again shortly."
**Recovery**: Wait for the next price aggregation cycle to complete.

### Error Scenario 4: Unsupported or Missing Trading Pair

**Condition**: User submits a trade request with a null, blank, or unsupported trading pair (anything other than ETHUSDT or BTCUSDT).
**Response**: Return HTTP 400. For null/blank: Spring validation returns "Trading pair is required". For unsupported pair: service returns "Unsupported trading pair: {pair}".
**Recovery**: User must provide a supported trading pair (ETHUSDT or BTCUSDT).

### Error Scenario 5: Invalid or Missing Trade Quantity

**Condition**: User submits a trade with null quantity, zero, or negative quantity.
**Response**: Return HTTP 400. For null: Spring validation returns "Quantity is required". For zero/negative: Spring validation returns "Quantity must be greater than zero".
**Recovery**: User must provide a positive quantity.

### Error Scenario 6: Rate Limit Exceeded

**Condition**: User exceeds the allowed request rate for trade execution (10 requests per 10-second window for `POST /api/trades`).
**Response**: Return HTTP 429 Too Many Requests with `Retry-After` header indicating seconds until the window resets.
**Recovery**: User must wait for the rate limit window to expire before retrying. Note: read endpoints (`GET /api/prices`, `GET /api/wallets`, `GET /api/trades`) are not rate-limited.

### Error Scenario 7: Missing Idempotency Key

**Condition**: `POST /api/trades` request is missing the `X-Idempotency-Key` header or the header value is empty.
**Response**: Return HTTP 400 with error message "X-Idempotency-Key header is required for trade execution."
**Recovery**: User must include a unique idempotency key (e.g., UUID) in the request header.

### Error Scenario 8: Duplicate Idempotency Key (Idempotent Return)

**Condition**: `POST /api/trades` request contains an `X-Idempotency-Key` that was already used by the same user.
**Response**: Return HTTP 200 with the original trade result. No new trade is executed.
**Recovery**: N/A — this is expected behavior for retry safety. If the user wants a new trade, they must use a new idempotency key.

### Error Scenario 9: Insufficient Available Balance (Concurrent Reservation)

**Condition**: A trade request arrives but the available balance (dbBalance - reservedAmount from pending trades) is insufficient. This occurs when concurrent trades have already reserved part of the wallet balance.
**Response**: Return HTTP 400 with error message "Insufficient balance for {currency}". No DB operations are attempted; the reservation check fails upfront.
**Recovery**: User can retry after pending trades complete and release their reservations. Unlike optimistic locking, this approach prevents wasted DB work — the conflict is detected before any database mutation.

### Error Scenario 10: System Maintenance / Stale Prices

**Condition**: All external price sources are down or the latest price aggregation for the requested trading pair is older than 30 seconds.
**Response**: Return HTTP 503 Service Unavailable with error message "Trading is temporarily unavailable due to stale price data. Please try again shortly."
**Recovery**: Wait for the price aggregation scheduler to successfully fetch fresh prices. Check `GET /api/prices` status field for system status.
