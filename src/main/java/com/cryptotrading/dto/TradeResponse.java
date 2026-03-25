package com.cryptotrading.dto;

import com.cryptotrading.entity.TradeType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TradeResponse {

    private Long id;
    private String tradingPair;
    private TradeType tradeType;
    private BigDecimal price;
    private BigDecimal quantity;
    private BigDecimal totalAmount;
    private BigDecimal feePercentage;
    private BigDecimal feeAmount;
    private BigDecimal netAmount;
    private LocalDateTime timestamp;
}
