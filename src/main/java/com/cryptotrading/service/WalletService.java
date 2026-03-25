package com.cryptotrading.service;

import com.cryptotrading.entity.Wallet;
import com.cryptotrading.exception.WalletNotFoundException;
import com.cryptotrading.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Manages user wallet balances for USDT, ETH, and BTC currencies.
 */
@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletRepository walletRepository;

    public List<Wallet> getWalletBalances(Long userId) {
        return walletRepository.findByUserId(userId);
    }

    public Wallet getWalletBalance(Long userId, String currency) {
        return walletRepository.findByUserIdAndCurrency(userId, currency)
                .orElseThrow(() -> new WalletNotFoundException(
                        "Wallet not found for user " + userId + " and currency " + currency));
    }
}
