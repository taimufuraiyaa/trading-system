package com.cryptotrading.dto.exchange;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class HuobiResponse {

    private String status;
    private List<HuobiTicker> data;
}
