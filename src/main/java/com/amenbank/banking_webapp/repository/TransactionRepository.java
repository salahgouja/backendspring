package com.amenbank.banking_webapp.repository;

import com.amenbank.banking_webapp.model.Transaction;
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
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    Page<Transaction> findByAccountIdOrderByCreatedAtDesc(UUID accountId, Pageable pageable);

    List<Transaction> findByAccountIdAndCreatedAtBetween(
            UUID accountId, LocalDateTime start, LocalDateTime end);

    // Paginated date range filter (fix #30)
    Page<Transaction> findByAccountIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            UUID accountId, LocalDateTime start, LocalDateTime end, Pageable pageable);

    List<Transaction> findByAccountUserIdOrderByCreatedAtDesc(UUID userId);

    // GAP-1: Advanced search with optional filters (date, amount, type, category)
    @Query("SELECT t FROM Transaction t WHERE t.account.id = :accountId " +
           "AND (:from IS NULL OR t.createdAt >= :from) " +
           "AND (:to IS NULL OR t.createdAt <= :to) " +
           "AND (:minAmount IS NULL OR t.amount >= :minAmount) " +
           "AND (:maxAmount IS NULL OR t.amount <= :maxAmount) " +
           "AND (:type IS NULL OR t.type = :type) " +
           "AND (:category IS NULL OR t.category = :category) " +
           "ORDER BY t.createdAt DESC")
    Page<Transaction> searchTransactions(
            @Param("accountId") UUID accountId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            @Param("minAmount") BigDecimal minAmount,
            @Param("maxAmount") BigDecimal maxAmount,
            @Param("type") Transaction.TransactionType type,
            @Param("category") String category,
            Pageable pageable);
}
