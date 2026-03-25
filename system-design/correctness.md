## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a system-essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.*

### Property 1: Best Price Aggregation

*For any* set of exchange tickers from Binance and Huobi for a supported trading pair, the aggregated bid price shall equal the maximum bid across all tickers, and the aggregated ask price shall equal the minimum ask across all tickers.

**Validates: Requirements 1.2, 1.3**

### Property 2: Wallet Balance Conservation

*For any* valid trade execution (BUY or SELL), the sum of value deducted from the source wallet and value credited to the destination wallet shall differ by exactly the fee amount. Specifically: for a BUY, preTradeUSDT == postTradeUSDT + (quantity × askPrice) + feeAmount; for a SELL, postTradeUSDT == preTradeUSDT + (quantity × bidPrice) - feeAmount.

**Validates: Requirements 3.1, 3.2**

### Property 3: Non-Negative Wallet Balances

*For any* sequence of trade executions, all wallet balances shall remain greater than or equal to zero. If a trade would cause any wallet balance to become negative, the BalanceReservationService shall reject the reservation and the trade shall not execute.

**Validates: Requirements 6.2, 7.1**

### Property 4: Trade Amount Consistency

*For any* trade record, the totalAmount field shall equal the execution price multiplied by the quantity.

**Validates: Requirement 3.3**

### Property 5: BUY Uses Ask Price, SELL Uses Bid Price

*For any* BUY trade, the execution price shall equal the Best_Ask from the latest aggregated price for that pair. *For any* SELL trade, the execution price shall equal the Best_Bid from the latest aggregated price for that pair.

**Validates: Requirements 3.1, 3.2**

### Property 6: Supported Pairs Only

*For any* trade request with a trading pair that is not ETHUSDT or BTCUSDT, the system shall reject the request. All persisted trades and aggregated prices shall have a trading pair in {ETHUSDT, BTCUSDT}.

**Validates: Requirement 3.5**

### Property 7: Fee Calculation Correctness

*For any* trade with a positive total amount and a valid fee percentage (0 ≤ feePercentage < 1), the fee amount shall equal round(totalAmount × feePercentage, 8, HALF_UP), and the fee amount shall be non-negative.

**Validates: Requirements 4.1, 4.5**

### Property 8: Net Amount by Trade Type

*For any* BUY trade, the net amount shall equal totalAmount + feeAmount. *For any* SELL trade, the net amount shall equal totalAmount - feeAmount.

**Validates: Requirements 4.3, 4.4**

### Property 9: Idempotency Guarantee

*For any* two trade requests submitted by the same user with the same idempotency key, the system shall return the same trade result and the total number of persisted trades for that (userId, idempotencyKey) combination shall be exactly 1.

**Validates: Requirements 5.3, 5.4**

### Property 10: Concurrent Reservation Safety

*For any* two concurrent trades targeting the same wallet where the combined cost exceeds the database balance, the BalanceReservationService shall reject at least one trade, ensuring the total reserved amount never exceeds the database balance.

**Validates: Requirement 6.4**

### Property 11: Trade History Chronological Order

*For any* user's trade history, the returned list shall be sorted in ascending order by creation timestamp, such that for all consecutive entries i and j where i < j, entry i's timestamp is less than or equal to entry j's timestamp.

**Validates: Requirement 11.2**

### Property 12: Price Response Status Accuracy

*For any* price response, the status field shall be "HEALTHY" when all exchanges are reachable and all prices are fresh (within 30 seconds), "STALE" when at least one exchange is down or at least one price is stale but not all sources are unavailable, and "MAINTENANCE" when all exchanges are down or all prices are stale.

**Validates: Requirements 2.2, 2.3, 2.4**

### Property 13: Trade Rejection on Unreliable Prices

*For any* trade request submitted when the latest aggregated price for the requested pair is older than 30 seconds or the system is in MAINTENANCE status, the TradeService shall reject the trade with an appropriate error response and no wallet mutations shall occur.

**Validates: Requirements 12.3, 12.4, 12.5, 14.2, 14.3**

### Property 14: Rate Limit Enforcement

*For any* user submitting more than 10 POST /api/trades requests within a 10-second sliding window, the excess requests shall receive HTTP 429 responses. *For any* number of GET requests to /api/prices, /api/wallets, or /api/trades, no rate limiting shall be applied.