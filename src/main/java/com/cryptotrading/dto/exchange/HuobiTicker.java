package com.cryptotrading.dto.exchange;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class HuobiTicker {

    private String symbol;
    private BigDecimal bid;
    private BigDecimal ask;
}
