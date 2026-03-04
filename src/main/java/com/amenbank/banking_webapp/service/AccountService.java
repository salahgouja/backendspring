package com.amenbank.banking_webapp.service;

import com.amenbank.banking_webapp.dto.response.AccountResponse;
import com.amenbank.banking_webapp.dto.response.TransactionResponse;
import com.amenbank.banking_webapp.exception.BankingException;
import com.amenbank.banking_webapp.model.Account;
import com.amenbank.banking_webapp.model.Notification;
import com.amenbank.banking_webapp.model.User;
import com.amenbank.banking_webapp.repository.AccountRepository;
import com.amenbank.banking_webapp.repository.NotificationRepository;
import com.amenbank.banking_webapp.repository.TransactionRepository;
import com.amenbank.banking_webapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Transactional(readOnly = true)
    public List<AccountResponse> getUserAccounts(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BankingException.NotFoundException("User not found"));

        return accountRepository.findByUserId(user.getId())
                .stream()
                .map(this::toAccountResponse)
                .collect(Collectors.toList());
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
                .stream()
                .map(tx -> TransactionResponse.builder()
                        .id(tx.getId())
                        .type(tx.getType())
                        .amount(tx.getAmount())
                        .balanceAfter(tx.getBalanceAfter())
                        .description(tx.getDescription())
                        .category(tx.getCategory())
                        .createdAt(tx.getCreatedAt())
                        .build())
                .collect(Collectors.toList());
    }

    // ============================================================
    // Account Approval Workflow
    // ============================================================

    /**
     * Get pending accounts for the agent's agency
     */
    public List<AccountResponse> getPendingAccounts(String agentEmail) {
        User agent = userRepository.findByEmail(agentEmail)
                .orElseThrow(() -> new BankingException.NotFoundException("Agent not found"));

        if (agent.getUserType() == User.UserType.ADMIN) {
            // Admins see all pending accounts
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

    /**
     * Approve an account — agent action
     */
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

        // Notify the account owner
        Notification notification = Notification.builder()
                .user(account.getUser())
                .type(Notification.NotificationType.SECURITY)
                .title("Compte approuvé ✅")
                .body(String.format(
                        "Votre compte %s (%s) a été approuvé par l'agence. Vous pouvez maintenant effectuer des opérations.",
                        account.getAccountType(), account.getAccountNumber()))
                .build();
        notificationRepository.save(notification);

        log.info("Account {} approved by agent {}", account.getAccountNumber(), agentEmail);
        return toAccountResponse(account);
    }

    /**
     * Reject an account — agent action
     */
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

        // Notify the account owner
        String body = String.format("Votre demande d'ouverture de compte %s a été refusée.", account.getAccountType());
        if (reason != null && !reason.isBlank()) {
            body += " Motif: " + reason;
        }

        Notification notification = Notification.builder()
                .user(account.getUser())
                .type(Notification.NotificationType.SECURITY)
                .title("Demande de compte refusée ❌")
                .body(body)
                .build();
        notificationRepository.save(notification);

        log.info("Account {} rejected by agent {}: {}", account.getAccountNumber(), agentEmail, reason);
        return toAccountResponse(account);
    }

    // ============================================================
    // Helpers
    // ============================================================

    private void validateAgentAuthority(User agent, Account account) {
        if (agent.getUserType() == User.UserType.ADMIN) {
            return; // Admin can approve from any agency
        }

        if (agent.getUserType() != User.UserType.AGENT) {
            throw new BankingException.ForbiddenException("Seuls les agents et admins peuvent approuver les comptes");
        }

        if (agent.getAgency() == null || account.getUser().getAgency() == null) {
            throw new BankingException.ForbiddenException("Agent ou client non affecté à une agence");
        }

        if (!agent.getAgency().getId().equals(account.getUser().getAgency().getId())) {
            throw new BankingException.ForbiddenException("Vous ne pouvez approuver que les comptes de votre agence");
        }
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
                        ? account.getUser().getAgency().getBranchName()
                        : null)
                .createdAt(account.getCreatedAt())
                .build();
    }
}
