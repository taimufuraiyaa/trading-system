package com.cryptotrading.repository;

import com.cryptotrading.entity.Trade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TradeRepository extends JpaRepository<Trade, Long> {
    Optional<Trade> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);
    List<Trade> findByUserIdOrderByCreatedAtAsc(Long userId);
}
