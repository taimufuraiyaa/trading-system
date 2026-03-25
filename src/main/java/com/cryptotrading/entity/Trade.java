package com.cryptotrading.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "trades",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "idempotency_key"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Trade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "trading_pair", nullable = false)
    private String tradingPair;

    @Column(name = "trade_type", nullable = false)
    @Enumerated(EnumType.STRING)
    private TradeType tradeType;

    @Column(nullable = false, precision = 18, scale = 8)
    private BigDecimal price;

    @Column(nullable = false, precision = 18, scale = 8)
    private BigDecimal quantity;

    @Column(name = "total_amount", nullable = false, precision = 18, scale = 8)
    private BigDecimal totalAmount;

    @Column(name = "fee_percentage", nullable = false, precision = 5, scale = 4)
    private BigDecimal feePercentage;

    @Column(name = "fee_amount", nullable = false, precision = 18, scale = 8)
    private BigDecimal feeAmount;

    @Column(name = "net_amount", nullable = false, precision = 18, scale = 8)
    private BigDecimal netAmount;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
