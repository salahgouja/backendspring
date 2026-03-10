package com.amenbank.banking_webapp.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Daily interest accrual record.
 * Interest = (CRD × Rate × Days) / (yearBasis × 100)
 * For Tunisian banking: Interest = (CRD × Rate × Days) / 36000
 */
@Entity
@Table(name = "interest_accruals", indexes = {
        @Index(name = "idx_accrual_loan", columnList = "loan_contract_id"),
        @Index(name = "idx_accrual_date", columnList = "accrualDate")
})
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@ToString(exclude = {"loanContract"})
public class InterestAccrual {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "loan_contract_id", nullable = false)
    private LoanContract loanContract;

    /** The date this accrual covers */
    @Column(nullable = false)
    private LocalDate accrualDate;

    /** Number of days covered (usually 1) */
    @Column(nullable = false)
    @Builder.Default
    private Integer days = 1;

    /** CRD at the time of accrual */
    @Column(nullable = false, precision = 15, scale = 3)
    private BigDecimal outstandingPrincipal;

    /** Annual rate applied */
    @Column(nullable = false, precision = 7, scale = 4)
    private BigDecimal rateApplied;

    /** Calculated interest for this period */
    @Column(nullable = false, precision = 15, scale = 6)
    private BigDecimal interestAmount;

    /** Is this a penalty accrual? */
    @Column(nullable = false)
    @Builder.Default
    private Boolean isPenalty = false;

    /** Year basis used (360 or 365) */
    @Column(nullable = false)
    private Integer yearBasis;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        InterestAccrual that = (InterestAccrual) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override public int hashCode() { return getClass().hashCode(); }
}

