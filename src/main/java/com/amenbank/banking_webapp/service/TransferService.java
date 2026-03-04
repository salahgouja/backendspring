package com.amenbank.banking_webapp.service;

import com.amenbank.banking_webapp.dto.request.TransferRequest;
import com.amenbank.banking_webapp.dto.response.TransferResponse;
import com.amenbank.banking_webapp.exception.BankingException;
import com.amenbank.banking_webapp.exception.BankingException.*;
import com.amenbank.banking_webapp.model.*;
import com.amenbank.banking_webapp.repository.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransferService {

    private static final String BEAN_NAME = "TransferService";
    private static final String USER_NOT_FOUND = "Utilisateur introuvable";

    private final TransferRepository transferRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final FraudService fraudService;

    @Value("${app.transfer.max-amount-per-transaction:100000.000}")
    private BigDecimal maxAmountPerTransaction;

    @Value("${app.transfer.max-daily-amount:500000.000}")
    private BigDecimal maxDailyAmount;

    @Value("${app.transfer.min-amount:0.001}")
    private BigDecimal minAmount;

    @PostConstruct
    public void init() {
        log.info("=== {}: PostConstruct - Transfer Service initialized ===", BEAN_NAME);
        log.info("{}: Max per tx: {} TND, Max daily: {} TND", BEAN_NAME, maxAmountPerTransaction, maxDailyAmount);
    }

    @PreDestroy
    public void destroy() {
        log.info("=== {}: PreDestroy - Transfer Service cleanup ===", BEAN_NAME);
    }

    // ============================================================
    // Execute Transfer
    // ============================================================
    @Transactional
    public TransferResponse createTransfer(String userEmail, TransferRequest request) {
        log.info("{}: Creating transfer from user: {}", BEAN_NAME, userEmail);

        // 1. Validate transfer amount
        validateTransferAmount(request.getAmount());

        // 2. Load sender user
        User sender = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new NotFoundException(USER_NOT_FOUND));

        // 3. Load and validate sender account with pessimistic lock
        Account senderAccount = accountRepository.findByIdForUpdate(request.getSenderAccountId())
                .orElseThrow(() -> new NotFoundException("Compte émetteur introuvable"));

        // 4. Verify account ownership
        verifyAccountOwnership(senderAccount, sender);

        // 5. Verify account is active AND not suspended (fix #14)
        verifyAccountOperational(senderAccount, "émetteur");

        // 6. Load receiver account
        Account receiverAccount = accountRepository.findByAccountNumberForUpdate(
                request.getReceiverAccountNumber())
                .orElseThrow(() -> new NotFoundException(
                        "Compte destinataire introuvable: " + request.getReceiverAccountNumber()));

        // 7. Verify receiver is operational (fix #14)
        verifyAccountOperational(receiverAccount, "destinataire");

        // 8. Verify not same account
        verifyNotSameAccount(senderAccount, receiverAccount);

        // 9. Verify same currency (fix #22)
        verifySameCurrency(senderAccount, receiverAccount);

        // 10. Check sufficient balance
        verifySufficientBalance(senderAccount, request.getAmount());

        // 11. Enforce daily transfer limit (fix #8)
        verifyDailyLimit(sender.getId(), request.getAmount());

        // 12. Execute transfer with balance updates
        BigDecimal amount = request.getAmount();
        senderAccount.setBalance(senderAccount.getBalance().subtract(amount));
        accountRepository.save(senderAccount);
        receiverAccount.setBalance(receiverAccount.getBalance().add(amount));
        accountRepository.save(receiverAccount);

        // 13. Create transfer record
        Transfer transfer = createTransferRecord(sender, senderAccount, receiverAccount, amount, request);

        // 14. Create transactions
        createTransactions(senderAccount, receiverAccount, transfer, amount);

        // 15. Create notifications
        createNotifications(sender, receiverAccount, transfer, amount);

        // 16. Audit log (fix #24)
        auditService.log(AuditLog.AuditAction.TRANSFER_CREATED, userEmail,
                "Transfer", transfer.getId().toString(),
                String.format("%.3f %s from %s to %s", amount, senderAccount.getCurrency(),
                        senderAccount.getAccountNumber(), receiverAccount.getAccountNumber()));

        // 17. Fraud analysis (fix #10)
        fraudService.analyzeTransfer(transfer, sender);

        log.info("{}: Transfer completed: {} -> {} : {} TND",
                BEAN_NAME, senderAccount.getAccountNumber(),
                receiverAccount.getAccountNumber(), amount);

        return toResponse(transfer);
    }

    // ============================================================
    // Get User Transfers — Sent (paginated, fix #26)
    // ============================================================
    @Transactional(readOnly = true)
    public Page<TransferResponse> getUserTransfersPaged(String userEmail, int page, int size) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new NotFoundException(USER_NOT_FOUND));
        return transferRepository.findBySenderUserIdOrderByCreatedAtDesc(
                user.getId(), PageRequest.of(page, size)).map(this::toResponse);
    }

    // ============================================================
    // Get Received Transfers (fix #13)
    // ============================================================
    @Transactional(readOnly = true)
    public Page<TransferResponse> getReceivedTransfers(String userEmail, int page, int size) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new NotFoundException(USER_NOT_FOUND));
        return transferRepository.findReceivedByUserId(
                user.getId(), PageRequest.of(page, size)).map(this::toResponse);
    }

    // ============================================================
    // Get All Transfers — Sent + Received (fix #13)
    // ============================================================
    @Transactional(readOnly = true)
    public Page<TransferResponse> getAllUserTransfers(String userEmail, int page, int size) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new NotFoundException(USER_NOT_FOUND));
        return transferRepository.findAllByUserId(
                user.getId(), PageRequest.of(page, size)).map(this::toResponse);
    }

    // ============================================================
    // Legacy — unpaged sent transfers (backward compat)
    // ============================================================
    @Transactional(readOnly = true)
    public List<TransferResponse> getUserTransfers(String userEmail) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new NotFoundException(USER_NOT_FOUND));
        return transferRepository.findBySenderUserIdOrderByCreatedAtDesc(user.getId())
                .stream().map(this::toResponse).toList();
    }

    // ============================================================
    // Get Transfer By ID (fix #31 — added @Transactional)
    // ============================================================
    @Transactional(readOnly = true)
    public TransferResponse getTransferById(String userEmail, UUID transferId) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new NotFoundException(USER_NOT_FOUND));

        Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new NotFoundException("Virement introuvable"));

        // Allow both sender and receiver to view (fix #13)
        boolean isSender = transfer.getSenderUser().getId().equals(user.getId());
        boolean isReceiver = transfer.getReceiverAccount().getUser().getId().equals(user.getId());
        if (!isSender && !isReceiver) {
            throw new ForbiddenException("Ce virement ne vous appartient pas");
        }

        return toResponse(transfer);
    }

    // ============================================================
    // Validation Methods
    // ============================================================
    private void validateTransferAmount(BigDecimal amount) {
        if (amount == null) throw new BankingException("Le montant est obligatoire");
        if (amount.compareTo(minAmount) < 0) {
            throw new BankingException(String.format("Le montant minimum est %s TND", minAmount));
        }
        if (amount.compareTo(maxAmountPerTransaction) > 0) {
            throw new BankingException(String.format("Le montant maximum par virement est %s TND", maxAmountPerTransaction));
        }
    }

    private void verifyAccountOwnership(Account account, User user) {
        if (!account.getUser().getId().equals(user.getId())) {
            throw new ForbiddenException("Ce compte ne vous appartient pas");
        }
    }

    /** Fix #14: Check both isActive AND status (not SUSPENDED/CLOSED) */
    private void verifyAccountOperational(Account account, String type) {
        if (Boolean.FALSE.equals(account.getIsActive())) {
            throw new BankingException(String.format("Le compte %s est désactivé", type));
        }
        if (account.getStatus() == Account.AccountStatus.SUSPENDED) {
            throw new BankingException(String.format("Le compte %s est suspendu", type));
        }
        if (account.getStatus() == Account.AccountStatus.CLOSED) {
            throw new BankingException(String.format("Le compte %s est fermé", type));
        }
        if (account.getStatus() == Account.AccountStatus.PENDING_APPROVAL) {
            throw new BankingException(String.format("Le compte %s est en attente d'approbation", type));
        }
    }

    private void verifyNotSameAccount(Account sender, Account receiver) {
        if (sender.getId().equals(receiver.getId())) {
            throw new BankingException("Impossible de transférer vers le même compte");
        }
    }

    /** Fix #22: Verify sender and receiver have the same currency */
    private void verifySameCurrency(Account sender, Account receiver) {
        if (!sender.getCurrency().equals(receiver.getCurrency())) {
            throw new BankingException(String.format(
                    "Devises incompatibles: compte émetteur en %s, compte destinataire en %s. " +
                    "Les virements inter-devises ne sont pas encore supportés.",
                    sender.getCurrency(), receiver.getCurrency()));
        }
    }

    private void verifySufficientBalance(Account account, BigDecimal amount) {
        if (account.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException(String.format(
                    "Solde insuffisant. Solde actuel: %.3f TND, montant demandé: %.3f TND",
                    account.getBalance(), amount));
        }
    }

    /** Fix #8: Enforce daily transfer limit */
    private void verifyDailyLimit(UUID userId, BigDecimal newAmount) {
        LocalDateTime startOfDay = LocalDate.now().atTime(LocalTime.MIN);
        BigDecimal dailyTotal = transferRepository.sumDailyTransfersByUser(userId, startOfDay);
        BigDecimal projectedTotal = dailyTotal.add(newAmount);
        if (projectedTotal.compareTo(maxDailyAmount) > 0) {
            throw new BankingException(String.format(
                    "Limite journalière dépassée. Déjà transféré: %.3f TND, demandé: %.3f TND, limite: %.3f TND",
                    dailyTotal, newAmount, maxDailyAmount));
        }
    }

    // ============================================================
    // Private Helpers
    // ============================================================
    private Transfer createTransferRecord(User sender, Account senderAccount,
            Account receiverAccount, BigDecimal amount, TransferRequest request) {
        Transfer transfer = Transfer.builder()
                .senderUser(sender)
                .senderAccount(senderAccount)
                .receiverAccount(receiverAccount)
                .receiverName(receiverAccount.getUser().getFullNameFr())
                .amount(amount)
                .motif(sanitizeMotif(request.getMotif()))
                .status(Transfer.TransferStatus.EXECUTED)
                .executedAt(LocalDateTime.now())
                .build();
        return transferRepository.save(transfer);
    }

    private void createTransactions(Account senderAccount, Account receiverAccount,
            Transfer transfer, BigDecimal amount) {
        transactionRepository.save(Transaction.builder()
                .account(senderAccount).transfer(transfer)
                .type(Transaction.TransactionType.DEBIT).amount(amount)
                .balanceAfter(senderAccount.getBalance())
                .description("Virement vers " + receiverAccount.getAccountNumber())
                .category("virement").build());

        transactionRepository.save(Transaction.builder()
                .account(receiverAccount).transfer(transfer)
                .type(Transaction.TransactionType.CREDIT).amount(amount)
                .balanceAfter(receiverAccount.getBalance())
                .description("Virement reçu de " + senderAccount.getAccountNumber())
                .category("virement").build());
    }

    private void createNotifications(User sender, Account receiverAccount,
            Transfer transfer, BigDecimal amount) {
        notificationRepository.save(Notification.builder()
                .user(sender).type(Notification.NotificationType.TRANSFER)
                .title("Virement effectué")
                .body(String.format("Virement de %.3f TND vers %s (%s) effectué avec succès.",
                        amount, receiverAccount.getUser().getFullNameFr(),
                        receiverAccount.getAccountNumber())).build());

        notificationRepository.save(Notification.builder()
                .user(receiverAccount.getUser()).type(Notification.NotificationType.TRANSFER)
                .title("Virement reçu")
                .body(String.format("Vous avez reçu un virement de %.3f TND de %s.",
                        amount, sender.getFullNameFr())).build());
    }

    private String sanitizeMotif(String motif) {
        if (motif == null) return null;
        return motif.replaceAll("[<>\"'&]", "");
    }

    private TransferResponse toResponse(Transfer t) {
        return TransferResponse.builder()
                .id(t.getId())
                .senderAccountId(t.getSenderAccount().getId())
                .senderAccountNumber(t.getSenderAccount().getAccountNumber())
                .receiverAccountId(t.getReceiverAccount().getId())
                .receiverAccountNumber(t.getReceiverAccount().getAccountNumber())
                .receiverName(t.getReceiverName())
                .amount(t.getAmount())
                .currency(t.getCurrency())
                .motif(t.getMotif())
                .status(t.getStatus().name())
                .executedAt(t.getExecutedAt())
                .createdAt(t.getCreatedAt())
                .build();
    }
}
