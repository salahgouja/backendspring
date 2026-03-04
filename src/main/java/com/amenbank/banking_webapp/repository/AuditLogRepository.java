package com.amenbank.banking_webapp.repository;

import com.amenbank.banking_webapp.model.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    Page<AuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<AuditLog> findByActorEmailOrderByCreatedAtDesc(String actorEmail, Pageable pageable);

    Page<AuditLog> findByActionOrderByCreatedAtDesc(AuditLog.AuditAction action, Pageable pageable);

    List<AuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, String entityId);

    long countByActionAndCreatedAtAfter(AuditLog.AuditAction action, LocalDateTime after);
}

