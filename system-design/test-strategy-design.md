## Testing Strategy

### Unit Testing Approach

- Test `PriceService.computeBestPrice()` with various combinations of exchange data
- Test `TradeService.executeTrade()` for BUY and SELL scenarios with mocked repositories
- Test wallet balance calculations with BigDecimal precision
- Test exchange response parsing for both Binance and Huobi formats
- Coverage goal: 80%+ on service layer

### Property-Based Testing Approach

**Property Test Library**: jqwik (Java property-based testing)

Key properties to test:
- For any set of exchange tickers, the aggregated bid is always the maximum bid
- For any set of exchange tickers, the aggregated ask is always the minimum ask
- For any valid trade, wallet balances remain non-negative
- For any trade, totalAmount always equals price × quantity
- BUY trades always use ask price, SELL trades always use bid price

### Integration Testing Approach

- Test full trade flow: price aggregation → trade execution → wallet update → history retrieval
- Test with H2 in-memory database using `@SpringBootTest`
- Test scheduler execution with `@SpyBean` to verify 10-second interval
- Test REST endpoints with `MockMvc` for request/response validation
- Test concurrent trade execution to verify transactional integrity

## Performance Considerations

- H2 in-memory database provides fast read/write for this use case
- Price aggregation runs on a fixed 10-second schedule; external API calls should have reasonable timeouts (5 seconds) to avoid blocking
- Consider indexing `aggregated_prices` on `(trading_pair, created_at DESC)` for fast latest-price lookups
- Consider indexing `trades` on `(user_id, created_at)` for efficient history queries
- BigDecimal with scale 8 provides sufficient precision for crypto trading without floating-point errors

## Security Considerations

- User authentication/authorization is assumed to be handled externally (per requirements)
- All monetary operations use `BigDecimal` to prevent floating-point precision issues
- Trade execution is wrapped in `@Transactional` to prevent partial updates
- Input validation on all API endpoints (pair, quantity, trade type)
- Database CHECK constraint ensures wallet balances cannot go negative
- No sensitive data (private keys, passwords) is stored or transmitted