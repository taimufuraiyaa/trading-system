package com.cryptotrading.controller;

import com.cryptotrading.dto.PriceResponse;
import com.cryptotrading.service.PriceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller exposing the latest aggregated best prices.
 *
 * <p>Returns prices enriched with system status metadata (HEALTHY/STALE/MAINTENANCE)
 * so clients can assess data reliability before trading.</p>
 */
@RestController
@RequestMapping("/api/prices")
@RequiredArgsConstructor
public class PriceController {

    private final PriceService priceService;

    @GetMapping
    public ResponseEntity<PriceResponse> getLatestPrices() {
        return ResponseEntity.ok(priceService.getLatestPricesWithStatus());
    }
}
