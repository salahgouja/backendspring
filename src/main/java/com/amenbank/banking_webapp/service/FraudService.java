package com.amenbank.banking_webapp.service;

import com.amenbank.banking_webapp.dto.response.FraudAlertResponse;
import com.amenbank.banking_webapp.exception.BankingException;
import com.amenbank.banking_webapp.model.*;
import com.amenbank.banking_webapp.repository.FraudAlertRepository;
import com.amenbank.banking_webapp.repository.NotificationRepository;
import com.amenbank.banking_webapp.repository.TransferRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class FraudService {

    private final FraudAlertRepository fraudAlertRepository;
    private final TransferRepository transferRepository;
    private final NotificationRepository notificationRepository;

    @Value("${app.fraud.unusual-amount-threshold:50000.000}")
    private BigDecimal unusualAmountThreshold;

    @Value("${app.fraud.velocity-window-minutes:30}")
    private int velocityWindowMinutes;

    @Value("${app.fraud.velocity-max-count:5}")
    private int velocityMaxCount;

    @Async
    public void analyzeTransfer(Transfer transfer, User sender) {
        try {
            if (transfer.getAmount().compareTo(unusualAmountThreshold) > 0) {
                double riskScore = Math.min(1.0,
                        transfer.getAmount().doubleValue() / unusualAmountThreshold.doubleValue() * 0.5);
                createAlert(sender, null, FraudAlert.AlertType.UNUSUAL_AMOUNT, riskScore,
                        String.format("Transfer of %.3f TND exceeds threshold of %.3f TND",
                                transfer.getAmount(), unusualAmountThreshold));
            }

            LocalDateTime windowStart = LocalDateTime.now().minusMinutes(velocityWindowMinutes);
            long recentCount = transferRepository.countRecentTransfersByUser(sender.getId(), windowStart);
            if (recentCount > velocityMaxCount) {
                double riskScore = Math.min(1.0, (double) recentCount / velocityMaxCount * 0.6);
                createAlert(sender, null, FraudAlert.AlertType.VELOCITY, riskScore,
                        String.format("%d transfers in last %d minutes (threshold: %d)",
                                recentCount, velocityWindowMinutes, velocityMaxCount));
            }

            int hour = LocalDateTime.now().getHour();
            if (hour >= 1 && hour <= 5) {
                createAlert(sender, null, FraudAlert.AlertType.UNUSUAL_TIME, 0.3,
                        "Transfer initiated at unusual hour: " + hour + ":00");
            }
        } catch (Exception e) {
            log.error("Fraud analysis failed for transfer {}: {}", transfer.getId(), e.getMessage());
        }
    }

    private void createAlert(User user, Transaction transaction,
                             FraudAlert.AlertType type, double riskScore, String details) {
        FraudAlert alert = FraudAlert.builder()
                .user(user).transaction(transaction)
                .alertType(type).riskScore(riskScore).details(details).build();
        fraudAlertRepository.save(alert);

        notificationRepository.save(Notification.builder()
                .user(user).type(Notification.NotificationType.FRAUD)
                .title("Activité suspecte détectée ⚠️")
                .body("Notre système a détecté une activité inhabituelle sur votre compte. " +
                        "Si ce n'est pas vous, veuillez contacter votre agence immédiatement.")
                .build());

        log.warn("FRAUD ALERT: {} for user {} — risk={}", type, user.getEmail(), riskScore);
    }

    @Transactional(readOnly = true)
    public Page<FraudAlertResponse> getAllAlerts(int page, int size) {
        return fraudAlertRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size))
                .map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<FraudAlertResponse> getAlertsByStatus(FraudAlert.AlertStatus status, int page, int size) {
        return fraudAlertRepository.findByStatusOrderByCreatedAtDesc(status, PageRequest.of(page, size))
                .map(this::toResponse);
    }

    @Transactional
    public FraudAlertResponse resolveAlert(UUID alertId, String resolverEmail, FraudAlert.AlertStatus newStatus) {
        FraudAlert alert = fraudAlertRepository.findById(alertId)
                .orElseThrow(() -> new BankingException.NotFoundException("Alerte introuvable"));
        if (alert.getStatus() != FraudAlert.AlertStatus.OPEN
                && alert.getStatus() != FraudAlert.AlertStatus.INVESTIGATING) {
            throw new BankingException("Cette alerte a déjà été traitée");
        }
        if (newStatus != FraudAlert.AlertStatus.RESOLVED
                && newStatus != FraudAlert.AlertStatus.FALSE_POSITIVE
                && newStatus != FraudAlert.AlertStatus.INVESTIGATING) {
            throw new BankingException("Statut invalide");
        }
        alert.setStatus(newStatus);
        alert.setResolvedAt(LocalDateTime.now());
        alert.setResolvedBy(resolverEmail);
        fraudAlertRepository.save(alert);
        return toResponse(alert);
    }

    public long getOpenAlertCount() {
        return fraudAlertRepository.countByStatus(FraudAlert.AlertStatus.OPEN);
    }

    private FraudAlertResponse toResponse(FraudAlert a) {
        return FraudAlertResponse.builder()
                .id(a.getId())
                .alertType(a.getAlertType().name())
                .riskScore(a.getRiskScore())
                .status(a.getStatus().name())
                .details(a.getDetails())
                .userName(a.getUser().getFullNameFr())
                .userEmail(a.getUser().getEmail())
                .transactionId(a.getTransaction() != null ? a.getTransaction().getId() : null)
                .resolvedAt(a.getResolvedAt())
                .resolvedBy(a.getResolvedBy())
                .createdAt(a.getCreatedAt())
                .build();
    }
}

