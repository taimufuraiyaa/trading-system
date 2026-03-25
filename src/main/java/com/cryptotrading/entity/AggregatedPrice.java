package com.cryptotrading.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "aggregated_prices")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AggregatedPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_pair", nullable = false)
    private String tradingPair;

    @Column(name = "bid_price", nullable = false, precision = 18, scale = 8)
    private BigDecimal bidPrice;

    @Column(name = "ask_price", nullable = false, precision = 18, scale = 8)
    private BigDecimal askPrice;

    @Column(name = "bid_source", nullable = false)
    private String bidSource;

    @Column(name = "ask_source", nullable = false)
    private String askSource;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public AggregatedPrice(String tradingPair, BigDecimal bidPrice, BigDecimal askPrice,
                           String bidSource, String askSource, LocalDateTime createdAt) {
        this.tradingPair = tradingPair;
        this.bidPrice = bidPrice;
        this.askPrice = askPrice;
        this.bidSource = bidSource;
        this.askSource = askSource;
        this.createdAt = createdAt;
    }
}
