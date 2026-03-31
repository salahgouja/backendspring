package com.amenbank.banking_webapp.service;

import com.amenbank.banking_webapp.exception.BankingException;
import com.amenbank.banking_webapp.model.AuditLog;
import com.amenbank.banking_webapp.repository.AuditLogRepository;
import com.amenbank.banking_webapp.repository.specification.AuditLogSpecification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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

    public Page<Map<String, Object>> getFilteredAuditLogs(
            String actorEmail,
            AuditLog.AuditAction action,
            String entityType,
            LocalDateTime dateFrom,
            LocalDateTime dateTo,
            int page,
            int size
    ) {
        Specification<AuditLog> specification = AuditLogSpecification.withFilters(
                actorEmail, action, entityType, dateFrom, dateTo
        );
        return auditLogRepository.findAll(specification, PageRequest.of(page, size)).map(this::toMap);
    }

    public Page<Map<String, Object>> getMyAuditLogs(String email, int page, int size) {
        return auditLogRepository.findByActorEmailOrderByCreatedAtDesc(email, PageRequest.of(page, size))
                .map(this::toMap);
    }

    public String exportToCsv(
            String actorEmail,
            AuditLog.AuditAction action,
            String entityType,
            LocalDateTime dateFrom,
            LocalDateTime dateTo
    ) {
        try {
            Specification<AuditLog> specification = AuditLogSpecification.withFilters(
                    actorEmail, action, entityType, dateFrom, dateTo
            );
            List<AuditLog> logs = auditLogRepository.findAll(
                    specification,
                    PageRequest.of(0, 10000, Sort.by(Sort.Direction.DESC, "createdAt"))
            ).getContent();

            StringBuilder csv = new StringBuilder();
            csv.append("Date,Acteur,Action,Type Entité,ID Entité,Détails\n");
            for (AuditLog logEntry : logs) {
                csv.append(String.format("\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"\n",
                        safeCsv(logEntry.getCreatedAt()),
                        safeCsv(logEntry.getActorEmail()),
                        safeCsv(logEntry.getAction()),
                        safeCsv(logEntry.getEntityType()),
                        safeCsv(logEntry.getEntityId()),
                        safeCsv(logEntry.getDetails())));
            }
            return csv.toString();
        } catch (Exception e) {
            throw new BankingException("Erreur lors de l'export CSV des logs d'audit");
        }
    }

    public Page<Map<String, Object>> getAuditLogsByActor(String email, int page, int size) {
        return auditLogRepository.findByActorEmailOrderByCreatedAtDesc(email, PageRequest.of(page, size))
                .map(this::toMap);
    }

    public List<Map<String, Object>> getAuditLogsForEntity(String entityType, String entityId) {
        return auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(entityType, entityId)
                .stream().map(this::toMap).toList();
    }

    private String safeCsv(Object value) {
        if (value == null) {
            return "";
        }
        return value.toString().replace("\"", "'");
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

