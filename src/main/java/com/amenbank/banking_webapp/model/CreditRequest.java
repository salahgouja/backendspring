package com.amenbank.banking_webapp.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "credit_requests")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreditRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CreditType creditType;

    @Column(nullable = false, precision = 15, scale = 3)
    private BigDecimal amountRequested;

    @Column(nullable = false)
    private Integer durationMonths;

    @Column(precision = 5, scale = 2)
    private BigDecimal interestRate;

    @Column(precision = 15, scale = 3)
    private BigDecimal monthlyPayment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private CreditStatus status = CreditStatus.SIMULATION;

    private Double aiRiskScore; // 0.0 (no risk) to 1.0 (high risk)

    @CreationTimestamp
    private LocalDateTime createdAt;

    private LocalDateTime decidedAt;

    // ── Enums ──────────────────────────────────────────
    public enum CreditType {
        PERSONNEL, // Personal loan
        IMMOBILIER, // Mortgage
        COMMERCIAL, // Business loan
        EQUIPEMENT // Equipment financing
    }

    public enum CreditStatus {
        SIMULATION, // Just ran the calculator
        SUBMITTED, // Formal application
        IN_REVIEW, // Under review
        APPROVED,
        REJECTED,
        DISBURSED // Money credited to client's account
    }
}
