package com.amenbank.banking_webapp.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Actual payment received against a loan contract.
 */
@Entity
@Table(name = "loan_payments", indexes = {
        @Index(name = "idx_payment_loan", columnList = "loan_contract_id"),
        @Index(name = "idx_payment_date", columnList = "paymentDate")
})
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@ToString(exclude = {"loanContract", "scheduleEntry"})
public class LoanPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "loan_contract_id", nullable = false)
    private LoanContract loanContract;

    /** Which schedule line this payment settles (nullable if extra payment) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_entry_id")
    private AmortizationSchedule scheduleEntry;

    @Column(nullable = false)
    private LocalDate paymentDate;

    @Column(nullable = false, precision = 15, scale = 3)
    private BigDecimal totalPaid;

    /** How much went to principal */
    @Column(nullable = false, precision = 15, scale = 3)
    private BigDecimal principalPaid;

    /** How much went to interest */
    @Column(nullable = false, precision = 15, scale = 3)
    private BigDecimal interestPaid;

    /** How much went to penalty */
    @Column(nullable = false, precision = 15, scale = 3)
    @Builder.Default
    private BigDecimal penaltyPaid = BigDecimal.ZERO;

    /** Outstanding principal after this payment */
    @Column(nullable = false, precision = 15, scale = 3)
    private BigDecimal outstandingAfter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private PaymentType paymentType = PaymentType.SCHEDULED;

    @CreationTimestamp
    private LocalDateTime createdAt;

    public enum PaymentType {
        SCHEDULED,       // Regular scheduled payment
        EARLY,           // Early / prepayment
        PARTIAL,         // Partial payment
        PENALTY          // Penalty payment
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LoanPayment that = (LoanPayment) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override public int hashCode() { return getClass().hashCode(); }
}

