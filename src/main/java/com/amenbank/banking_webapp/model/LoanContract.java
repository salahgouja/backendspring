package com.amenbank.banking_webapp.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A live loan contract — represents an active loan between the bank and a client.
 * Created when a CreditRequest is approved and disbursed.
 */
@Entity
@Table(name = "loan_contracts", indexes = {
        @Index(name = "idx_loan_user", columnList = "user_id"),
        @Index(name = "idx_loan_status", columnList = "status")
})
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@ToString(exclude = {"user", "product", "creditRequest", "account", "scheduleLines", "accruals", "payments", "rateRevisions"})
public class LoanContract {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Reference number displayed to the client */
    @Column(nullable = false, unique = true, length = 30)
    private String contractNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private LoanProduct product;

    /** Optional link back to the original credit request */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "credit_request_id")
    private CreditRequest creditRequest;

    /** Account to debit for repayments */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    // ── Financial parameters (snapshot at origination) ──
    @Column(nullable = false, precision = 15, scale = 3)
    private BigDecimal principalAmount;

    /** Capital Restant Dû — outstanding balance */
    @Column(nullable = false, precision = 15, scale = 3)
    private BigDecimal outstandingPrincipal;

    /** Current annual rate = referenceRate + margin (or fixedRate) */
    @Column(nullable = false, precision = 7, scale = 4)
    private BigDecimal currentRate;

    /** Snapshot of the reference index value at origination / last revision */
    @Column(precision = 7, scale = 4)
    private BigDecimal referenceRateValue;

    /** Snapshot of the margin */
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal margin;

    @Column(nullable = false)
    private Integer totalInstallments;

    @Column(nullable = false)
    @Builder.Default
    private Integer paidInstallments = 0;

    /** Computed installment amount (principal + interest) */
    @Column(nullable = false, precision = 15, scale = 3)
    private BigDecimal installmentAmount;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "TND";

    // ── Dates ──────────────────────────────────────────
    @Column(nullable = false)
    private LocalDate disbursementDate;

    @Column(nullable = false)
    private LocalDate firstInstallmentDate;

    @Column(nullable = false)
    private LocalDate maturityDate;

    /** Last date interest was accrued up to */
    @Column(nullable = false)
    private LocalDate lastAccrualDate;

    // ── Grace period ───────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private GracePeriodType gracePeriodType = GracePeriodType.NONE;

    @Column(nullable = false)
    @Builder.Default
    private Integer gracePeriodMonths = 0;

    // ── Accrued interest tracking ──────────────────────
    /** Total interest accrued but not yet billed */
    @Column(nullable = false, precision = 15, scale = 3)
    @Builder.Default
    private BigDecimal accruedInterest = BigDecimal.ZERO;

    /** Total interest paid over the life of the loan */
    @Column(nullable = false, precision = 15, scale = 3)
    @Builder.Default
    private BigDecimal totalInterestPaid = BigDecimal.ZERO;

    /** Total penalty interest accrued */
    @Column(nullable = false, precision = 15, scale = 3)
    @Builder.Default
    private BigDecimal totalPenaltyAccrued = BigDecimal.ZERO;

    // ── Status ─────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private LoanStatus status = LoanStatus.ACTIVE;

    @Column(nullable = false)
    @Builder.Default
    private Integer daysOverdue = 0;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    // ── Relationships ──────────────────────────────────
    @OneToMany(mappedBy = "loanContract", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @OrderBy("installmentNumber ASC")
    @Builder.Default
    private List<AmortizationSchedule> scheduleLines = new ArrayList<>();

    @OneToMany(mappedBy = "loanContract", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @OrderBy("accrualDate DESC")
    @Builder.Default
    private List<InterestAccrual> accruals = new ArrayList<>();

    @OneToMany(mappedBy = "loanContract", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @OrderBy("paymentDate DESC")
    @Builder.Default
    private List<LoanPayment> payments = new ArrayList<>();

    @OneToMany(mappedBy = "loanContract", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @OrderBy("effectiveDate DESC")
    @Builder.Default
    private List<RateRevision> rateRevisions = new ArrayList<>();

    // ── Enums ──────────────────────────────────────────
    public enum LoanStatus {
        ACTIVE,
        OVERDUE,
        DEFAULTED,     // > 90 days overdue
        RESTRUCTURED,
        PAID_OFF,
        WRITTEN_OFF,
        CANCELLED
    }

    public enum GracePeriodType {
        NONE,                   // No grace period
        TOTAL,                  // No payments at all during grace
        INTEREST_ONLY           // Pay interest only, no principal
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LoanContract that = (LoanContract) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override public int hashCode() { return getClass().hashCode(); }
}

