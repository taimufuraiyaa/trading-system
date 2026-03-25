package com.cryptotrading.dto;

import com.cryptotrading.entity.TradeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TradeRequest {

    @NotBlank(message = "Trading pair is required")
    private String tradingPair;

    @NotNull(message = "Trade type is required")
    private TradeType tradeType;

    @NotNull(message = "Quantity is required")
    @Positive(message = "Quantity must be greater than zero")
    private BigDecimal quantity;
}
