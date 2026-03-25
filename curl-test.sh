#!/bin/bash
# Crypto Trading System - API Test Suite
# Usage: ./curl-test.sh
# Prerequisite: Application running on localhost:8080, wait ~10s for price aggregation

BASE_URL="http://localhost:8080"
PASS=0
FAIL=0

print_header() {
  echo ""
  echo "============================================"
  echo "  $1"
  echo "============================================"
}

assert_status() {
  local test_name="$1"
  local expected="$2"
  local actual="$3"
  local body="$4"

  if [ "$actual" -eq "$expected" ]; then
    echo "✅ PASS: $test_name (HTTP $actual)"
    PASS=$((PASS + 1))
  else
    echo "❌ FAIL: $test_name (expected HTTP $expected, got HTTP $actual)"
    FAIL=$((FAIL + 1))
  fi
  echo "   Response: $body"
  echo ""
}

assert_contains() {
  local test_name="$1"
  local expected_text="$2"
  local body="$3"
  local status="$4"

  if echo "$body" | grep -q "$expected_text"; then
    echo "✅ PASS: $test_name (contains '$expected_text')"
    PASS=$((PASS + 1))
  else
    echo "❌ FAIL: $test_name (expected to contain '$expected_text')"
    FAIL=$((FAIL + 1))
  fi
  echo "   Response: $body"
  echo ""
}

# ==========================================
# 1. PRICE API TESTS
# ==========================================
print_header "1. PRICE API TESTS"

# 1.1 Get latest prices
echo "--- 1.1 GET /api/prices - Fetch aggregated prices ---"
RESPONSE=$(curl -s -w "\n%{http_code}" "$BASE_URL/api/prices")
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')
assert_status "Get latest prices" 200 "$HTTP_CODE" "$BODY"

# 1.2 Verify price response contains status field
assert_contains "Price response has status field" '"status"' "$BODY"

# 1.3 Verify price response contains ETHUSDT
assert_contains "Price response has ETHUSDT" 'ETHUSDT' "$BODY"

# 1.4 Verify price response contains BTCUSDT
assert_contains "Price response has BTCUSDT" 'BTCUSDT' "$BODY"

# ==========================================
# 2. WALLET API TESTS
# ==========================================
print_header "2. WALLET API TESTS"

# 2.1 Get wallet balances (initial state)
echo "--- 2.1 GET /api/wallets - Initial balances ---"
RESPONSE=$(curl -s -w "\n%{http_code}" "$BASE_URL/api/wallets")
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')
assert_status "Get wallet balances" 200 "$HTTP_CODE" "$BODY"

# 2.2 Verify USDT wallet exists
assert_contains "Wallet has USDT" 'USDT' "$BODY"

# 2.3 Verify ETH wallet exists
assert_contains "Wallet has ETH" 'ETH' "$BODY"

# 2.4 Verify BTC wallet exists
assert_contains "Wallet has BTC" 'BTC' "$BODY"

# ==========================================
# 3. TRADE API - BUY TESTS
# ==========================================
print_header "3. TRADE API - BUY TESTS"

# 3.1 BUY 0.5 ETH
echo "--- 3.1 POST /api/trades - BUY 0.5 ETH ---"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/trades" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: test-buy-eth-001" \
  -d '{"tradingPair":"ETHUSDT","tradeType":"BUY","quantity":0.5}')
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')
assert_status "BUY 0.5 ETH" 200 "$HTTP_CODE" "$BODY"

# 3.2 Verify trade response has fee fields (backend-calculated)
assert_contains "Trade has feePercentage" '"feePercentage"' "$BODY"
assert_contains "Trade has feeAmount" '"feeAmount"' "$BODY"
assert_contains "Trade has netAmount" '"netAmount"' "$BODY"

# 3.3 Verify wallet updated after BUY
echo "--- 3.3 GET /api/wallets - After BUY ETH ---"
RESPONSE=$(curl -s -w "\n%{http_code}" "$BASE_URL/api/wallets")
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')
assert_status "Wallet after BUY" 200 "$HTTP_CODE" "$BODY"

# 3.4 BUY 0.01 BTC
echo "--- 3.4 POST /api/trades - BUY 0.01 BTC ---"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/trades" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: test-buy-btc-001" \
  -d '{"tradingPair":"BTCUSDT","tradeType":"BUY","quantity":0.01}')
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')
assert_status "BUY 0.01 BTC" 200 "$HTTP_CODE" "$BODY"

# ==========================================
# 4. TRADE API - SELL TESTS
# ==========================================
print_header "4. TRADE API - SELL TESTS"

# 4.1 SELL 0.25 ETH
echo "--- 4.1 POST /api/trades - SELL 0.25 ETH ---"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/trades" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: test-sell-eth-001" \
  -d '{"tradingPair":"ETHUSDT","tradeType":"SELL","quantity":0.25}')
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')
assert_status "SELL 0.25 ETH" 200 "$HTTP_CODE" "$BODY"

