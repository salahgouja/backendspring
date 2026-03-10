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
 * Log entry for variable-rate loan revisions.
 * Created whenever the reference index changes and the loan rate is recalculated.
 */
@Entity
@Table(name = "rate_revisions", indexes = {
        @Index(name = "idx_revision_loan", columnList = "loan_contract_id")
})
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@ToString(exclude = {"loanContract"})
public class RateRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "loan_contract_id", nullable = false)
    private LoanContract loanContract;

    @Column(nullable = false)
    private LocalDate effectiveDate;

    /** Old reference index value */
    @Column(nullable = false, precision = 7, scale = 4)
    private BigDecimal previousReferenceRate;

    /** New reference index value */
    @Column(nullable = false, precision = 7, scale = 4)
    private BigDecimal newReferenceRate;

    /** Margin (unchanged) */
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal margin;

    /** Old total rate */
    @Column(nullable = false, precision = 7, scale = 4)
    private BigDecimal previousLoanRate;

    /** New total rate = newReferenceRate + margin (capped by floor/ceiling) */
    @Column(nullable = false, precision = 7, scale = 4)
    private BigDecimal newLoanRate;

    /** Old installment amount */
    @Column(nullable = false, precision = 15, scale = 3)
    private BigDecimal previousInstallment;

    /** Recalculated installment amount */
    @Column(nullable = false, precision = 15, scale = 3)
    private BigDecimal newInstallment;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RateRevision that = (RateRevision) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override public int hashCode() { return getClass().hashCode(); }
}

