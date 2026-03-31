package com.amenbank.banking_webapp.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Catches all exceptions and returns a clean JSON error response.
 * Without this, Spring Security swallows exceptions as 403/500
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // ── 404 — Not Found ────────────────────────────────────
    @ExceptionHandler(BankingException.NotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(BankingException.NotFoundException ex) {
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    // ── 403 — Forbidden (ownership violations) ─────────────
    @ExceptionHandler(BankingException.ForbiddenException.class)
    public ResponseEntity<Map<String, Object>> handleForbidden(BankingException.ForbiddenException ex) {
        return buildResponse(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    // ── 409 — Insufficient Funds ───────────────────────────
    @ExceptionHandler(BankingException.InsufficientFundsException.class)
    public ResponseEntity<Map<String, Object>> handleInsufficientFunds(BankingException.InsufficientFundsException ex) {
        return buildResponse(HttpStatus.CONFLICT, ex.getMessage());
    }

    // ── 400 — General banking errors ───────────────────────
    @ExceptionHandler(BankingException.class)
    public ResponseEntity<Map<String, Object>> handleBankingException(BankingException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    // ── 400 — Validation errors (@Valid) ───────────────────
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return buildResponse(HttpStatus.BAD_REQUEST, errors);
    }

    // ── 401 — Bad credentials ──────────────────────────────
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleBadCredentials(BadCredentialsException ex) {
        return buildResponse(HttpStatus.UNAUTHORIZED, "Email ou mot de passe incorrect");
    }

    // ── 423 — Account locked (brute-force protection) ──────
    @ExceptionHandler(LockedException.class)
    public ResponseEntity<Map<String, Object>> handleLocked(LockedException ex) {
        return buildResponse(HttpStatus.LOCKED, ex.getMessage());
    }

    // ── 403 — Account disabled ─────────────────────────────
    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<Map<String, Object>> handleDisabled(DisabledException ex) {
        return buildResponse(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    // ── 403 — Spring Security access denied ────────────────
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        return buildResponse(HttpStatus.FORBIDDEN, "Accès refusé — permissions insuffisantes");
    }

    // ── 500 — Catch-all for unexpected errors ──────────────
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception ex) {
        log.error("Unhandled exception", ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                "Erreur interne du serveur: " + ex.getMessage());
    }

    // ── 400 — Request param type mismatch (e.g., invalid enum) ──
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("parameter", ex.getName());
        details.put("rejectedValue", ex.getValue());

        Class<?> requiredType = ex.getRequiredType();
        String message;

        if (requiredType != null && requiredType.isEnum()) {
            String allowedValues = Arrays.stream(requiredType.getEnumConstants())
                    .map(Object::toString)
                    .collect(Collectors.joining(", "));
            details.put("allowedValues", allowedValues);
            message = "Valeur invalide pour le parametre '" + ex.getName() + "'. " +
                    "Valeurs autorisees: " + allowedValues;
        } else {
            String expectedType = requiredType != null ? requiredType.getSimpleName() : "type attendu";
            details.put("expectedType", expectedType);
            message = "Valeur invalide pour le parametre '" + ex.getName() +
                    "'. Type attendu: " + expectedType;
        }

        return buildResponse(HttpStatus.BAD_REQUEST, message, details);
    }

    // ── Build JSON response ────────────────────────────────
    private ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String message) {
        return buildResponse(status, message, null);
    }

    private ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String message, Map<String, Object> details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        if (details != null && !details.isEmpty()) {
            body.put("details", details);
        }
        return ResponseEntity.status(status).body(body);
    }
}
