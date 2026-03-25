package com.cryptotrading.service;

import com.cryptotrading.dto.AggregatedPriceResponse;
import com.cryptotrading.dto.ExchangeTicker;
import com.cryptotrading.dto.PriceResponse;
import com.cryptotrading.entity.AggregatedPrice;
import com.cryptotrading.repository.AggregatedPriceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Computes best aggregated prices and manages price persistence.
 *
 * <p>For each supported trading pair (ETHUSDT, BTCUSDT), determines the best bid
 * (highest across exchanges) and best ask (lowest across exchanges), then persists
 * the result as an {@link AggregatedPrice} record.</p>
 *
 * <p>Enriches price responses with system status (HEALTHY/STALE/MAINTENANCE) by
 * consulting {@link HealthService}.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PriceService {

    private static final Set<String> SUPPORTED_PAIRS = Set.of("ETHUSDT", "BTCUSDT");

    private final AggregatedPriceRepository aggregatedPriceRepository;
    private final HealthService healthService;

    /**
     * Merge tickers from both exchanges and persist the best bid/ask for each supported pair.
     *
     * <p>For each pair: {@code bestBid = max(allBids)}, {@code bestAsk = min(allAsks)}.
     * If a pair has data from only one exchange, that exchange's price is used.
     * If a pair has no data, no record is created.</p>
     *
     * @param binanceTickers tickers fetched from Binance (may be empty on failure)
     * @param huobiTickers   tickers fetched from Huobi (may be empty on failure)
     */
    public void aggregateAndSave(List<ExchangeTicker> binanceTickers, List<ExchangeTicker> huobiTickers) {
        List<ExchangeTicker> allTickers = new ArrayList<>();
        allTickers.addAll(binanceTickers);
        allTickers.addAll(huobiTickers);

        for (String pair : SUPPORTED_PAIRS) {
            List<ExchangeTicker> pairTickers = allTickers.stream()
                    .filter(t -> t.getSymbol().equalsIgnoreCase(pair))
                    .collect(Collectors.toList());

            if (pairTickers.isEmpty()) continue;

            BigDecimal bestBid = BigDecimal.ZERO;
            String bidSource = "";
            BigDecimal bestAsk = null;
            String askSource = "";

            for (ExchangeTicker ticker : pairTickers) {
                if (ticker.getBidPrice().compareTo(bestBid) > 0) {
                    bestBid = ticker.getBidPrice();
                    bidSource = ticker.getSource();
                }
                if (bestAsk == null || ticker.getAskPrice().compareTo(bestAsk) < 0) {
                    bestAsk = ticker.getAskPrice();
                    askSource = ticker.getSource();
                }
            }

            AggregatedPrice price = new AggregatedPrice(pair, bestBid, bestAsk,
                    bidSource, askSource, LocalDateTime.now());
            aggregatedPriceRepository.save(price);
            log.info("Aggregated price for {}: bid={} ({}), ask={} ({})",
                    pair, bestBid, bidSource, bestAsk, askSource);
        }
    }

    public AggregatedPrice getLatestBestPrice(String tradingPair) {
        return aggregatedPriceRepository.findTopByTradingPairOrderByCreatedAtDesc(tradingPair)
                .orElse(null);
    }

    public List<AggregatedPrice> getAllLatestBestPrices() {
        List<AggregatedPrice> prices = new ArrayList<>();
        for (String pair : SUPPORTED_PAIRS) {
            aggregatedPriceRepository.findTopByTradingPairOrderByCreatedAtDesc(pair)
                    .ifPresent(prices::add);
        }
        return prices;
    }

    public PriceResponse getLatestPricesWithStatus() {
        String status = healthService.getSystemStatus();
        List<AggregatedPrice> latestPrices = getAllLatestBestPrices();

        List<AggregatedPriceResponse> priceResponses = latestPrices.stream()
                .map(p -> new AggregatedPriceResponse(
                        p.getTradingPair(),
                        p.getBidPrice(),
                        p.getAskPrice(),
                        p.getBidSource(),
                        p.getAskSource(),
                        p.getCreatedAt()))
                .collect(Collectors.toList());

        String message = null;
        if ("STALE".equals(status)) {
            message = "Price data may be outdated. Some exchange sources are unavailable.";
        } else if ("MAINTENANCE".equals(status)) {
            message = "All price sources are currently unavailable. Trading is disabled.";
        }

        return new PriceResponse(status, message, priceResponses);
    }
}