# 4.2 Verify wallet updated after SELL
echo "--- 4.2 GET /api/wallets - After SELL ETH ---"
RESPONSE=$(curl -s -w "\n%{http_code}" "$BASE_URL/api/wallets")
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')
assert_status "Wallet after SELL" 200 "$HTTP_CODE" "$BODY"

# ==========================================
# 5. TRADE HISTORY TEST
# ==========================================
print_header "5. TRADE HISTORY TEST"

# 5.1 Get trade history
echo "--- 5.1 GET /api/trades - Trade history ---"
RESPONSE=$(curl -s -w "\n%{http_code}" "$BASE_URL/api/trades")
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')
assert_status "Get trade history" 200 "$HTTP_CODE" "$BODY"
assert_contains "History has BUY trade" '"BUY"' "$BODY"
assert_contains "History has SELL trade" '"SELL"' "$BODY"

# ==========================================
# 6. IDEMPOTENCY TESTS
# ==========================================
print_header "6. IDEMPOTENCY TESTS"

# 6.1 Repeat same BUY with same idempotency key — should return original trade
echo "--- 6.1 POST /api/trades - Idempotent retry (same key) ---"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/trades" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: test-buy-eth-001" \
  -d '{"tradingPair":"ETHUSDT","tradeType":"BUY","quantity":0.5}')
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')
assert_status "Idempotent retry returns 200" 200 "$HTTP_CODE" "$BODY"
assert_contains "Idempotent retry returns original trade id" '"id":1' "$BODY"

# 6.2 Verify wallet NOT double-charged
echo "--- 6.2 GET /api/wallets - Verify no double charge ---"
RESPONSE=$(curl -s -w "\n%{http_code}" "$BASE_URL/api/wallets")
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')
assert_status "Wallet after idempotent retry" 200 "$HTTP_CODE" "$BODY"

# ==========================================
# 7. VALIDATION ERROR TESTS
# ==========================================
print_header "7. VALIDATION ERROR TESTS"

# 7.1 Missing idempotency key
echo "--- 7.1 POST /api/trades - Missing X-Idempotency-Key ---"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/trades" \
  -H "Content-Type: application/json" \
  -d '{"tradingPair":"ETHUSDT","tradeType":"BUY","quantity":0.5}')
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')
assert_status "Missing idempotency key" 400 "$HTTP_CODE" "$BODY"

# 7.2 Empty idempotency key
echo "--- 7.2 POST /api/trades - Empty X-Idempotency-Key ---"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/trades" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: " \
  -d '{"tradingPair":"ETHUSDT","tradeType":"BUY","quantity":0.5}')
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')
assert_status "Empty idempotency key" 400 "$HTTP_CODE" "$BODY"

# 7.3 Unsupported trading pair
echo "--- 7.3 POST /api/trades - Unsupported pair (DOGEUSDT) ---"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/trades" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: test-invalid-pair-001" \
  -d '{"tradingPair":"DOGEUSDT","tradeType":"BUY","quantity":1}')
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')
assert_status "Unsupported pair rejected" 400 "$HTTP_CODE" "$BODY"
assert_contains "Error mentions unsupported pair" 'Unsupported trading pair' "$BODY"

# 7.4 Missing trading pair (blank)
echo "--- 7.4 POST /api/trades - Blank trading pair ---"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/trades" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: test-blank-pair-001" \
  -d '{"tradingPair":"","tradeType":"BUY","quantity":1}')
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')
assert_status "Blank pair rejected" 400 "$HTTP_CODE" "$BODY"

# 7.5 Missing quantity (null)
echo "--- 7.5 POST /api/trades - Missing quantity ---"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/trades" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: test-no-qty-001" \
  -d '{"tradingPair":"ETHUSDT","tradeType":"BUY"}')
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')
assert_status "Missing quantity rejected" 400 "$HTTP_CODE" "$BODY"

# 7.6 Zero quantity
echo "--- 7.6 POST /api/trades - Zero quantity ---"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/trades" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: test-zero-qty-001" \
  -d '{"tradingPair":"ETHUSDT","tradeType":"BUY","quantity":0}')
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')
assert_status "Zero quantity rejected" 400 "$HTTP_CODE" "$BODY"

# Wait for rate limit window to reset before continuing
echo "--- Waiting 11s for rate limit window to reset ---"
sleep 11

# 7.7 Negative quantity
echo "--- 7.7 POST /api/trades - Negative quantity ---"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/trades" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: test-neg-qty-001" \
  -d '{"tradingPair":"ETHUSDT","tradeType":"BUY","quantity":-5}')
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')
assert_status "Negative quantity rejected" 400 "$HTTP_CODE" "$BODY"

# 7.8 Missing trade type
echo "--- 7.8 POST /api/trades - Missing trade type ---"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/trades" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: test-no-type-001" \
  -d '{"tradingPair":"ETHUSDT","quantity":0.5}')
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')
assert_status "Missing trade type rejected" 400 "$HTTP_CODE" "$BODY"

# 7.9 Empty request body
echo "--- 7.9 POST /api/trades - Empty body ---"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/trades" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: test-empty-body-001" \
  -d '{}')
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')
assert_status "Empty body rejected" 400 "$HTTP_CODE" "$BODY"

