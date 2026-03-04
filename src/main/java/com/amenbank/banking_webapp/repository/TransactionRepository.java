package com.amenbank.banking_webapp.repository;

import com.amenbank.banking_webapp.model.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
