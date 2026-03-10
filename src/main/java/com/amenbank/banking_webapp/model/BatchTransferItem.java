package com.amenbank.banking_webapp.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * GAP-3: Individual item within a batch transfer.
 */
@Entity
@Table(name = "batch_transfer_items")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"batchTransfer", "transfer"})
public class BatchTransferItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_transfer_id", nullable = false)
    private BatchTransfer batchTransfer;

    @Column(nullable = false)
    private String receiverAccountNumber;

    @Column(nullable = false, precision = 15, scale = 3)
    private BigDecimal amount;

    private String motif;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ItemStatus status = ItemStatus.PENDING;

    private String errorMessage;

    /** Link to the actual transfer record if execution succeeded */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transfer_id")
    private Transfer transfer;

    @CreationTimestamp
    private LocalDateTime createdAt;

    public enum ItemStatus {
        PENDING,
        EXECUTED,
        FAILED
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BatchTransferItem that = (BatchTransferItem) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}

