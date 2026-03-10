package com.amenbank.banking_webapp.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * One line of the amortization table (échéancier).
 * Generated at loan creation; updated when payments are received.
 */
@Entity
@Table(name = "amortization_schedule", indexes = {
        @Index(name = "idx_amort_loan", columnList = "loan_contract_id"),
        @Index(name = "idx_amort_due", columnList = "dueDate")
})
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@ToString(exclude = {"loanContract"})
public class AmortizationSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "loan_contract_id", nullable = false)
    private LoanContract loanContract;

    @Column(nullable = false)
    private Integer installmentNumber;

    @Column(nullable = false)
    private LocalDate dueDate;

    /** Start of the interest period */
    @Column(nullable = false)
    private LocalDate periodStart;

    /** End of the interest period */
    @Column(nullable = false)
    private LocalDate periodEnd;

    /** Number of days in this interest period */
    @Column(nullable = false)
    private Integer daysInPeriod;

    /** Total installment = principal + interest */
    @Column(nullable = false, precision = 15, scale = 3)
    private BigDecimal installmentAmount;

    /** Principal portion of this installment */
    @Column(nullable = false, precision = 15, scale = 3)
    private BigDecimal principalAmount;

    /** Interest portion: I = (CRD × Rate × Days) / 36000 */
    @Column(nullable = false, precision = 15, scale = 3)
    private BigDecimal interestAmount;

    /** Outstanding principal BEFORE this installment */
    @Column(nullable = false, precision = 15, scale = 3)
    private BigDecimal openingBalance;

    /** Outstanding principal AFTER this installment */
    @Column(nullable = false, precision = 15, scale = 3)
    private BigDecimal closingBalance;

    /** The annual rate used for this installment */
    @Column(nullable = false, precision = 7, scale = 4)
    private BigDecimal rateApplied;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ScheduleStatus status = ScheduleStatus.PENDING;

    /** Date the installment was actually paid */
    private LocalDate paidDate;

    /** Amount actually paid (may differ if partial) */
    @Column(precision = 15, scale = 3)
    private BigDecimal paidAmount;

    /** Penalty interest for late payment */
    @Column(precision = 15, scale = 3)
    @Builder.Default
    private BigDecimal penaltyAmount = BigDecimal.ZERO;

    public enum ScheduleStatus {
        PENDING,        // Not yet due
        DUE,            // Due date reached
        PAID,           // Fully paid
        PARTIAL,        // Partially paid
        OVERDUE,        // Past due date, unpaid
        GRACE           // Within grace period (interest-only or total)
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AmortizationSchedule that = (AmortizationSchedule) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override public int hashCode() { return getClass().hashCode(); }
}

