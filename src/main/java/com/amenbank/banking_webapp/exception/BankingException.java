package com.amenbank.banking_webapp.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Custom business exception for banking operations.
 * Returns HTTP 400 Bad Request by default.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class BankingException extends RuntimeException {

    public BankingException(String message) {
        super(message);
    }

    // ── Specific subclasses ────────────────────────────────

    @ResponseStatus(HttpStatus.NOT_FOUND)
    public static class NotFoundException extends BankingException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    @ResponseStatus(HttpStatus.FORBIDDEN)
    public static class ForbiddenException extends BankingException {
        public ForbiddenException(String message) {
            super(message);
        }
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    public static class InsufficientFundsException extends BankingException {
        public InsufficientFundsException(String message) {
            super(message);
        }
    }
}
