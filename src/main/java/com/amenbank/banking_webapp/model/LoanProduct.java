package com.amenbank.banking_webapp.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Loan Product catalog — defines the parameters for each type of loan
 * offered by the bank. Agents select a product when creating a loan contract.
 * Rate = referenceIndex (e.g. TMM) + margin
 */
@Entity
@Table(name = "loan_products")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@ToString
public class LoanProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Human-readable product name (e.g. "Crédit Personnel", "Crédit Immobilier") */
    @Column(nullable = false, unique = true, length = 100)
    private String name;

    /** Internal product code (e.g. "CRED-PERSO", "CRED-IMMO") */
    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CreditRequest.CreditType creditType;

    // ── Rate parameters ────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private RateType rateType = RateType.VARIABLE;

    /** Reference index name (e.g. "TMM"). Null for fixed-rate loans. */
    @Column(length = 20)
    private String referenceIndex;

    /** Bank spread / margin over the reference index (in %) */
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal margin;

    /** For fixed-rate loans: the fixed annual rate (in %). */
    @Column(precision = 7, scale = 4)
    private BigDecimal fixedRate;

    /** Floor rate: minimum loan rate regardless of index (in %) */
    @Column(precision = 5, scale = 2)
    private BigDecimal floorRate;

    /** Ceiling rate: maximum loan rate (in %) */
    @Column(precision = 5, scale = 2)
    private BigDecimal ceilingRate;

    // ── Day count & frequency ──────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private DayCountConvention dayCountConvention = DayCountConvention.ACTUAL_360;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private RepaymentFrequency repaymentFrequency = RepaymentFrequency.MONTHLY;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private InterestMethod interestMethod = InterestMethod.AMORTIZED;

    // ── Loan limits ────────────────────────────────────
    @Column(precision = 15, scale = 3)
    private BigDecimal minAmount;

    @Column(precision = 15, scale = 3)
    private BigDecimal maxAmount;

    @Column
    private Integer minDurationMonths;

    @Column
    private Integer maxDurationMonths;

    // ── Grace period ───────────────────────────────────
    /** Maximum grace period in months allowed for this product */
    @Column
    @Builder.Default
    private Integer maxGracePeriodMonths = 0;

    // ── Penalty ────────────────────────────────────────
    /** Penalty margin added to the loan rate for late payments (in %) */
    @Column(precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal penaltyMargin = new BigDecimal("2.00");

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    // ── Enums ──────────────────────────────────────────
    public enum RateType {
        FIXED,
        VARIABLE
    }

    public enum DayCountConvention {
        THIRTY_360,     // 30/360 (European)
        ACTUAL_360,     // Actual/360 (French / Tunisian standard)
        ACTUAL_365      // Actual/365 (English)
    }

    public enum RepaymentFrequency {
        MONTHLY,    // Every month
        QUARTERLY,  // Every 3 months
        ANNUAL      // Once a year
    }

    public enum InterestMethod {
        AMORTIZED,      // Classic amortized loan (annuity)
        REVOLVING       // Overdraft / revolving credit (daily interest on used balance)
    }

    // ── Helpers ────────────────────────────────────────
    /** Number of installments per year */
    public int periodsPerYear() {
        return switch (repaymentFrequency) {
            case MONTHLY -> 12;
            case QUARTERLY -> 4;
            case ANNUAL -> 1;
        };
    }

    /** Day count denominator for the convention */
    public int yearBasis() {
        return switch (dayCountConvention) {
            case THIRTY_360, ACTUAL_360 -> 360;
            case ACTUAL_365 -> 365;
        };
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LoanProduct that = (LoanProduct) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override public int hashCode() { return getClass().hashCode(); }
}

