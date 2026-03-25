package com.cryptotrading.dto.exchange;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BinanceTicker {

    private String symbol;
    private String bidPrice;
    private String askPrice;
}
