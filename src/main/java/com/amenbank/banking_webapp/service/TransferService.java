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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Optimized Transfer Service with:
 * - Bean Life Cycle management
 * - Transfer limits and validation
 * - Optimistic locking for concurrency
 * - Enhanced security checks
 * - Performance optimizations
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransferService {

    private static final String BEAN_NAME = "TransferService";
    private static final String USER_NOT_FOUND = "Utilisateur introuvable";

    // Dependencies - constructor injection
    private final TransferRepository transferRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;

    // Configuration
    @Value("${app.transfer.max-amount-per-transaction:100000.000}")
    private BigDecimal maxAmountPerTransaction;

    @Value("${app.transfer.max-daily-amount:500000.000}")
    private BigDecimal maxDailyAmount;

    @Value("${app.transfer.min-amount:0.001}")
    private BigDecimal minAmount;

    // ============================================================
    // Bean Life Cycle - @PostConstruct
    // ============================================================
    @PostConstruct
    public void init() {
        log.info("=== {}: PostConstruct - Transfer Service initialized ===", BEAN_NAME);
        log.info("{}: Max amount per transaction: {} TND", BEAN_NAME, maxAmountPerTransaction);
        log.info("{}: Max daily amount: {} TND", BEAN_NAME, maxDailyAmount);
    }

    // ============================================================
    // Bean Life Cycle - @PreDestroy
    // ============================================================
    @PreDestroy
    public void destroy() {
        log.info("=== {}: PreDestroy - Transfer Service cleanup ===", BEAN_NAME);
    }

    // ============================================================
    // Execute Transfer with Optimizations
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

        // 5. Verify account is active
        verifyAccountActive(senderAccount, "émetteur");

        // 6. Load receiver account
        Account receiverAccount = accountRepository.findByAccountNumberForUpdate(
                request.getReceiverAccountNumber())
                .orElseThrow(() -> new NotFoundException(
                        "Compte destinataire introuvable: " + request.getReceiverAccountNumber()));

        // 7. Verify receiver is active
        verifyAccountActive(receiverAccount, "destinataire");

        // 8. Verify not same account
        verifyNotSameAccount(senderAccount, receiverAccount);

        // 9. Check sufficient balance
        verifySufficientBalance(senderAccount, request.getAmount());

        // 10. Execute transfer with balance updates
        BigDecimal amount = request.getAmount();

        // Debit sender
        senderAccount.setBalance(senderAccount.getBalance().subtract(amount));
        accountRepository.save(senderAccount);

        // Credit receiver
        receiverAccount.setBalance(receiverAccount.getBalance().add(amount));
        accountRepository.save(receiverAccount);

        // 11. Create transfer record
        Transfer transfer = createTransferRecord(sender, senderAccount, receiverAccount, amount, request);

        // 12. Create transactions
        createTransactions(senderAccount, receiverAccount, transfer, amount);

        // 13. Create notifications
        createNotifications(sender, receiverAccount, transfer, amount);

        log.info("{}: Transfer completed successfully: {} -> {} : {} TND",
                BEAN_NAME, senderAccount.getAccountNumber(),
                receiverAccount.getAccountNumber(), amount);

        return toResponse(transfer);
    }

    // ============================================================
    // Get User Transfers
    // ============================================================
    @Transactional(readOnly = true)
    public List<TransferResponse> getUserTransfers(String userEmail) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new NotFoundException(USER_NOT_FOUND));

        return transferRepository.findBySenderUserIdOrderByCreatedAtDesc(user.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    // ============================================================
    // Get Transfer By ID
    // ============================================================
    public TransferResponse getTransferById(String userEmail, UUID transferId) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new NotFoundException(USER_NOT_FOUND));

        Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new NotFoundException("Virement introuvable"));

        // Verify ownership
        if (!transfer.getSenderUser().getId().equals(user.getId())) {
            throw new ForbiddenException("Ce virement ne vous appartient pas");
        }

        return toResponse(transfer);
    }

    // ============================================================
    // Validation Methods
    // ============================================================
    private void validateTransferAmount(BigDecimal amount) {
        if (amount == null) {
            throw new BankingException("Le montant est obligatoire");
        }

        if (amount.compareTo(minAmount) < 0) {
            throw new BankingException(String.format(
                    "Le montant minimum est %s TND", minAmount));
        }

        if (amount.compareTo(maxAmountPerTransaction) > 0) {
            throw new BankingException(String.format(
                    "Le montant maximum par virement est %s TND",
                    maxAmountPerTransaction));
        }
    }

    private void verifyAccountOwnership(Account account, User user) {
        if (!account.getUser().getId().equals(user.getId())) {
            throw new ForbiddenException("Ce compte ne vous appartient pas");
        }
    }

    private void verifyAccountActive(Account account, String type) {
        if (Boolean.FALSE.equals(account.getIsActive())) {
            throw new BankingException(String.format(
                    "Le compte %s est désactivé", type));
        }
    }

    private void verifyNotSameAccount(Account sender, Account receiver) {
        if (sender.getId().equals(receiver.getId())) {
            throw new BankingException("Impossible de transférer vers le même compte");
        }
    }

    private void verifySufficientBalance(Account account, BigDecimal amount) {
        if (account.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException(String.format(
                    "Solde insuffisant. Solde actuel: %.3f TND, montant demandé: %.3f TND",
                    account.getBalance(), amount));
        }
    }

    // ============================================================
    // Private Helper Methods
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

        // Debit transaction for sender
        Transaction debitTx = Transaction.builder()
                .account(senderAccount)
                .transfer(transfer)
                .type(Transaction.TransactionType.DEBIT)
                .amount(amount)
                .balanceAfter(senderAccount.getBalance())
                .description("Virement vers " + receiverAccount.getAccountNumber())
                .category("virement")
                .build();
        transactionRepository.save(debitTx);

        // Credit transaction for receiver
        Transaction creditTx = Transaction.builder()
                .account(receiverAccount)
                .transfer(transfer)
                .type(Transaction.TransactionType.CREDIT)
                .amount(amount)
                .balanceAfter(receiverAccount.getBalance())
                .description("Virement reçu de " + senderAccount.getAccountNumber())
                .category("virement")
                .build();
        transactionRepository.save(creditTx);
    }

    private void createNotifications(User sender, Account receiverAccount,
            Transfer transfer, BigDecimal amount) {

        // Notify sender
        notificationRepository.save(Notification.builder()
                .user(sender)
                .type(Notification.NotificationType.TRANSFER)
                .title("Virement effectué")
                .body(String.format("Virement de %.3f TND vers %s (%s) effectué avec succès.",
                        amount, receiverAccount.getUser().getFullNameFr(),
                        receiverAccount.getAccountNumber()))
                .build());

        // Notify receiver
        notificationRepository.save(Notification.builder()
                .user(receiverAccount.getUser())
                .type(Notification.NotificationType.TRANSFER)
                .title("Virement reçu")
                .body(String.format("Vous avez reçu un virement de %.3f TND de %s.",
                        amount, sender.getFullNameFr()))
                .build());
    }

    /**
     * Sanitize motif input to prevent XSS
     */
    private String sanitizeMotif(String motif) {
        if (motif == null) {
            return null;
        }
        // Remove potentially dangerous characters
        return motif.replaceAll("[<>\"'&]", "");
    }

    // ============================================================
    // Mapper
    // ============================================================
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
