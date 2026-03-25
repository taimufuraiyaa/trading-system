package com.cryptotrading.repository;

import com.cryptotrading.entity.AggregatedPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AggregatedPriceRepository extends JpaRepository<AggregatedPrice, Long> {
    Optional<AggregatedPrice> findTopByTradingPairOrderByCreatedAtDesc(String tradingPair);
}
