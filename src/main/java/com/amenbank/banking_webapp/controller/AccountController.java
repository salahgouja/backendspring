package com.amenbank.banking_webapp.controller;

import com.amenbank.banking_webapp.dto.response.AccountResponse;
import com.amenbank.banking_webapp.dto.response.TransactionResponse;
import com.amenbank.banking_webapp.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

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

        @GetMapping
        @Operation(summary = "List all accounts for the authenticated user")
        public ResponseEntity<List<AccountResponse>> getMyAccounts(
                        @AuthenticationPrincipal UserDetails userDetails) {
                return ResponseEntity.ok(
                                accountService.getUserAccounts(userDetails.getUsername()));
        }

        @GetMapping("/{accountId}")
        @Operation(summary = "Get account details by ID")
        public ResponseEntity<AccountResponse> getAccount(
                        @PathVariable UUID accountId,
                        @AuthenticationPrincipal UserDetails userDetails) {
                return ResponseEntity.ok(
                                accountService.getAccountById(accountId, userDetails.getUsername()));
        }

        @GetMapping("/{accountId}/transactions")
        @Operation(summary = "Get paginated transaction history for an account")
        public ResponseEntity<List<TransactionResponse>> getTransactions(
                        @PathVariable UUID accountId,
                        @RequestParam(defaultValue = "0") int page,
                        @RequestParam(defaultValue = "20") int size,
                        @AuthenticationPrincipal UserDetails userDetails) {
                return ResponseEntity.ok(
                                accountService.getAccountTransactions(
                                                accountId, userDetails.getUsername(), page, size));
        }

        // ============================================================
        // Account Approval Workflow (AGENT / ADMIN only)
        // ============================================================

        @GetMapping("/pending")
        @Operation(summary = "List pending account requests", description = "Agents see pending from their agency, Admins see all")
        public ResponseEntity<List<AccountResponse>> getPendingAccounts(
                        @AuthenticationPrincipal UserDetails userDetails) {
                return ResponseEntity.ok(
                                accountService.getPendingAccounts(userDetails.getUsername()));
        }

        @PutMapping("/{accountId}/approve")
        @Operation(summary = "Approve a pending account")
        public ResponseEntity<AccountResponse> approveAccount(
                        @PathVariable UUID accountId,
                        @AuthenticationPrincipal UserDetails userDetails) {
                return ResponseEntity.ok(
                                accountService.approveAccount(accountId, userDetails.getUsername()));
        }

        @PutMapping("/{accountId}/reject")
        @Operation(summary = "Reject a pending account")
        public ResponseEntity<AccountResponse> rejectAccount(
                        @PathVariable UUID accountId,
                        @RequestBody(required = false) Map<String, String> body,
                        @AuthenticationPrincipal UserDetails userDetails) {
                String reason = body != null ? body.get("reason") : null;
                return ResponseEntity.ok(
                                accountService.rejectAccount(accountId, userDetails.getUsername(), reason));
        }
}
