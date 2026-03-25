package com.cryptotrading.repository;

import com.cryptotrading.entity.ExchangeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExchangeStatusRepository extends JpaRepository<ExchangeStatus, Long> {
    Optional<ExchangeStatus> findByExchangeName(String exchangeName);
    List<ExchangeStatus> findAll();
}
