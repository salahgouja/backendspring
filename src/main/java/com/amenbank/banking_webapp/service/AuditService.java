package com.amenbank.banking_webapp.service;

import com.amenbank.banking_webapp.model.AuditLog;
import com.amenbank.banking_webapp.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    @Async
    public void log(AuditLog.AuditAction action, String actorEmail,
                    String entityType, String entityId, String details) {
        try {
            AuditLog auditLog = AuditLog.builder()
                    .actorEmail(actorEmail)
                    .action(action)
                    .entityType(entityType)
                    .entityId(entityId)
                    .details(details)
                    .build();
            auditLogRepository.save(auditLog);
            log.debug("Audit: {} by {} on {}:{}", action, actorEmail, entityType, entityId);
        } catch (Exception e) {
            log.error("Failed to save audit log: {} - {}", action, e.getMessage());
        }
    }

    public Page<Map<String, Object>> getAuditLogs(int page, int size) {
        return auditLogRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size))
                .map(this::toMap);
    }

    public Page<Map<String, Object>> getAuditLogsByActor(String email, int page, int size) {
        return auditLogRepository.findByActorEmailOrderByCreatedAtDesc(email, PageRequest.of(page, size))
                .map(this::toMap);
    }

    public List<Map<String, Object>> getAuditLogsForEntity(String entityType, String entityId) {
        return auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(entityType, entityId)
                .stream().map(this::toMap).toList();
    }

    private Map<String, Object> toMap(AuditLog a) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", a.getId());
        map.put("action", a.getAction().name());
        map.put("actorEmail", a.getActorEmail());
        map.put("entityType", a.getEntityType());
        map.put("entityId", a.getEntityId() != null ? a.getEntityId() : "");
        map.put("details", a.getDetails() != null ? a.getDetails() : "");
        map.put("createdAt", a.getCreatedAt() != null ? a.getCreatedAt().toString() : "");
        return map;
    }
}

