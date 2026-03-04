package com.amenbank.banking_webapp.service;

import com.amenbank.banking_webapp.model.*;
import com.amenbank.banking_webapp.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Scheduled service to process pending/recurring transfers (fix #15).
 * Runs every minute to check for scheduled transfers ready to execute.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScheduledTransferService {

    private final TransferRepository transferRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final NotificationRepository notificationRepository;
    private final AuditService auditService;

    /**
     * Process scheduled (one-time) transfers that are due.
     * Runs every minute.
     */
    @Scheduled(fixedRate = 60_000)
    @Transactional
    public void processScheduledTransfers() {
        List<Transfer> readyTransfers = transferRepository.findScheduledTransfersReady(LocalDateTime.now());
        if (readyTransfers.isEmpty()) return;

        log.info("Processing {} scheduled transfers", readyTransfers.size());

        for (Transfer transfer : readyTransfers) {
            try {
                executeTransfer(transfer);
            } catch (Exception e) {
                log.error("Failed to execute scheduled transfer {}: {}",
                        transfer.getId(), e.getMessage());
                transfer.setStatus(Transfer.TransferStatus.FAILED);
                transferRepository.save(transfer);

                notificationRepository.save(Notification.builder()
                        .user(transfer.getSenderUser())
                        .type(Notification.NotificationType.TRANSFER)
                        .title("Virement programmé échoué ❌")
                        .body(String.format("Le virement de %.3f TND vers %s a échoué: %s",
                                transfer.getAmount(), transfer.getReceiverAccount().getAccountNumber(),
                                e.getMessage()))
                        .build());
            }
        }
    }

    /**
     * Process recurring transfers.
     * Runs every hour — checks for recurring transfers with PENDING status.
     */
    @Scheduled(fixedRate = 3_600_000)
    @Transactional
    public void processRecurringTransfers() {
        List<Transfer> recurring = transferRepository.findByIsRecurringTrueAndStatus(
                Transfer.TransferStatus.PENDING);
        if (recurring.isEmpty()) return;

        log.info("Processing {} recurring transfers", recurring.size());

        for (Transfer template : recurring) {
            // Check if scheduledAt is due
            if (template.getScheduledAt() != null && template.getScheduledAt().isAfter(LocalDateTime.now())) {
                continue;
            }

            try {
                // Create a new transfer from the template
                Account sender = accountRepository.findByIdForUpdate(template.getSenderAccount().getId())
                        .orElseThrow(() -> new RuntimeException("Sender account not found"));
                Account receiver = accountRepository.findByIdForUpdate(template.getReceiverAccount().getId())
                        .orElseThrow(() -> new RuntimeException("Receiver account not found"));

                if (sender.getBalance().compareTo(template.getAmount()) < 0) {
                    log.warn("Insufficient funds for recurring transfer {}", template.getId());
                    continue;
                }

                // Execute
                sender.setBalance(sender.getBalance().subtract(template.getAmount()));
                receiver.setBalance(receiver.getBalance().add(template.getAmount()));
                accountRepository.save(sender);
                accountRepository.save(receiver);

                // Create transaction records
                createTransactions(sender, receiver, template, template.getAmount());

                // Update template with next scheduled time
                template.setExecutedAt(LocalDateTime.now());
                // Keep as PENDING for next recurrence
                transferRepository.save(template);

                auditService.log(AuditLog.AuditAction.TRANSFER_CREATED,
                        template.getSenderUser().getEmail(), "Transfer",
                        template.getId().toString(), "Recurring transfer executed");

            } catch (Exception e) {
                log.error("Recurring transfer {} failed: {}", template.getId(), e.getMessage());
            }
        }
    }

    private void executeTransfer(Transfer transfer) {
        Account sender = accountRepository.findByIdForUpdate(transfer.getSenderAccount().getId())
                .orElseThrow(() -> new RuntimeException("Sender account not found"));
        Account receiver = accountRepository.findByAccountNumberForUpdate(
                transfer.getReceiverAccount().getAccountNumber())
                .orElseThrow(() -> new RuntimeException("Receiver account not found"));

        if (sender.getBalance().compareTo(transfer.getAmount()) < 0) {
            throw new RuntimeException("Solde insuffisant");
        }

        sender.setBalance(sender.getBalance().subtract(transfer.getAmount()));
        receiver.setBalance(receiver.getBalance().add(transfer.getAmount()));
        accountRepository.save(sender);
        accountRepository.save(receiver);

        createTransactions(sender, receiver, transfer, transfer.getAmount());

        transfer.setStatus(Transfer.TransferStatus.EXECUTED);
        transfer.setExecutedAt(LocalDateTime.now());
        transferRepository.save(transfer);

        // Notifications
        notificationRepository.save(Notification.builder()
                .user(transfer.getSenderUser())
                .type(Notification.NotificationType.TRANSFER)
                .title("Virement programmé effectué ✅")
                .body(String.format("Virement de %.3f TND vers %s effectué.",
                        transfer.getAmount(), receiver.getAccountNumber()))
                .build());

        notificationRepository.save(Notification.builder()
                .user(receiver.getUser())
                .type(Notification.NotificationType.TRANSFER)
                .title("Virement reçu")
                .body(String.format("Vous avez reçu %.3f TND de %s.",
                        transfer.getAmount(), transfer.getSenderUser().getFullNameFr()))
                .build());

        auditService.log(AuditLog.AuditAction.TRANSFER_CREATED,
                transfer.getSenderUser().getEmail(), "Transfer",
                transfer.getId().toString(), "Scheduled transfer executed");
    }

    private void createTransactions(Account sender, Account receiver,
                                     Transfer transfer, BigDecimal amount) {
        transactionRepository.save(Transaction.builder()
                .account(sender).transfer(transfer)
                .type(Transaction.TransactionType.DEBIT).amount(amount)
                .balanceAfter(sender.getBalance())
                .description("Virement programmé vers " + receiver.getAccountNumber())
                .category("virement").build());

        transactionRepository.save(Transaction.builder()
                .account(receiver).transfer(transfer)
                .type(Transaction.TransactionType.CREDIT).amount(amount)
                .balanceAfter(receiver.getBalance())
                .description("Virement reçu de " + sender.getAccountNumber())
                .category("virement").build());
    }
}

