package com.cryptotrading.controller;

import com.cryptotrading.dto.TradeRequest;
import com.cryptotrading.dto.TradeResponse;
import com.cryptotrading.entity.Trade;
import com.cryptotrading.service.TradeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * REST controller for trade execution and trade history retrieval.
 *
 * <p>{@code POST /api/trades} executes a trade with idempotency support via the
 * {@code X-Idempotency-Key} header. {@code GET /api/trades} returns the user's
 * trade history in chronological order.</p>
 *
 * <p>Trade requests are validated via Bean Validation ({@code @Valid}) before reaching
 * the service layer. Rate limiting is enforced by {@link com.cryptotrading.filter.RateLimitFilter}
 * on the POST endpoint only.</p>
 */
@RestController
@RequestMapping("/api/trades")
@RequiredArgsConstructor
public class TradeController {

    private final TradeService tradeService;

    @PostMapping
    public ResponseEntity<TradeResponse> executeTrade(
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody TradeRequest request) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("X-Idempotency-Key header is required for trade execution.");
        }

        // Pre-authenticated user with id=1
        Long userId = 1L;
        Trade trade = tradeService.executeTrade(userId, idempotencyKey, request);
        return ResponseEntity.ok(toResponse(trade));
    }

    @GetMapping
    public ResponseEntity<List<TradeResponse>> getTradeHistory() {
        Long userId = 1L;
        List<TradeResponse> history = tradeService.getTradeHistory(userId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(history);
    }

    private TradeResponse toResponse(Trade trade) {
        return new TradeResponse(
                trade.getId(),
                trade.getTradingPair(),
                trade.getTradeType(),
                trade.getPrice(),
                trade.getQuantity(),
                trade.getTotalAmount(),
                trade.getFeePercentage(),
                trade.getFeeAmount(),
                trade.getNetAmount(),
                trade.getCreatedAt()
        );
    }
}
