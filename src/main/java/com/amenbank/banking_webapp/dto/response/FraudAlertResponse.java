package com.amenbank.banking_webapp.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class FraudAlertResponse {
    private UUID id;
    private String alertType;
    private Double riskScore;
    private String status;
    private String details;
    private String userName;
    private String userEmail;
    private UUID transactionId;
    private LocalDateTime resolvedAt;
    private String resolvedBy;
    private LocalDateTime createdAt;
}

