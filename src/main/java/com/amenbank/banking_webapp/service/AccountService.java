package com.amenbank.banking_webapp.service;

import com.amenbank.banking_webapp.dto.request.CreateAccountRequest;
import com.amenbank.banking_webapp.dto.response.AccountResponse;
import com.amenbank.banking_webapp.dto.response.TransactionResponse;
import com.amenbank.banking_webapp.exception.BankingException;
import com.amenbank.banking_webapp.model.*;
import com.amenbank.banking_webapp.repository.AccountRepository;
import com.amenbank.banking_webapp.repository.NotificationRepository;
import com.amenbank.banking_webapp.repository.TransactionRepository;
import com.amenbank.banking_webapp.repository.UserRepository;
import com.amenbank.banking_webapp.repository.specification.TransactionSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;
    private final AuditService auditService;
    private final AccountNumberGenerator accountNumberGenerator;

    @Transactional(readOnly = true)
    public List<AccountResponse> getUserAccounts(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BankingException.NotFoundException("User not found"));
        return accountRepository.findByUserId(user.getId())
                .stream().map(this::toAccountResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccountById(UUID accountId, String email) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BankingException.NotFoundException("Account not found"));
        if (!account.getUser().getEmail().equals(email)) {
            throw new BankingException.ForbiddenException("Access denied");
        }
        return toAccountResponse(account);
    }

    @Transactional(readOnly = true)
    public List<TransactionResponse> getAccountTransactions(UUID accountId, String email, int page, int size) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BankingException.NotFoundException("Account not found"));
        if (!account.getUser().getEmail().equals(email)) {
            throw new BankingException.ForbiddenException("Access denied");
        }
        return transactionRepository
                .findByAccountIdOrderByCreatedAtDesc(accountId, PageRequest.of(page, size))
                .stream().map(this::toTransactionResponse).collect(Collectors.toList());
    }

    // ============================================================
    // Transactions by date range (fix #30)
    // ============================================================
    @Transactional(readOnly = true)
    public List<TransactionResponse> getAccountTransactionsByDateRange(
            UUID accountId, String email, LocalDateTime from, LocalDateTime to, int page, int size) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BankingException.NotFoundException("Account not found"));
        if (!account.getUser().getEmail().equals(email)) {
            throw new BankingException.ForbiddenException("Access denied");
        }
        return transactionRepository
                .findByAccountIdAndCreatedAtBetweenOrderByCreatedAtDesc(accountId, from, to, PageRequest.of(page, size))
                .stream().map(this::toTransactionResponse).collect(Collectors.toList());
    }

    // ============================================================
    // GAP-1: Advanced transaction search (amount, type, category, date)
    // ============================================================
    @Transactional(readOnly = true)
    public Page<TransactionResponse> searchTransactions(
            UUID accountId, String email,
            LocalDateTime from, LocalDateTime to,
            BigDecimal minAmount, BigDecimal maxAmount,
            Transaction.TransactionType type, String category,
            int page, int size) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BankingException.NotFoundException("Account not found"));
        if (!account.getUser().getEmail().equals(email)) {
            throw new BankingException.ForbiddenException("Access denied");
        }

        String normalizedCategory = (category != null && !category.isBlank()) ? category.trim() : null;

        return transactionRepository.findAll(
                TransactionSpecification.withFilters(
                        accountId, from, to, minAmount, maxAmount, type, normalizedCategory
                ),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))
        )
                .map(this::toTransactionResponse);
    }

    // ============================================================
    // Create additional account (fix #12)
    // ============================================================
    @Transactional
    public AccountResponse createAccount(String email, CreateAccountRequest request) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BankingException.NotFoundException("User not found"));

        // Validate: COMMERCIAL accounts only for COMMERCANT
        if (request.getAccountType() == Account.AccountType.COMMERCIAL
                && user.getUserType() != User.UserType.COMMERCANT) {
            throw new BankingException("Les comptes COMMERCIAL sont réservés aux commerçants");
        }

        Account account = Account.builder()
                .user(user)
                .accountNumber(accountNumberGenerator.generate())
                .accountType(request.getAccountType())
                .balance(BigDecimal.ZERO)
                .isActive(false)
                .status(Account.AccountStatus.PENDING_APPROVAL)
                .currency(request.getCurrency() != null ? request.getCurrency() : "TND")
                .build();
        accountRepository.save(account);

        // Notify agency agents
        if (user.getAgency() != null) {
            List<User> agents = userRepository.findByUserTypeAndAgency(
                    User.UserType.AGENT, user.getAgency());
            for (User agent : agents) {
                notificationRepository.save(Notification.builder()
                        .user(agent).type(Notification.NotificationType.ACCOUNT)
                        .title("Nouvelle demande de compte")
                        .body(String.format("%s (%s) demande l'ouverture d'un compte %s.",
                                user.getFullNameFr(), user.getCin(), request.getAccountType()))
                        .build());
            }
        }

        auditService.log(AuditLog.AuditAction.ACCOUNT_CREATED, email,
                "Account", account.getId().toString(),
                "New " + request.getAccountType() + " account created — pending approval");

        log.info("New account request {} by {}", account.getAccountNumber(), email);
        return toAccountResponse(account);
    }

    // ============================================================
    // Account Approval Workflow
    // ============================================================

    @Transactional(readOnly = true)
    public List<AccountResponse> getPendingAccounts(String agentEmail) {
        User agent = userRepository.findByEmail(agentEmail)
                .orElseThrow(() -> new BankingException.NotFoundException("Agent not found"));
        if (agent.getUserType() == User.UserType.ADMIN) {
            return accountRepository.findByStatusOrderByCreatedAtDesc(Account.AccountStatus.PENDING_APPROVAL)
                    .stream().map(this::toAccountResponse).collect(Collectors.toList());
        }
        if (agent.getAgency() == null) {
            throw new BankingException("Agent non affecté à une agence");
        }
        return accountRepository.findByUserAgencyIdAndStatus(
                agent.getAgency().getId(), Account.AccountStatus.PENDING_APPROVAL)
                .stream().map(this::toAccountResponse).collect(Collectors.toList());
    }

    // ── Account history for agents (fix #18) ────────────
    @Transactional(readOnly = true)
    public List<AccountResponse> getAccountHistory(String agentEmail) {
        User agent = userRepository.findByEmail(agentEmail)
                .orElseThrow(() -> new BankingException.NotFoundException("Agent not found"));
        List<Account.AccountStatus> statuses = List.of(
                Account.AccountStatus.ACTIVE, Account.AccountStatus.CLOSED,
                Account.AccountStatus.SUSPENDED);

        if (agent.getUserType() == User.UserType.ADMIN) {
            return accountRepository.findAll().stream()
                    .filter(a -> statuses.contains(a.getStatus()))
                    .map(this::toAccountResponse).collect(Collectors.toList());
        }
        if (agent.getAgency() == null) return List.of();
        return accountRepository.findByUserAgencyIdAndStatusIn(agent.getAgency().getId(), statuses)
                .stream().map(this::toAccountResponse).collect(Collectors.toList());
    }

    @Transactional
    public AccountResponse approveAccount(UUID accountId, String agentEmail) {
        User agent = userRepository.findByEmail(agentEmail)
                .orElseThrow(() -> new BankingException.NotFoundException("Agent not found"));
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BankingException.NotFoundException("Compte introuvable"));
        validateAgentAuthority(agent, account);
        if (account.getStatus() != Account.AccountStatus.PENDING_APPROVAL) {
            throw new BankingException("Ce compte n'est pas en attente d'approbation");
        }

        account.setStatus(Account.AccountStatus.ACTIVE);
        account.setIsActive(true);
        accountRepository.save(account);

        notificationRepository.save(Notification.builder()
                .user(account.getUser()).type(Notification.NotificationType.ACCOUNT)
                .title("Compte approuvé ✅")
                .body(String.format("Votre compte %s (%s) a été approuvé. Vous pouvez maintenant effectuer des opérations.",
                        account.getAccountType(), account.getAccountNumber())).build());

        auditService.log(AuditLog.AuditAction.ACCOUNT_APPROVED, agentEmail,
                "Account", account.getId().toString(),
                "Account " + account.getAccountNumber() + " approved");

        log.info("Account {} approved by {}", account.getAccountNumber(), agentEmail);
        return toAccountResponse(account);
    }

    @Transactional
    public AccountResponse rejectAccount(UUID accountId, String agentEmail, String reason) {
        User agent = userRepository.findByEmail(agentEmail)
                .orElseThrow(() -> new BankingException.NotFoundException("Agent not found"));
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BankingException.NotFoundException("Compte introuvable"));
        validateAgentAuthority(agent, account);
        if (account.getStatus() != Account.AccountStatus.PENDING_APPROVAL) {
            throw new BankingException("Ce compte n'est pas en attente d'approbation");
        }

        account.setStatus(Account.AccountStatus.CLOSED);
        account.setIsActive(false);
        accountRepository.save(account);

        String body = String.format("Votre demande d'ouverture de compte %s a été refusée.", account.getAccountType());
        if (reason != null && !reason.isBlank()) body += " Motif: " + reason;

        notificationRepository.save(Notification.builder()
                .user(account.getUser()).type(Notification.NotificationType.ACCOUNT)
                .title("Demande de compte refusée ❌").body(body).build());

        auditService.log(AuditLog.AuditAction.ACCOUNT_REJECTED, agentEmail,
                "Account", account.getId().toString(),
                "Account " + account.getAccountNumber() + " rejected. Reason: " + reason);

        log.info("Account {} rejected by {}: {}", account.getAccountNumber(), agentEmail, reason);
        return toAccountResponse(account);
    }

    // ============================================================
    // Suspend / Unsuspend Account (fix #14)
    // ============================================================
    @Transactional
    public AccountResponse suspendAccount(UUID accountId, String agentEmail, String reason) {
        User agent = userRepository.findByEmail(agentEmail)
                .orElseThrow(() -> new BankingException.NotFoundException("Agent not found"));
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BankingException.NotFoundException("Compte introuvable"));
        validateAgentAuthority(agent, account);

        if (account.getStatus() != Account.AccountStatus.ACTIVE) {
            throw new BankingException("Seuls les comptes ACTIVE peuvent être suspendus");
        }

        account.setStatus(Account.AccountStatus.SUSPENDED);
        account.setIsActive(false);
        accountRepository.save(account);

        String body = "Votre compte " + account.getAccountNumber() + " a été suspendu.";
        if (reason != null && !reason.isBlank()) body += " Motif: " + reason;

        notificationRepository.save(Notification.builder()
                .user(account.getUser()).type(Notification.NotificationType.SECURITY)
                .title("Compte suspendu ⚠️").body(body).build());

        auditService.log(AuditLog.AuditAction.ACCOUNT_SUSPENDED, agentEmail,
                "Account", account.getId().toString(), "Suspended. Reason: " + reason);

        return toAccountResponse(account);
    }

    @Transactional
    public AccountResponse unsuspendAccount(UUID accountId, String agentEmail) {
        User agent = userRepository.findByEmail(agentEmail)
                .orElseThrow(() -> new BankingException.NotFoundException("Agent not found"));
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BankingException.NotFoundException("Compte introuvable"));
        validateAgentAuthority(agent, account);

        if (account.getStatus() != Account.AccountStatus.SUSPENDED) {
            throw new BankingException("Ce compte n'est pas suspendu");
        }

        account.setStatus(Account.AccountStatus.ACTIVE);
        account.setIsActive(true);
        accountRepository.save(account);

        notificationRepository.save(Notification.builder()
                .user(account.getUser()).type(Notification.NotificationType.ACCOUNT)
                .title("Compte réactivé ✅")
                .body("Votre compte " + account.getAccountNumber() + " a été réactivé.").build());

        auditService.log(AuditLog.AuditAction.ACCOUNT_UNSUSPENDED, agentEmail,
                "Account", account.getId().toString(), "Unsuspended");

        return toAccountResponse(account);
    }

    // ============================================================
    // Helpers
    // ============================================================
    private void validateAgentAuthority(User agent, Account account) {
        if (agent.getUserType() == User.UserType.ADMIN) return;
        if (agent.getUserType() != User.UserType.AGENT) {
            throw new BankingException.ForbiddenException("Seuls les agents et admins peuvent gérer les comptes");
        }
        if (agent.getAgency() == null || account.getUser().getAgency() == null) {
            throw new BankingException.ForbiddenException("Agent ou client non affecté à une agence");
        }
        if (!agent.getAgency().getId().equals(account.getUser().getAgency().getId())) {
            throw new BankingException.ForbiddenException("Vous ne pouvez gérer que les comptes de votre agence");
        }
    }


    private TransactionResponse toTransactionResponse(Transaction tx) {
        return TransactionResponse.builder()
                .id(tx.getId()).type(tx.getType()).amount(tx.getAmount())
                .balanceAfter(tx.getBalanceAfter()).description(tx.getDescription())
                .category(tx.getCategory()).createdAt(tx.getCreatedAt()).build();
    }

    private AccountResponse toAccountResponse(Account account) {
        return AccountResponse.builder()
                .id(account.getId())
                .accountNumber(account.getAccountNumber())
                .accountType(account.getAccountType())
                .status(account.getStatus())
                .balance(account.getBalance())
                .currency(account.getCurrency())
                .isActive(account.getIsActive())
                .ownerName(account.getUser().getFullNameFr())
                .ownerCin(account.getUser().getCin())
                .agencyName(account.getUser().getAgency() != null
                        ? account.getUser().getAgency().getBranchName() : null)
                .createdAt(account.getCreatedAt())
                .build();
    }
}
