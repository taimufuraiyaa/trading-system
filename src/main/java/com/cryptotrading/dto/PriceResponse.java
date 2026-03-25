package com.cryptotrading.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PriceResponse {

    private String status;
    private String message;
    private List<AggregatedPriceResponse> prices;
}
