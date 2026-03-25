package com.cryptotrading.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExchangeTicker {

    private String symbol;
    private BigDecimal bidPrice;
    private BigDecimal askPrice;
    private String source;
}
