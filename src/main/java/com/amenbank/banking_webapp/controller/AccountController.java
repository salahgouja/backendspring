package com.amenbank.banking_webapp.controller;

import com.amenbank.banking_webapp.dto.request.CreateAccountRequest;
import com.amenbank.banking_webapp.dto.response.AccountResponse;
import com.amenbank.banking_webapp.dto.response.TransactionResponse;
import com.amenbank.banking_webapp.model.Transaction;
import com.amenbank.banking_webapp.service.AccountService;
import com.amenbank.banking_webapp.service.StatementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/accounts")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Accounts", description = "Bank account management endpoints")
public class AccountController {

        private final AccountService accountService;
        private final StatementService statementService;

        @GetMapping
        @Operation(summary = "List all accounts for the authenticated user")
        public ResponseEntity<List<AccountResponse>> getMyAccounts(
                        @AuthenticationPrincipal UserDetails userDetails) {
                return ResponseEntity.ok(accountService.getUserAccounts(userDetails.getUsername()));
        }

        @GetMapping("/{accountId}")
        @Operation(summary = "Get account details by ID")
        public ResponseEntity<AccountResponse> getAccount(
                        @PathVariable UUID accountId,
                        @AuthenticationPrincipal UserDetails userDetails) {
                return ResponseEntity.ok(accountService.getAccountById(accountId, userDetails.getUsername()));
        }

        // ── Create additional account (fix #12) ────────────
        @PostMapping
        @Operation(summary = "Request a new account (pending approval)")
        public ResponseEntity<AccountResponse> createAccount(
                        @AuthenticationPrincipal UserDetails userDetails,
                        @Valid @RequestBody CreateAccountRequest request) {
                return ResponseEntity.status(HttpStatus.CREATED)
                        .body(accountService.createAccount(userDetails.getUsername(), request));
        }

        @GetMapping("/{accountId}/transactions")
        @Operation(summary = "Get paginated transaction history for an account")
        public ResponseEntity<List<TransactionResponse>> getTransactions(
                        @PathVariable UUID accountId,
                        @RequestParam(defaultValue = "0") int page,
                        @RequestParam(defaultValue = "20") int size,
                        @AuthenticationPrincipal UserDetails userDetails) {
                return ResponseEntity.ok(accountService.getAccountTransactions(
                                accountId, userDetails.getUsername(), page, size));
        }

        // ── Transaction date-range filter (fix #30) ────────
        @GetMapping("/{accountId}/transactions/filter")
        @Operation(summary = "Get transactions filtered by date range")
        public ResponseEntity<List<TransactionResponse>> getTransactionsByDateRange(
                        @PathVariable UUID accountId,
                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
                        @RequestParam(defaultValue = "0") int page,
                        @RequestParam(defaultValue = "20") int size,
                        @AuthenticationPrincipal UserDetails userDetails) {
                return ResponseEntity.ok(accountService.getAccountTransactionsByDateRange(
                                accountId, userDetails.getUsername(), from, to, page, size));
        }

        // ── GAP-1: Advanced transaction search (amount, type, category, date) ──
        @GetMapping("/{accountId}/transactions/search")
        @Operation(summary = "Search transactions with advanced filters (date, amount, type, category)")
        public ResponseEntity<Page<TransactionResponse>> searchTransactions(
                        @PathVariable UUID accountId,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
                        @RequestParam(required = false) BigDecimal minAmount,
                        @RequestParam(required = false) BigDecimal maxAmount,
                        @RequestParam(required = false) Transaction.TransactionType type,
                        @RequestParam(required = false) String category,
                        @RequestParam(defaultValue = "0") int page,
                        @RequestParam(defaultValue = "20") int size,
                        @AuthenticationPrincipal UserDetails userDetails) {
                return ResponseEntity.ok(accountService.searchTransactions(
                                accountId, userDetails.getUsername(),
                                from, to, minAmount, maxAmount, type, category,
                                page, size));
        }

        // ── GAP-8: PDF Account Statement ───────────────────
        @GetMapping("/{accountId}/statement")
        @Operation(summary = "Download PDF account statement (Relevé de Compte)",
                description = "Generates and downloads a PDF statement for the specified date range. Format: from=2025-01-01&to=2025-12-31")
        public ResponseEntity<byte[]> downloadStatement(
                        @PathVariable UUID accountId,
                        @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate from,
                        @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate to,
                        @AuthenticationPrincipal UserDetails userDetails) {
                byte[] pdf = statementService.generateStatement(accountId, userDetails.getUsername(), from, to);
                String filename = String.format("releve_%s_%s_%s.pdf",
                                accountId.toString().substring(0, 8), from, to);
                return ResponseEntity.ok()
                        .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                        .header("Content-Type", "application/pdf")
                        .body(pdf);
        }

        // ============================================================
        // Account Approval Workflow (AGENT / ADMIN only)
        // ============================================================

        @GetMapping("/pending")
        @Operation(summary = "List pending account requests")
        public ResponseEntity<List<AccountResponse>> getPendingAccounts(
                        @AuthenticationPrincipal UserDetails userDetails) {
                return ResponseEntity.ok(accountService.getPendingAccounts(userDetails.getUsername()));
        }

        // ── Account history (fix #18) ──────────────────────
        @GetMapping("/history")
        @Operation(summary = "List approved/rejected/suspended accounts (Agent/Admin)")
        public ResponseEntity<List<AccountResponse>> getAccountHistory(
                        @AuthenticationPrincipal UserDetails userDetails) {
                return ResponseEntity.ok(accountService.getAccountHistory(userDetails.getUsername()));
        }

        @PutMapping("/{accountId}/approve")
        @Operation(summary = "Approve a pending account")
        public ResponseEntity<AccountResponse> approveAccount(
                        @PathVariable UUID accountId,
                        @AuthenticationPrincipal UserDetails userDetails) {
                return ResponseEntity.ok(accountService.approveAccount(accountId, userDetails.getUsername()));
        }

        @PutMapping("/{accountId}/reject")
        @Operation(summary = "Reject a pending account")
        public ResponseEntity<AccountResponse> rejectAccount(
                        @PathVariable UUID accountId,
                        @RequestBody(required = false) Map<String, String> body,
                        @AuthenticationPrincipal UserDetails userDetails) {
                String reason = body != null ? body.get("reason") : null;
                return ResponseEntity.ok(accountService.rejectAccount(accountId, userDetails.getUsername(), reason));
        }

        // ── Suspend / Unsuspend (fix #14) ──────────────────
        @PutMapping("/{accountId}/suspend")
        @Operation(summary = "Suspend an active account (Agent/Admin)")
        public ResponseEntity<AccountResponse> suspendAccount(
                        @PathVariable UUID accountId,
                        @RequestBody(required = false) Map<String, String> body,
                        @AuthenticationPrincipal UserDetails userDetails) {
                String reason = body != null ? body.get("reason") : null;
                return ResponseEntity.ok(accountService.suspendAccount(accountId, userDetails.getUsername(), reason));
        }

        @PutMapping("/{accountId}/unsuspend")
        @Operation(summary = "Reactivate a suspended account (Agent/Admin)")
        public ResponseEntity<AccountResponse> unsuspendAccount(
                        @PathVariable UUID accountId,
                        @AuthenticationPrincipal UserDetails userDetails) {
                return ResponseEntity.ok(accountService.unsuspendAccount(accountId, userDetails.getUsername()));
        }
}
