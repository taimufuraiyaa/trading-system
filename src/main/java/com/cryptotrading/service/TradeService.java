package com.cryptotrading.service;

import com.cryptotrading.dto.TradeRequest;
import com.cryptotrading.entity.*;
import com.cryptotrading.exception.InsufficientBalanceException;
import com.cryptotrading.exception.PriceNotFoundException;
import com.cryptotrading.exception.ServiceUnavailableException;
import com.cryptotrading.repository.AggregatedPriceRepository;
import com.cryptotrading.repository.TradeRepository;
import com.cryptotrading.repository.UserRepository;
import com.cryptotrading.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Executes buy/sell trades against aggregated prices and manages wallet balances atomically.
 *
 * <p>Uses {@link BalanceReservationService} to prevent concurrent overdraft via CAS-based
 * in-memory reservation before any DB mutation. The reservation is always released in a
 * {@code finally} block on both success and failure.</p>
 *
 * <h3>Key behaviors</h3>
 * <ul>
 *   <li>Validates trade requests (supported pair, positive quantity, system health)</li>
 *   <li>Fetches latest aggregated price — BUY uses ask price, SELL uses bid price</li>
 *   <li>Calculates fee via {@link FeeService} (default 0.1% of total amount)</li>
 *   <li>Handles idempotency: duplicate keys return the original trade without re-execution</li>
 *   <li>Catches {@link org.springframework.dao.DataIntegrityViolationException} for concurrent
 *       idempotency key races — returns the existing trade instead of a 500 error</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TradeService {

    private static final Set<String> SUPPORTED_PAIRS = Set.of("ETHUSDT", "BTCUSDT");

    private final TradeRepository tradeRepository;
    private final AggregatedPriceRepository aggregatedPriceRepository;
    private final WalletRepository walletRepository;
    private final UserRepository userRepository;
    private final BalanceReservationService balanceReservationService;
    private final FeeService feeService;
    private final HealthService healthService;

    /**
     * Execute a trade (BUY or SELL) against the latest aggregated price.
     *
     * <p>Validates the request, checks system health, reserves balance via
     * {@link BalanceReservationService}, performs atomic wallet updates, and records
     * the trade. Handles idempotency via the {@code idempotencyKey} — duplicate keys
     * return the original trade. Concurrent idempotency races are caught via
     * {@link DataIntegrityViolationException} from the DB unique constraint.</p>
     *
     * @param userId         the pre-authenticated user ID
     * @param idempotencyKey client-provided key to prevent duplicate execution
     * @param request        the trade request (pair, type, quantity)
     * @return the executed (or idempotently returned) {@link Trade}
     * @throws InsufficientBalanceException if available balance is insufficient
     * @throws PriceNotFoundException       if no aggregated price exists for the pair
     * @throws ServiceUnavailableException  if system is in maintenance or prices are stale
     */
    @Transactional
    public Trade executeTrade(Long userId, String idempotencyKey, TradeRequest request) {
        // Step 1: Idempotency check
        Optional<Trade> existingTrade = tradeRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
        if (existingTrade.isPresent()) {
            log.info("Idempotent return for userId={}, key={}", userId, idempotencyKey);
            return existingTrade.get();
        }

        // Step 2: Validate supported pair
        String pair = request.getTradingPair().toUpperCase();
        if (!SUPPORTED_PAIRS.contains(pair)) {
            throw new PriceNotFoundException("Unsupported trading pair: " + pair);
        }

        // Step 3: System health check
        if (healthService.isSystemInMaintenance()) {
            throw new ServiceUnavailableException("System is in maintenance mode. Trading is temporarily disabled.");
        }
        if (healthService.isPriceDataStale(pair)) {
            throw new ServiceUnavailableException("Price data for " + pair + " is stale. Please try again shortly.");
        }

        TradeType type = request.getTradeType();
        BigDecimal quantity = request.getQuantity();

        // Step 4: Get latest aggregated price
        AggregatedPrice latestPrice = aggregatedPriceRepository.findTopByTradingPairOrderByCreatedAtDesc(pair)
                .orElseThrow(() -> new PriceNotFoundException("No price available for " + pair));

        // Step 5: Determine execution price and calculate fee
        BigDecimal executionPrice = (type == TradeType.BUY) ? latestPrice.getAskPrice() : latestPrice.getBidPrice();
        BigDecimal totalAmount = executionPrice.multiply(quantity).setScale(8, RoundingMode.HALF_UP);

        FeeConfig feeConfig = feeService.getCurrentFeeConfig();
        BigDecimal feeRate = feeConfig.getFeePercentage();
        BigDecimal feeAmount = totalAmount.multiply(feeRate).setScale(8, RoundingMode.HALF_UP);

        String cryptoCurrency = pair.replace("USDT", "");

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));

        // Step 6: Reserve and execute
        try {
            if (type == TradeType.BUY) {
                return executeBuy(user, pair, executionPrice, quantity, totalAmount, feeRate, feeAmount, cryptoCurrency, idempotencyKey);
            } else {
                return executeSell(user, pair, executionPrice, quantity, totalAmount, feeRate, feeAmount, cryptoCurrency, idempotencyKey);
            }
        } catch (DataIntegrityViolationException e) {
            // Concurrent duplicate idempotency key — the other thread won the insert race.
            // Re-fetch and return the existing trade instead of a 500 error.
            log.warn("Concurrent idempotency collision for userId={}, key={}", userId, idempotencyKey);
            return tradeRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                    .orElseThrow(() -> new RuntimeException("Idempotency conflict but trade not found", e));
        }
    }

    private Trade executeBuy(User user, String pair, BigDecimal executionPrice, BigDecimal quantity,
                             BigDecimal totalAmount, BigDecimal feeRate, BigDecimal feeAmount,
                             String cryptoCurrency, String idempotencyKey) {
        Long userId = user.getId();
        BigDecimal netCost = totalAmount.add(feeAmount);

        if (!balanceReservationService.tryReserve(userId, "USDT", netCost)) {
            throw new InsufficientBalanceException("Insufficient USDT balance");
        }

        try {
            Wallet usdtWallet = walletRepository.findByUserIdAndCurrency(userId, "USDT")
                    .orElseThrow(() -> new RuntimeException("USDT wallet not found"));
            usdtWallet.setBalance(usdtWallet.getBalance().subtract(netCost));
            walletRepository.save(usdtWallet);

            Wallet cryptoWallet = walletRepository.findByUserIdAndCurrency(userId, cryptoCurrency)
                    .orElseThrow(() -> new RuntimeException(cryptoCurrency + " wallet not found"));
            cryptoWallet.setBalance(cryptoWallet.getBalance().add(quantity));
            walletRepository.save(cryptoWallet);

            Trade trade = saveTrade(user, pair, TradeType.BUY, executionPrice, quantity,
                    totalAmount, feeRate, feeAmount, netCost, idempotencyKey);

            log.info("BUY executed: userId={}, pair={}, qty={}, price={}, fee={}, netCost={}",
                    userId, pair, quantity, executionPrice, feeAmount, netCost);
            return trade;
        } finally {
            balanceReservationService.release(userId, "USDT", netCost);
        }
    }

    private Trade executeSell(User user, String pair, BigDecimal executionPrice, BigDecimal quantity,
                              BigDecimal totalAmount, BigDecimal feeRate, BigDecimal feeAmount,
                              String cryptoCurrency, String idempotencyKey) {
        Long userId = user.getId();
        BigDecimal netProceeds = totalAmount.subtract(feeAmount);

        if (!balanceReservationService.tryReserve(userId, cryptoCurrency, quantity)) {
            throw new InsufficientBalanceException("Insufficient " + cryptoCurrency + " balance");
        }

        try {
            Wallet cryptoWallet = walletRepository.findByUserIdAndCurrency(userId, cryptoCurrency)
                    .orElseThrow(() -> new RuntimeException(cryptoCurrency + " wallet not found"));
            cryptoWallet.setBalance(cryptoWallet.getBalance().subtract(quantity));
            walletRepository.save(cryptoWallet);

            Wallet usdtWallet = walletRepository.findByUserIdAndCurrency(userId, "USDT")
                    .orElseThrow(() -> new RuntimeException("USDT wallet not found"));
            usdtWallet.setBalance(usdtWallet.getBalance().add(netProceeds));
            walletRepository.save(usdtWallet);

            Trade trade = saveTrade(user, pair, TradeType.SELL, executionPrice, quantity,
                    totalAmount, feeRate, feeAmount, netProceeds, idempotencyKey);

            log.info("SELL executed: userId={}, pair={}, qty={}, price={}, fee={}, netProceeds={}",
                    userId, pair, quantity, executionPrice, feeAmount, netProceeds);
            return trade;
        } finally {
            balanceReservationService.release(userId, cryptoCurrency, quantity);
        }
    }

    public List<Trade> getTradeHistory(Long userId) {
        return tradeRepository.findByUserIdOrderByCreatedAtAsc(userId);
    }

    private Trade saveTrade(User user, String pair, TradeType type, BigDecimal price,
                            BigDecimal quantity, BigDecimal totalAmount, BigDecimal feeRate,
                            BigDecimal feeAmount, BigDecimal netAmount, String idempotencyKey) {
        Trade trade = new Trade();
        trade.setUser(user);
        trade.setTradingPair(pair);
        trade.setTradeType(type);
        trade.setPrice(price);
        trade.setQuantity(quantity);
        trade.setTotalAmount(totalAmount);
        trade.setFeePercentage(feeRate);
        trade.setFeeAmount(feeAmount);
        trade.setNetAmount(netAmount);
        trade.setIdempotencyKey(idempotencyKey);
        trade.setCreatedAt(LocalDateTime.now());
        return tradeRepository.save(trade);
    }
}
