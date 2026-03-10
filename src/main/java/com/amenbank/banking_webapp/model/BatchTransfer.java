package com.amenbank.banking_webapp.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * GAP-3: Batch (grouped) transfer — a single request that creates multiple transfers.
 */
@Entity
@Table(name = "batch_transfers")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"user", "items"})
public class BatchTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_account_id", nullable = false)
    private Account senderAccount;

    @Column(nullable = false, precision = 15, scale = 3)
    private BigDecimal totalAmount;

    @Column(nullable = false)
    private Integer totalCount;

    @Column(nullable = false)
    @Builder.Default
    private Integer successCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer failedCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private BatchStatus status = BatchStatus.PROCESSING;

    @CreationTimestamp
    private LocalDateTime createdAt;

    private LocalDateTime completedAt;

    @OneToMany(mappedBy = "batchTransfer", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<BatchTransferItem> items = new ArrayList<>();

    public enum BatchStatus {
        PROCESSING,   // Currently processing items
        COMPLETED,    // All items succeeded
        PARTIAL,      // Some succeeded, some failed
        FAILED        // All items failed
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BatchTransfer that = (BatchTransfer) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}

