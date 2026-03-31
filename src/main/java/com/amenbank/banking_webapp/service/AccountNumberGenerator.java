package com.amenbank.banking_webapp.service;

import com.amenbank.banking_webapp.exception.BankingException;
import com.amenbank.banking_webapp.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Shared account number generator (BUG-1 / CQ-1 fix).
 * Single synchronized point for account number generation across all services.
 */
@Service
@RequiredArgsConstructor
public class AccountNumberGenerator {

    private static final String ACCOUNT_PREFIX = "AMEN";

    private final AccountRepository accountRepository;

    public synchronized String generate() {
        String accountNumber;
        int attempts = 0;
        do {
            accountNumber = ACCOUNT_PREFIX + String.format("%012d",
                    Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000_000_000L));
            attempts++;
        } while (accountRepository.findByAccountNumber(accountNumber).isPresent() && attempts < 10);
        if (attempts >= 10) {
            throw new BankingException("Erreur lors de la génération du numéro de compte");
        }
        return accountNumber;
    }
}

