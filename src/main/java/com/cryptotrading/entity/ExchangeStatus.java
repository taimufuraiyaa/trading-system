package com.cryptotrading.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "exchange_status")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExchangeStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "exchange_name", nullable = false, unique = true)
    private String exchangeName;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "last_success_at")
    private LocalDateTime lastSuccessAt;

    @Column(name = "last_failure_at")
    private LocalDateTime lastFailureAt;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
