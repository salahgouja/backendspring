package com.amenbank.banking_webapp.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "fraud_alerts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"user", "transaction"})
public class FraudAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id")
    private Transaction transaction; // nullable

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AlertType alertType;

    @Column(nullable = false)
    private Double riskScore; // 0.0 to 1.0

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private AlertStatus status = AlertStatus.OPEN;

    @Column(columnDefinition = "TEXT")
    private String details; // JSON details about the alert

    private LocalDateTime resolvedAt;

    private String resolvedBy; // Email of the agent/admin who resolved

    @CreationTimestamp
    private LocalDateTime createdAt;

    // ── Enums ──────────────────────────────────────────
    public enum AlertType {
        UNUSUAL_AMOUNT,
        UNUSUAL_LOCATION,
        UNUSUAL_TIME,
        VELOCITY // Too many transactions in short time
    }

    public enum AlertStatus {
        OPEN,
        INVESTIGATING,
        RESOLVED,
        FALSE_POSITIVE
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FraudAlert that = (FraudAlert) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
