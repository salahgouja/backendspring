package com.amenbank.banking_webapp.repository;

import com.amenbank.banking_webapp.model.FraudAlert;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface FraudAlertRepository extends JpaRepository<FraudAlert, UUID> {

    Page<FraudAlert> findByStatusOrderByCreatedAtDesc(FraudAlert.AlertStatus status, Pageable pageable);

    Page<FraudAlert> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<FraudAlert> findByUserIdOrderByCreatedAtDesc(UUID userId);

    long countByStatus(FraudAlert.AlertStatus status);

    /**
     * Count recent transactions for velocity check
     */
    long countByUserIdAndCreatedAtAfter(UUID userId, LocalDateTime after);
}

