package com.cryptotrading.scheduler;

import com.cryptotrading.dto.ExchangeTicker;
import com.cryptotrading.dto.exchange.BinanceTicker;
import com.cryptotrading.dto.exchange.HuobiResponse;
import com.cryptotrading.entity.Status;
import com.cryptotrading.service.HealthService;
import com.cryptotrading.service.PriceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Fetches pricing data from Binance and Huobi every 10 seconds and delegates
 * aggregation to {@link PriceService}.
 *
 * <p>Uses an {@link AtomicBoolean} guard to prevent overlapping cycles — if a previous
 * cycle is still in progress (e.g., slow API response), the next scheduled invocation
 * is skipped with a warning log. The flag is reset in a {@code try/finally} block to
 * guarantee cleanup even on exceptions.</p>
 *
 * <p>API failures are handled gracefully: each exchange fetch is independent, so a failure
 * on one exchange still allows aggregation from the other. If both fail, the cycle is skipped.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PriceAggregationScheduler {

    private static final String BINANCE_URL = "https://api.binance.com/api/v3/ticker/bookTicker";
    private static final String HUOBI_URL = "https://api.huobi.pro/market/tickers";
    private static final Set<String> SUPPORTED_PAIRS = Set.of("ETHUSDT", "BTCUSDT");

    private final RestTemplate restTemplate;
    private final PriceService priceService;
    private final HealthService healthService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Scheduled price aggregation cycle. Fetches from Binance and Huobi, then delegates
     * to {@link PriceService#aggregateAndSave}. Guarded by {@link AtomicBoolean} to skip
     * overlapping cycles.
     */
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
            running.set(false);
        }
    }

    public List<ExchangeTicker> fetchBinancePrices() {
        try {
            ResponseEntity<List<BinanceTicker>> response = restTemplate.exchange(
                    BINANCE_URL,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<List<BinanceTicker>>() {}
            );

            List<BinanceTicker> tickers = response.getBody();
            if (tickers == null) {
                healthService.updateExchangeStatus("binance", Status.DOWN, "Empty response body");
                return new ArrayList<>();
            }

            List<ExchangeTicker> result = tickers.stream()
                    .filter(t -> SUPPORTED_PAIRS.contains(t.getSymbol()))
                    .map(t -> new ExchangeTicker(
                            t.getSymbol(),
                            new BigDecimal(t.getBidPrice()),
                            new BigDecimal(t.getAskPrice()),
                            "binance"
                    ))
                    .collect(Collectors.toList());

            healthService.updateExchangeStatus("binance", Status.UP, null);
            log.info("Fetched {} tickers from Binance", result.size());
            return result;
        } catch (Exception e) {
            log.error("Failed to fetch Binance prices: {}", e.getMessage());
            healthService.updateExchangeStatus("binance", Status.DOWN, e.getMessage());
            return new ArrayList<>();
        }
    }

    public List<ExchangeTicker> fetchHuobiPrices() {
        try {
            ResponseEntity<HuobiResponse> response = restTemplate.exchange(
                    HUOBI_URL,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<HuobiResponse>() {}
            );

            HuobiResponse huobiResponse = response.getBody();
            if (huobiResponse == null || !"ok".equals(huobiResponse.getStatus()) || huobiResponse.getData() == null) {
                healthService.updateExchangeStatus("huobi", Status.DOWN, "Invalid response");
                return new ArrayList<>();
            }

            List<ExchangeTicker> result = huobiResponse.getData().stream()
                    .filter(t -> SUPPORTED_PAIRS.contains(t.getSymbol().toUpperCase()))
                    .map(t -> new ExchangeTicker(
                            t.getSymbol().toUpperCase(),
                            t.getBid(),
                            t.getAsk(),
                            "huobi"
                    ))
                    .collect(Collectors.toList());

            healthService.updateExchangeStatus("huobi", Status.UP, null);
            log.info("Fetched {} tickers from Huobi", result.size());
            return result;
        } catch (Exception e) {
            log.error("Failed to fetch Huobi prices: {}", e.getMessage());
            healthService.updateExchangeStatus("huobi", Status.DOWN, e.getMessage());
            return new ArrayList<>();
        }
    }
}
