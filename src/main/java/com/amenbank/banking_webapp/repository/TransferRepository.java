package com.amenbank.banking_webapp.repository;

import com.amenbank.banking_webapp.model.Transfer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    List<Transfer> findBySenderUserIdOrderByCreatedAtDesc(UUID userId);

    Page<Transfer> findBySenderUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    @Query("SELECT t FROM Transfer t WHERE t.receiverAccount.user.id = :userId ORDER BY t.createdAt DESC")
    Page<Transfer> findReceivedByUserId(@Param("userId") UUID userId, Pageable pageable);

    @Query("SELECT t FROM Transfer t WHERE t.senderUser.id = :userId OR t.receiverAccount.user.id = :userId ORDER BY t.createdAt DESC")
    Page<Transfer> findAllByUserId(@Param("userId") UUID userId, Pageable pageable);

    List<Transfer> findByIsRecurringTrueAndStatus(Transfer.TransferStatus status);

    @Query("SELECT t FROM Transfer t WHERE t.scheduledAt <= :now AND t.status = 'PENDING' AND t.isRecurring = false")
    List<Transfer> findScheduledTransfersReady(@Param("now") LocalDateTime now);

    @Query("SELECT COALESCE(SUM(t.amount), 0) FROM Transfer t WHERE t.senderUser.id = :userId AND t.status = 'EXECUTED' AND t.executedAt >= :startOfDay")
    BigDecimal sumDailyTransfersByUser(@Param("userId") UUID userId, @Param("startOfDay") LocalDateTime startOfDay);

    @Query("SELECT COUNT(t) FROM Transfer t WHERE t.senderUser.id = :userId AND t.createdAt >= :since")
    long countRecentTransfersByUser(@Param("userId") UUID userId, @Param("since") LocalDateTime since);

    // GAP-4: Find all scheduled/recurring transfers for a user (PENDING status)
    @Query("SELECT t FROM Transfer t WHERE t.senderUser.id = :userId " +
           "AND (t.scheduledAt IS NOT NULL OR t.isRecurring = true) " +
           "ORDER BY t.createdAt DESC")
    Page<Transfer> findScheduledByUserId(@Param("userId") UUID userId, Pageable pageable);

    // GAP-5: Find recurring transfers due within the next 24 hours for pre-notification
    @Query("SELECT t FROM Transfer t WHERE t.isRecurring = true AND t.status = 'PENDING' " +
           "AND t.scheduledAt IS NOT NULL AND t.scheduledAt <= :notifyBefore AND t.scheduledAt > :now")
    List<Transfer> findRecurringTransfersDueSoon(@Param("now") LocalDateTime now, @Param("notifyBefore") LocalDateTime notifyBefore);
}
