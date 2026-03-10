package com.amenbank.banking_webapp.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class CreditResponse {
    private UUID id;
    private String creditType;
    private BigDecimal amountRequested;
    private Integer durationMonths;
    private BigDecimal interestRate;
    private BigDecimal monthlyPayment;
    private BigDecimal totalCost; // Total repayment = monthlyPayment × duration
    private BigDecimal totalInterest; // Total interest = totalCost - amountRequested
    private String status;
    private String purpose;
    private Double aiRiskScore;
    private LocalDateTime createdAt;
    private LocalDateTime decidedAt;

    // Requester info (for agent/admin review)
    private String userName;
    private String userEmail;
    private String agencyName;
}
