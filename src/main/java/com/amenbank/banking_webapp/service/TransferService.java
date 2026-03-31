package com.amenbank.banking_webapp.service;

import com.amenbank.banking_webapp.dto.request.BatchTransferRequest;
import com.amenbank.banking_webapp.dto.request.ScheduledTransferRequest;
import com.amenbank.banking_webapp.dto.request.TransferRequest;
import com.amenbank.banking_webapp.dto.response.BatchTransferResponse;
import com.amenbank.banking_webapp.dto.response.TransferResponse;
import com.amenbank.banking_webapp.exception.BankingException;
import com.amenbank.banking_webapp.exception.BankingException.*;
import com.amenbank.banking_webapp.model.*;
import com.amenbank.banking_webapp.repository.*;
import dev.samstevens.totp.code.*;
import dev.samstevens.totp.time.SystemTimeProvider;
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
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransferService {

    private static final String BEAN_NAME = "TransferService";
    private static final String USER_NOT_FOUND = "Utilisateur introuvable";
    private static final AtomicLong REFERENCE_SEQ = new AtomicLong(System.currentTimeMillis() % 100_000);

    private final TransferRepository transferRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final BatchTransferRepository batchTransferRepository;
    private final BatchTransferItemRepository batchTransferItemRepository;
    private final AuditService auditService;
    private final EmailService emailService;

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
    // Self-Transfer (between own accounts — no 2FA, no daily limit)
    // ============================================================
    @Transactional
    public TransferResponse createSelfTransfer(String userEmail, TransferRequest request) {
        log.info("{}: Creating self-transfer for user: {}", BEAN_NAME, userEmail);

        validateTransferAmount(request.getAmount());

        User sender = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new NotFoundException(USER_NOT_FOUND));

        // Load both accounts with locks
        Account senderAccount = accountRepository.findByIdForUpdate(request.getSenderAccountId())
                .orElseThrow(() -> new NotFoundException("Compte émetteur introuvable"));
        verifyAccountOwnership(senderAccount, sender);
        verifyAccountOperational(senderAccount, "émetteur");

        Account receiverAccount = accountRepository.findByAccountNumberForUpdate(request.getReceiverAccountNumber())
                .orElseThrow(() -> new NotFoundException("Compte destinataire introuvable: " + request.getReceiverAccountNumber()));

        // Verify receiver is ALSO owned by the same user
        if (!receiverAccount.getUser().getId().equals(sender.getId())) {
            throw new BankingException("Le virement interne n'est possible qu'entre vos propres comptes. " +
                    "Utilisez le virement normal pour envoyer à un autre utilisateur.");
        }

        verifyAccountOperational(receiverAccount, "destinataire");
        verifyNotSameAccount(senderAccount, receiverAccount);
        verifySameCurrency(senderAccount, receiverAccount);
        verifySufficientBalance(senderAccount, request.getAmount());

        // No 2FA check — it's the same user
        // No daily limit check — internal movement

        BigDecimal amount = request.getAmount();
        senderAccount.setBalance(senderAccount.getBalance().subtract(amount));
        accountRepository.save(senderAccount);
        receiverAccount.setBalance(receiverAccount.getBalance().add(amount));
        accountRepository.save(receiverAccount);

        Transfer transfer = Transfer.builder()
                .senderUser(sender)
                .senderAccount(senderAccount)
                .receiverAccount(receiverAccount)
                .receiverName(sender.getFullNameFr() + " (virement interne)")
                .amount(amount)
                .motif(sanitizeMotif(request.getMotif() != null ? request.getMotif() : "Virement interne"))
                .referenceNumber(generateReferenceNumber())
                .status(Transfer.TransferStatus.EXECUTED)
                .executedAt(LocalDateTime.now())
                .build();
        transferRepository.save(transfer);

        // Create debit & credit transactions
        transactionRepository.save(Transaction.builder()
                .account(senderAccount).transfer(transfer)
                .type(Transaction.TransactionType.DEBIT).amount(amount)
                .balanceAfter(senderAccount.getBalance())
                .description("Virement interne vers " + receiverAccount.getAccountNumber())
                .category("virement_interne").build());

        transactionRepository.save(Transaction.builder()
                .account(receiverAccount).transfer(transfer)
                .type(Transaction.TransactionType.CREDIT).amount(amount)
                .balanceAfter(receiverAccount.getBalance())
                .description("Virement interne de " + senderAccount.getAccountNumber())
                .category("virement_interne").build());

        notificationRepository.save(Notification.builder()
                .user(sender).type(Notification.NotificationType.TRANSFER)
                .title("Virement interne effectué")
                .body(String.format("%.3f TND transférés de %s vers %s.",
                        amount, senderAccount.getAccountNumber(), receiverAccount.getAccountNumber()))
                .build());

        auditService.log(AuditLog.AuditAction.TRANSFER_CREATED, userEmail,
                "Transfer", transfer.getId().toString(),
                String.format("Self-transfer: %.3f TND from %s to %s", amount,
                        senderAccount.getAccountNumber(), receiverAccount.getAccountNumber()));

        log.info("{}: Self-transfer completed: {} -> {} : {} TND",
                BEAN_NAME, senderAccount.getAccountNumber(),
                receiverAccount.getAccountNumber(), amount);

        return toResponse(transfer);
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

        // 2b. Verify 2FA if enabled (GAP-2: 2FA before transfer)
        verify2faForTransfer(sender, request.getTotpCode());

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

        // 17. GAP-23: Email confirmation
        emailService.sendTransferConfirmation(sender.getEmail(),
                senderAccount.getAccountNumber(), receiverAccount.getAccountNumber(),
                String.format("%.3f", amount), transfer.getReferenceNumber());

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
    // GAP-4: Create Scheduled (one-time future) Transfer
    // ============================================================
    @Transactional
    public TransferResponse createScheduledTransfer(String userEmail, ScheduledTransferRequest request) {
        log.info("Creating scheduled transfer for user: {}", userEmail);

        if (request.getScheduledAt() == null) {
            throw new BankingException("La date d'exécution est obligatoire pour un virement programmé");
        }
        if (request.getScheduledAt().isBefore(LocalDateTime.now().plusMinutes(5))) {
            throw new BankingException("La date d'exécution doit être dans le futur (minimum 5 minutes)");
        }

        validateTransferAmount(request.getAmount());

        User sender = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new NotFoundException(USER_NOT_FOUND));

        verify2faForTransfer(sender, request.getTotpCode());

        Account senderAccount = accountRepository.findById(request.getSenderAccountId())
                .orElseThrow(() -> new NotFoundException("Compte émetteur introuvable"));
        verifyAccountOwnership(senderAccount, sender);
        verifyAccountOperational(senderAccount, "émetteur");

        Account receiverAccount = accountRepository.findByAccountNumber(request.getReceiverAccountNumber())
                .orElseThrow(() -> new NotFoundException("Compte destinataire introuvable: " + request.getReceiverAccountNumber()));
        verifyAccountOperational(receiverAccount, "destinataire");
        verifyNotSameAccount(senderAccount, receiverAccount);
        verifySameCurrency(senderAccount, receiverAccount);

        Transfer transfer = Transfer.builder()
                .senderUser(sender)
                .senderAccount(senderAccount)
                .receiverAccount(receiverAccount)
                .receiverName(receiverAccount.getUser().getFullNameFr())
                .amount(request.getAmount())
                .motif(sanitizeMotif(request.getMotif()))
                .referenceNumber(generateReferenceNumber())
                .status(Transfer.TransferStatus.PENDING)
                .isRecurring(false)
                .scheduledAt(request.getScheduledAt())
                .build();
        transferRepository.save(transfer);

        notificationRepository.save(Notification.builder()
                .user(sender).type(Notification.NotificationType.TRANSFER)
                .title("Virement programmé créé")
                .body(String.format("Virement de %.3f TND vers %s programmé pour le %s.",
                        request.getAmount(), receiverAccount.getUser().getFullNameFr(),
                        request.getScheduledAt().toLocalDate()))
                .build());

        auditService.log(AuditLog.AuditAction.TRANSFER_CREATED, userEmail,
                "Transfer", transfer.getId().toString(),
                "Scheduled transfer for " + request.getScheduledAt());

        return toResponse(transfer);
    }

    // ============================================================
    // GAP-4: Create Recurring (permanent) Transfer
    // ============================================================
    @Transactional
    public TransferResponse createRecurringTransfer(String userEmail, ScheduledTransferRequest request) {
        log.info("Creating recurring transfer for user: {}", userEmail);

        if (request.getRecurrenceIntervalMonths() == null) {
            throw new BankingException("L'intervalle de récurrence est obligatoire pour un virement permanent");
        }

        validateTransferAmount(request.getAmount());

        User sender = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new NotFoundException(USER_NOT_FOUND));

        verify2faForTransfer(sender, request.getTotpCode());

        Account senderAccount = accountRepository.findById(request.getSenderAccountId())
                .orElseThrow(() -> new NotFoundException("Compte émetteur introuvable"));
        verifyAccountOwnership(senderAccount, sender);
        verifyAccountOperational(senderAccount, "émetteur");

        Account receiverAccount = accountRepository.findByAccountNumber(request.getReceiverAccountNumber())
                .orElseThrow(() -> new NotFoundException("Compte destinataire introuvable: " + request.getReceiverAccountNumber()));
        verifyAccountOperational(receiverAccount, "destinataire");
        verifyNotSameAccount(senderAccount, receiverAccount);
        verifySameCurrency(senderAccount, receiverAccount);

        // Calculate first execution date
        LocalDateTime firstExecution = request.getScheduledAt() != null
                ? request.getScheduledAt()
                : LocalDateTime.now().plusMonths(request.getRecurrenceIntervalMonths());

        Transfer transfer = Transfer.builder()
                .senderUser(sender)
                .senderAccount(senderAccount)
                .receiverAccount(receiverAccount)
                .receiverName(receiverAccount.getUser().getFullNameFr())
                .amount(request.getAmount())
                .motif(sanitizeMotif(request.getMotif()))
                .referenceNumber(generateReferenceNumber())
                .status(Transfer.TransferStatus.PENDING)
                .isRecurring(true)
                .recurrenceRule("MONTHLY_" + request.getRecurrenceIntervalMonths())
                .scheduledAt(firstExecution)
                .build();
        transferRepository.save(transfer);

        notificationRepository.save(Notification.builder()
                .user(sender).type(Notification.NotificationType.TRANSFER)
                .title("Virement permanent créé ✅")
                .body(String.format("Virement permanent de %.3f TND vers %s tous les %d mois. Prochaine exécution: %s.",
                        request.getAmount(), receiverAccount.getUser().getFullNameFr(),
                        request.getRecurrenceIntervalMonths(), firstExecution.toLocalDate()))
                .build());

        auditService.log(AuditLog.AuditAction.TRANSFER_CREATED, userEmail,
                "Transfer", transfer.getId().toString(),
                "Recurring transfer every " + request.getRecurrenceIntervalMonths() + " months");

        return toResponse(transfer);
    }

    // ============================================================
    // GAP-4: List scheduled/recurring transfers
    // ============================================================
    @Transactional(readOnly = true)
    public Page<TransferResponse> getScheduledTransfers(String userEmail, int page, int size) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new NotFoundException(USER_NOT_FOUND));
        return transferRepository.findScheduledByUserId(user.getId(), PageRequest.of(page, size))
                .map(this::toResponse);
    }

    // ============================================================
    // GAP-4: Cancel a scheduled/recurring transfer
    // ============================================================
    @Transactional
    public TransferResponse cancelScheduledTransfer(String userEmail, UUID transferId) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new NotFoundException(USER_NOT_FOUND));

        Transfer transfer = transferRepository.findById(transferId)
                .orElseThrow(() -> new NotFoundException("Virement introuvable"));

        if (!transfer.getSenderUser().getId().equals(user.getId())) {
            throw new ForbiddenException("Ce virement ne vous appartient pas");
        }

        if (transfer.getStatus() != Transfer.TransferStatus.PENDING) {
            throw new BankingException("Seuls les virements en attente peuvent être annulés (statut actuel: " + transfer.getStatus() + ")");
        }

        transfer.setStatus(Transfer.TransferStatus.CANCELLED);
        transferRepository.save(transfer);

        notificationRepository.save(Notification.builder()
                .user(user).type(Notification.NotificationType.TRANSFER)
                .title("Virement annulé")
                .body(String.format("Votre virement %s de %.3f TND vers %s a été annulé.",
                        transfer.getIsRecurring() ? "permanent" : "programmé",
                        transfer.getAmount(), transfer.getReceiverAccount().getAccountNumber()))
                .build());

        auditService.log(AuditLog.AuditAction.TRANSFER_FAILED, userEmail,
                "Transfer", transfer.getId().toString(), "Scheduled/recurring transfer cancelled");

        return toResponse(transfer);
    }

    // ============================================================
    // GAP-3: Batch (Grouped) Transfer
    // ============================================================
    @Transactional
    public BatchTransferResponse createBatchTransfer(String userEmail, BatchTransferRequest request) {
        log.info("Creating batch transfer with {} items for user: {}", request.getItems().size(), userEmail);

        User sender = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new NotFoundException(USER_NOT_FOUND));

        verify2faForTransfer(sender, request.getTotpCode());

        Account senderAccount = accountRepository.findByIdForUpdate(request.getSenderAccountId())
                .orElseThrow(() -> new NotFoundException("Compte émetteur introuvable"));
        verifyAccountOwnership(senderAccount, sender);
        verifyAccountOperational(senderAccount, "émetteur");

        // Calculate total amount
        BigDecimal totalAmount = request.getItems().stream()
                .map(BatchTransferRequest.BatchItem::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Verify total sufficient balance
        verifySufficientBalance(senderAccount, totalAmount);

        // Verify daily limit for total
        verifyDailyLimit(sender.getId(), totalAmount);

        // Create batch record
        BatchTransfer batch = BatchTransfer.builder()
                .user(sender)
                .senderAccount(senderAccount)
                .totalAmount(totalAmount)
                .totalCount(request.getItems().size())
                .successCount(0)
                .failedCount(0)
                .status(BatchTransfer.BatchStatus.PROCESSING)
                .build();
        batchTransferRepository.save(batch);

        int successCount = 0;
        int failedCount = 0;

        for (BatchTransferRequest.BatchItem item : request.getItems()) {
            BatchTransferItem batchItem = BatchTransferItem.builder()
                    .batchTransfer(batch)
                    .receiverAccountNumber(item.getReceiverAccountNumber())
                    .amount(item.getAmount())
                    .motif(sanitizeMotif(item.getMotif()))
                    .status(BatchTransferItem.ItemStatus.PENDING)
                    .build();

            try {
                // Validate each item
                validateTransferAmount(item.getAmount());

                Account receiverAccount = accountRepository.findByAccountNumberForUpdate(item.getReceiverAccountNumber())
                        .orElseThrow(() -> new NotFoundException("Compte destinataire introuvable: " + item.getReceiverAccountNumber()));
                verifyAccountOperational(receiverAccount, "destinataire");
                verifyNotSameAccount(senderAccount, receiverAccount);
                verifySameCurrency(senderAccount, receiverAccount);

                // Re-check balance before each item
                if (senderAccount.getBalance().compareTo(item.getAmount()) < 0) {
                    throw new InsufficientFundsException("Solde insuffisant pour ce sous-virement");
                }

                // Execute transfer
                senderAccount.setBalance(senderAccount.getBalance().subtract(item.getAmount()));
                accountRepository.save(senderAccount);
                receiverAccount.setBalance(receiverAccount.getBalance().add(item.getAmount()));
                accountRepository.save(receiverAccount);

                Transfer transfer = Transfer.builder()
                        .senderUser(sender)
                        .senderAccount(senderAccount)
                        .receiverAccount(receiverAccount)
                        .receiverName(receiverAccount.getUser().getFullNameFr())
                        .amount(item.getAmount())
                        .motif(sanitizeMotif(item.getMotif()))
                        .referenceNumber(generateReferenceNumber())
                        .status(Transfer.TransferStatus.EXECUTED)
                        .executedAt(LocalDateTime.now())
                        .build();
                transferRepository.save(transfer);

                createTransactions(senderAccount, receiverAccount, transfer, item.getAmount());

                batchItem.setStatus(BatchTransferItem.ItemStatus.EXECUTED);
                batchItem.setTransfer(transfer);
                successCount++;

            } catch (Exception e) {
                batchItem.setStatus(BatchTransferItem.ItemStatus.FAILED);
                batchItem.setErrorMessage(e.getMessage());
                failedCount++;
                log.warn("Batch item failed for receiver {}: {}", item.getReceiverAccountNumber(), e.getMessage());
            }

            batchTransferItemRepository.save(batchItem);
        }

        // Update batch status
        batch.setSuccessCount(successCount);
        batch.setFailedCount(failedCount);
        batch.setCompletedAt(LocalDateTime.now());

        if (failedCount == 0) {
            batch.setStatus(BatchTransfer.BatchStatus.COMPLETED);
        } else if (successCount == 0) {
            batch.setStatus(BatchTransfer.BatchStatus.FAILED);
        } else {
            batch.setStatus(BatchTransfer.BatchStatus.PARTIAL);
        }
        batchTransferRepository.save(batch);

        // Notification
        notificationRepository.save(Notification.builder()
                .user(sender).type(Notification.NotificationType.TRANSFER)
                .title("Virement groupé terminé")
                .body(String.format("Lot de %d virements: %d réussis, %d échoués. Total: %.3f TND.",
                        batch.getTotalCount(), successCount, failedCount, totalAmount))
                .build());

        auditService.log(AuditLog.AuditAction.TRANSFER_CREATED, userEmail,
                "BatchTransfer", batch.getId().toString(),
                String.format("Batch: %d items, %d success, %d failed", batch.getTotalCount(), successCount, failedCount));

        return toBatchResponse(batch);
    }

    // ============================================================
    // GAP-3: Get Batch Transfer Details
    // ============================================================
    @Transactional(readOnly = true)
    public BatchTransferResponse getBatchTransferById(String userEmail, UUID batchId) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new NotFoundException(USER_NOT_FOUND));

        BatchTransfer batch = batchTransferRepository.findById(batchId)
                .orElseThrow(() -> new NotFoundException("Lot de virements introuvable"));

        if (!batch.getUser().getId().equals(user.getId())) {
            throw new ForbiddenException("Ce lot de virements ne vous appartient pas");
        }

        return toBatchResponse(batch);
    }

    // ============================================================
    // GAP-3: List my batch transfers
    // ============================================================
    @Transactional(readOnly = true)
    public Page<BatchTransferResponse> getMyBatchTransfers(String userEmail, int page, int size) {
        User user = userRepository.findByEmail(userEmail)
                .orElseThrow(() -> new NotFoundException(USER_NOT_FOUND));
        return batchTransferRepository.findByUserIdOrderByCreatedAtDesc(user.getId(), PageRequest.of(page, size))
                .map(this::toBatchResponse);
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
                .referenceNumber(generateReferenceNumber())
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
        return org.springframework.web.util.HtmlUtils.htmlEscape(motif);
    }

    /** GAP-12: Generate a human-readable, unique reference number (PERF-4 fix: AtomicLong) */
    private String generateReferenceNumber() {
        String date = LocalDate.now().toString().replace("-", "");
        String seq = String.format("%06d", REFERENCE_SEQ.incrementAndGet() % 1_000_000);
        return "VIR-" + date + "-" + seq;
    }

    /** GAP-2: Verify 2FA TOTP code before executing a transfer */
    private void verify2faForTransfer(User user, String totpCode) {
        if (!Boolean.TRUE.equals(user.getIs2faEnabled())) {
            return; // 2FA not enabled — skip
        }
        if (totpCode == null || totpCode.isBlank()) {
            throw new BankingException("Code 2FA requis pour effectuer un virement (votre compte a le 2FA activé)");
        }
        if (user.getTotpSecret() == null) {
            throw new BankingException("Configuration 2FA incomplète. Veuillez reconfigurer le 2FA.");
        }
        CodeVerifier verifier = new DefaultCodeVerifier(
                new DefaultCodeGenerator(), new SystemTimeProvider());
        if (!verifier.isValidCode(user.getTotpSecret(), totpCode)) {
            throw new BankingException("Code 2FA invalide. Virement refusé.");
        }
    }

    private TransferResponse toResponse(Transfer t) {
        // Parse recurrence interval from recurrenceRule if present
        Integer recurrenceMonths = null;
        if (t.getRecurrenceRule() != null && t.getRecurrenceRule().startsWith("MONTHLY_")) {
            try {
                recurrenceMonths = Integer.parseInt(t.getRecurrenceRule().replace("MONTHLY_", ""));
            } catch (NumberFormatException ignored) {}
        }

        return TransferResponse.builder()
                .id(t.getId())
                .referenceNumber(t.getReferenceNumber())
                .senderAccountId(t.getSenderAccount().getId())
                .senderAccountNumber(t.getSenderAccount().getAccountNumber())
                .receiverAccountId(t.getReceiverAccount().getId())
                .receiverAccountNumber(t.getReceiverAccount().getAccountNumber())
                .receiverName(t.getReceiverName())
                .amount(t.getAmount())
                .currency(t.getCurrency())
                .motif(t.getMotif())
                .status(t.getStatus().name())
                .isRecurring(t.getIsRecurring())
                .recurrenceIntervalMonths(recurrenceMonths)
                .scheduledAt(t.getScheduledAt())
                .executedAt(t.getExecutedAt())
                .createdAt(t.getCreatedAt())
                .build();
    }

    private BatchTransferResponse toBatchResponse(BatchTransfer batch) {
        List<BatchTransferResponse.BatchItemResponse> itemResponses =
                batchTransferItemRepository.findByBatchTransferId(batch.getId()).stream()
                        .map(item -> BatchTransferResponse.BatchItemResponse.builder()
                                .id(item.getId())
                                .receiverAccountNumber(item.getReceiverAccountNumber())
                                .amount(item.getAmount())
                                .motif(item.getMotif())
                                .status(item.getStatus().name())
                                .errorMessage(item.getErrorMessage())
                                .transferId(item.getTransfer() != null ? item.getTransfer().getId() : null)
                                .build())
                        .toList();

        return BatchTransferResponse.builder()
                .id(batch.getId())
                .senderAccountId(batch.getSenderAccount().getId())
                .senderAccountNumber(batch.getSenderAccount().getAccountNumber())
                .totalAmount(batch.getTotalAmount())
                .totalCount(batch.getTotalCount())
                .successCount(batch.getSuccessCount())
                .failedCount(batch.getFailedCount())
                .status(batch.getStatus().name())
                .createdAt(batch.getCreatedAt())
                .completedAt(batch.getCompletedAt())
                .items(itemResponses)
                .build();
    }
}
