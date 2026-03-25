package com.cryptotrading.service;

import com.cryptotrading.entity.ExchangeStatus;
import com.cryptotrading.entity.Status;
import com.cryptotrading.repository.AggregatedPriceRepository;
import com.cryptotrading.repository.ExchangeStatusRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Tracks the availability of external price sources and determines overall system health status.
 *
 * <p>Monitors UP/DOWN status for each exchange (Binance, Huobi) and determines if price data
 * is stale (no successful update in &gt;30 seconds). Computes overall system status:</p>
 * <ul>
 *   <li><b>HEALTHY</b>: All exchanges reachable and prices fresh</li>
 *   <li><b>STALE</b>: At least one exchange down or prices partially stale</li>
 *   <li><b>MAINTENANCE</b>: All exchanges down or all prices stale</li>
 * </ul>
 *
 * <p>This is an internal service — no REST endpoint is exposed. Status is consumed by
 * {@link PriceService} for enriching {@code GET /api/prices} responses and by
 * {@link TradeService} to reject trades when prices are unreliable.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HealthService {

    private static final Set<String> SUPPORTED_PAIRS = Set.of("ETHUSDT", "BTCUSDT");
    private static final long STALE_THRESHOLD_SECONDS = 30;

    private final ExchangeStatusRepository exchangeStatusRepository;
    private final AggregatedPriceRepository aggregatedPriceRepository;

    public void updateExchangeStatus(String exchange, Status status, String failureReason) {
        ExchangeStatus exchangeStatus = exchangeStatusRepository.findByExchangeName(exchange)
                .orElseGet(() -> {
                    ExchangeStatus es = new ExchangeStatus();
                    es.setExchangeName(exchange);
                    es.setStatus(Status.DOWN);
                    es.setUpdatedAt(LocalDateTime.now());
                    return es;
                });

        exchangeStatus.setStatus(status);
        exchangeStatus.setUpdatedAt(LocalDateTime.now());

        if (status == Status.UP) {
            exchangeStatus.setLastSuccessAt(LocalDateTime.now());
            exchangeStatus.setFailureReason(null);
        } else {
            exchangeStatus.setLastFailureAt(LocalDateTime.now());
            exchangeStatus.setFailureReason(failureReason);
        }

        exchangeStatusRepository.save(exchangeStatus);
    }

    public boolean isPriceDataStale(String tradingPair) {
        return aggregatedPriceRepository.findTopByTradingPairOrderByCreatedAtDesc(tradingPair)
                .map(price -> price.getCreatedAt().isBefore(LocalDateTime.now().minusSeconds(STALE_THRESHOLD_SECONDS)))
                .orElse(true);
    }

    public boolean isSystemInMaintenance() {
        List<ExchangeStatus> statuses = exchangeStatusRepository.findAll();
        boolean allExchangesDown = statuses.stream().allMatch(s -> s.getStatus() == Status.DOWN);

        if (allExchangesDown) {
            return true;
        }

        boolean allPricesStale = SUPPORTED_PAIRS.stream().allMatch(this::isPriceDataStale);
        return allPricesStale;
    }

    public String getSystemStatus() {
        List<ExchangeStatus> statuses = exchangeStatusRepository.findAll();
        boolean allExchangesDown = statuses.stream().allMatch(s -> s.getStatus() == Status.DOWN);
        boolean anyExchangeDown = statuses.stream().anyMatch(s -> s.getStatus() == Status.DOWN);
        boolean allPricesStale = SUPPORTED_PAIRS.stream().allMatch(this::isPriceDataStale);
        boolean anyPriceStale = SUPPORTED_PAIRS.stream().anyMatch(this::isPriceDataStale);

        if (allExchangesDown || allPricesStale) {
            return "MAINTENANCE";
        }
        if (anyExchangeDown || anyPriceStale) {
            return "STALE";
        }
        return "HEALTHY";
    }
}
