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
 * Historical reference interest rates (e.g. TMM — Taux du Marché Monétaire).
 * Each row represents the rate in effect from a given date.
 * The latest row for a given index is the current active rate.
 */
@Entity
@Table(name = "reference_rates", indexes = {
        @Index(name = "idx_ref_rate_index_date", columnList = "indexName, effectiveDate DESC")
})
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class ReferenceRate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** The index name: TMM, EURIBOR, etc. */
    @Column(nullable = false, length = 20)
    private String indexName;

    /** Annual rate expressed as a percentage (e.g. 8.00 = 8%) */
    @Column(nullable = false, precision = 7, scale = 4)
    private BigDecimal rateValue;

    /** Date from which this rate is in effect */
    @Column(nullable = false)
    private LocalDate effectiveDate;

    /** Source / authority that published the rate */
    @Column(length = 100)
    private String source;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ReferenceRate that = (ReferenceRate) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override public int hashCode() { return getClass().hashCode(); }
}

