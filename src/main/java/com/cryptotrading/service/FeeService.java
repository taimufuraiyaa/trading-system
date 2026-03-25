package com.cryptotrading.service;

import com.cryptotrading.entity.FeeConfig;
import com.cryptotrading.repository.FeeConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Calculates and manages trading fees applied to each trade transaction.
 *
 * <p>Fee is computed as a percentage of the trade's total amount (default: 0.1%).
 * The fee configuration is stored in the database and can be updated without redeployment.
 * Fee amounts are always non-negative and rounded to scale 8 with {@code HALF_UP}.</p>
 */
@Service
@RequiredArgsConstructor
public class FeeService {

    private final FeeConfigRepository feeConfigRepository;

    public FeeConfig getCurrentFeeConfig() {
        return feeConfigRepository.findByActiveTrue()
                .orElseThrow(() -> new RuntimeException("No active fee configuration found"));
    }

    /**
     * Calculate fee as a percentage of the trade's total amount.
     *
     * @param totalAmount the trade's total amount (price × quantity)
     * @return the fee amount, rounded to scale 8 with {@code HALF_UP}
     */
    public BigDecimal calculateFee(BigDecimal totalAmount) {
        FeeConfig feeConfig = getCurrentFeeConfig();
        return totalAmount.multiply(feeConfig.getFeePercentage())
                .setScale(8, RoundingMode.HALF_UP);
    }
}
