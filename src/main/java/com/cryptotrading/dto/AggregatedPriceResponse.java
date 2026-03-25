package com.cryptotrading.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AggregatedPriceResponse {

    private String tradingPair;
    private BigDecimal bidPrice;
    private BigDecimal askPrice;
    private String bidSource;
    private String askSource;
    private LocalDateTime timestamp;
}
