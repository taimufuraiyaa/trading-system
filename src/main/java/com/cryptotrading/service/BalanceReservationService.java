package com.cryptotrading.service;

import com.cryptotrading.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Provides in-memory balance reservation to prevent concurrent trades from overdrawing a wallet.
 *
 * <p>Uses CAS (compare-and-set) operations on {@link AtomicReference AtomicReference&lt;BigDecimal&gt;}
 * for lock-free, thread-safe reservation management. Internally fetches the current DB balance
 * from {@link WalletRepository} so callers don't need to pre-fetch it.</p>
 *
 * <h3>Concurrency Design</h3>
 * <ul>
 *   <li>Each wallet key ({@code "userId:currency"}) has its own {@code AtomicReference<BigDecimal>}</li>
 *   <li>{@code tryReserve()} re-reads {@code dbBalance} from the DB on every CAS retry attempt,
 *       preventing stale-balance races where another thread's completed trade has updated the DB</li>
 *   <li>Max CAS retries capped at 10 to prevent infinite spin under extreme contention</li>
 *   <li>No locks are held during DB operations — only the lightweight CAS reservation</li>
 * </ul>
 *
 * <p>Since H2 is also in-memory, both the reservation cache and DB reset on restart — this is acceptable.</p>
 */
@Service
@RequiredArgsConstructor
public class BalanceReservationService {

    private final WalletRepository walletRepository;
    private final ConcurrentHashMap<String, AtomicReference<BigDecimal>> reservations = new ConcurrentHashMap<>();

    private String key(Long userId, String currency) {
        return userId + ":" + currency;
    }

    private AtomicReference<BigDecimal> getOrCreateRef(String key) {
        return reservations.computeIfAbsent(key, k -> new AtomicReference<>(BigDecimal.ZERO));
    }

    private static final int MAX_CAS_RETRIES = 10;

    /**
     * Atomically reserve an amount if available balance (dbBalance - reservedAmount) &gt;= amount.
     *
     * <p>Uses a CAS loop that re-reads {@code dbBalance} from the DB on every retry attempt
     * to avoid stale-balance races. Capped at {@value #MAX_CAS_RETRIES} retries.</p>
     *
     * @param userId   the user whose wallet to reserve against
     * @param currency the currency to reserve (e.g., "USDT", "ETH", "BTC")
     * @param amount   the amount to reserve
     * @return {@code true} if reservation succeeded, {@code false} if insufficient available balance
     */
    public boolean tryReserve(Long userId, String currency, BigDecimal amount) {
        String key = key(userId, currency);
        AtomicReference<BigDecimal> ref = getOrCreateRef(key);

        for (int attempt = 0; attempt < MAX_CAS_RETRIES; attempt++) {
            // Re-read dbBalance on every CAS attempt to avoid stale balance race
            BigDecimal dbBalance = walletRepository.findByUserIdAndCurrency(userId, currency)
                    .map(w -> w.getBalance())
                    .orElse(BigDecimal.ZERO);

            BigDecimal currentReserved = ref.get();
            if (dbBalance.subtract(currentReserved).compareTo(amount) < 0) {
                return false;
            }
            BigDecimal newReserved = currentReserved.add(amount);
            if (ref.compareAndSet(currentReserved, newReserved)) {
                return true;
            }
            // CAS failed — another thread modified reservations, retry with fresh data
        }
        // Exceeded max retries under extreme contention
        return false;
    }

    /**
     * Release a reservation after DB commit or rollback.
     *
     * <p>Uses a CAS loop to atomically subtract from reserved. Must be called in a
     * {@code finally} block to guarantee cleanup on both success and failure paths.</p>
     *
     * @param userId   the user whose reservation to release
     * @param currency the currency of the reservation
     * @param amount   the amount to release
     */
    public void release(Long userId, String currency, BigDecimal amount) {
        String key = key(userId, currency);
        AtomicReference<BigDecimal> ref = getOrCreateRef(key);

        while (true) {
            BigDecimal currentReserved = ref.get();
            BigDecimal newReserved = currentReserved.subtract(amount);
            if (newReserved.compareTo(BigDecimal.ZERO) < 0) {
                newReserved = BigDecimal.ZERO;
            }
            if (ref.compareAndSet(currentReserved, newReserved)) {
                return;
            }
        }
    }

    public BigDecimal getAvailableBalance(Long userId, String currency) {
        BigDecimal dbBalance = walletRepository.findByUserIdAndCurrency(userId, currency)
                .map(w -> w.getBalance())
                .orElse(BigDecimal.ZERO);
        BigDecimal reserved = getReservedAmount(userId, currency);
        return dbBalance.subtract(reserved);
    }

    public BigDecimal getReservedAmount(Long userId, String currency) {
        String key = key(userId, currency);
        AtomicReference<BigDecimal> ref = reservations.get(key);
        return ref != null ? ref.get() : BigDecimal.ZERO;
    }
}
