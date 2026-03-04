package com.amenbank.banking_webapp.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Immutable audit trail for all sensitive operations.
 * Banking compliance requires tracking who did what and when.
 */
@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_actor", columnList = "actorEmail"),
        @Index(name = "idx_audit_action", columnList = "action"),
        @Index(name = "idx_audit_created", columnList = "createdAt")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String actorEmail; // Who performed the action

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditAction action;

    @Column(nullable = false)
    private String entityType; // e.g. "Account", "User", "Transfer", "CreditRequest"

    private String entityId; // UUID of the affected entity

    @Column(columnDefinition = "TEXT")
    private String details; // JSON details / description

    private String ipAddress;

    @CreationTimestamp
    private LocalDateTime createdAt;

    public enum AuditAction {
        // Auth
        USER_REGISTERED,
        USER_LOGIN,
        USER_LOGIN_FAILED,
        USER_LOGOUT,
        PASSWORD_CHANGED,
        TWO_FA_ENABLED,
        TWO_FA_DISABLED,

        // Account
        ACCOUNT_CREATED,
        ACCOUNT_APPROVED,
        ACCOUNT_REJECTED,
        ACCOUNT_SUSPENDED,
        ACCOUNT_UNSUSPENDED,
        ACCOUNT_CLOSED,

        // Transfer
        TRANSFER_CREATED,
        TRANSFER_FAILED,

        // Credit
        CREDIT_SUBMITTED,
        CREDIT_REVIEWED,
        CREDIT_DISBURSED,
        CREDIT_CANCELLED,

        // Admin
        USER_ACTIVATED,
        USER_DEACTIVATED,
        AGENT_CREATED,

        // Fraud
        FRAUD_ALERT_CREATED,
        FRAUD_ALERT_RESOLVED
    }
}