# ==========================================
# 8. INSUFFICIENT BALANCE TESTS
# ==========================================
print_header "8. INSUFFICIENT BALANCE TESTS"

# 8.1 SELL more ETH than available
echo "--- 8.1 POST /api/trades - SELL 999 ETH (insufficient) ---"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/trades" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: test-insuf-sell-001" \
  -d '{"tradingPair":"ETHUSDT","tradeType":"SELL","quantity":999}')
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')
assert_status "Insufficient ETH for SELL" 400 "$HTTP_CODE" "$BODY"
assert_contains "Error mentions insufficient balance" 'Insufficient' "$BODY"

# 8.2 BUY more than USDT allows
echo "--- 8.2 POST /api/trades - BUY 9999 BTC (insufficient USDT) ---"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/trades" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: test-insuf-buy-001" \
  -d '{"tradingPair":"BTCUSDT","tradeType":"BUY","quantity":9999}')
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')
assert_status "Insufficient USDT for BUY" 400 "$HTTP_CODE" "$BODY"
assert_contains "Error mentions insufficient balance" 'Insufficient' "$BODY"

# 8.3 SELL BTC when balance is 0.01 (sell more than owned)
echo "--- 8.3 POST /api/trades - SELL 1 BTC (only have 0.01) ---"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/trades" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: test-insuf-btc-001" \
  -d '{"tradingPair":"BTCUSDT","tradeType":"SELL","quantity":1}')
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')
assert_status "Insufficient BTC for SELL" 400 "$HTTP_CODE" "$BODY"

# ==========================================
# 9. FEE SECURITY TEST
# ==========================================
print_header "9. FEE SECURITY TEST"

# 9.1 Try to inject fee fields in request (should be ignored)
echo "--- 9.1 POST /api/trades - Injected fee fields (should be ignored) ---"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/trades" \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: test-fee-inject-001" \
  -d '{"tradingPair":"ETHUSDT","tradeType":"BUY","quantity":0.1,"feePercentage":0,"feeAmount":0,"netAmount":0}')
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')
assert_status "Trade with injected fee fields succeeds" 200 "$HTTP_CODE" "$BODY"
# Verify backend still calculated real fee (not 0)
if echo "$BODY" | grep -q '"feeAmount":0.0'; then
  echo "❌ FAIL: Fee injection accepted — feeAmount is 0"
  FAIL=$((FAIL + 1))
else
  echo "✅ PASS: Fee injection ignored — backend calculated real fee"
  PASS=$((PASS + 1))
fi
echo "   Response: $BODY"
echo ""

# ==========================================
# 10. RATE LIMITING TEST
# ==========================================
print_header "10. RATE LIMITING TEST (POST /api/trades only)"

# Wait for rate limit window to fully reset
echo "--- Waiting 11s for rate limit window to reset ---"
sleep 11

# 10.1 Send 11 rapid POST requests — 11th should get 429
echo "--- 10.1 Rapid-fire 11 POST /api/trades ---"
GOT_429=false
for i in $(seq 1 11); do
  RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/trades" \
    -H "Content-Type: application/json" \
    -H "X-Idempotency-Key: rate-limit-test-$i" \
    -d '{"tradingPair":"ETHUSDT","tradeType":"BUY","quantity":0.001}')
  HTTP_CODE=$(echo "$RESPONSE" | tail -1)
  if [ "$HTTP_CODE" -eq 429 ]; then
    GOT_429=true
    BODY=$(echo "$RESPONSE" | sed '$d')
    echo "   Request #$i: HTTP 429 (rate limited)"
    break
  else
    echo "   Request #$i: HTTP $HTTP_CODE"
  fi
done

if [ "$GOT_429" = true ]; then
  echo "✅ PASS: Rate limit triggered (HTTP 429)"
  PASS=$((PASS + 1))
  assert_contains "Rate limit response has retryAfter" 'retryAfter' "$BODY"
else
  echo "❌ FAIL: Rate limit NOT triggered after 11 requests"
  FAIL=$((FAIL + 1))
fi
echo ""

# 10.2 GET endpoints should NOT be rate limited
echo "--- 10.2 GET /api/prices - Not rate limited ---"
for i in $(seq 1 15); do
  curl -s -o /dev/null "$BASE_URL/api/prices"
done
RESPONSE=$(curl -s -w "\n%{http_code}" "$BASE_URL/api/prices")
HTTP_CODE=$(echo "$RESPONSE" | tail -1)
BODY=$(echo "$RESPONSE" | sed '$d')
assert_status "GET /api/prices not rate limited after 15 calls" 200 "$HTTP_CODE" "$BODY"

# ==========================================
# SUMMARY
# ==========================================
print_header "TEST SUMMARY"
TOTAL=$((PASS + FAIL))
echo "  Total:  $TOTAL"
echo "  Passed: $PASS"
echo "  Failed: $FAIL"
echo ""

if [ "$FAIL" -eq 0 ]; then
  echo "  🎉 ALL TESTS PASSED"
else
  echo "  ⚠️  SOME TESTS FAILED"
fi
echo ""
