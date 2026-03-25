package com.cryptotrading.controller;

import com.cryptotrading.dto.WalletResponse;
import com.cryptotrading.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * REST controller for wallet balance retrieval.
 *
 * <p>Returns USDT, ETH, and BTC balances for the pre-authenticated user.</p>
 */
@RestController
@RequestMapping("/api/wallets")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;

    @GetMapping
    public ResponseEntity<List<WalletResponse>> getWalletBalances() {
        // Pre-authenticated user with id=1
        Long userId = 1L;
        List<WalletResponse> wallets = walletService.getWalletBalances(userId).stream()
                .map(w -> new WalletResponse(w.getCurrency(), w.getBalance()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(wallets);
    }
}
