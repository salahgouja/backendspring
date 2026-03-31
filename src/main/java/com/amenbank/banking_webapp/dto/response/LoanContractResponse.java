package com.amenbank.banking_webapp.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class LoanContractResponse {
    private UUID id;
    private String contractNumber;
    private String productName;
    private String productCode;
    private String creditType;
    private String rateType;
    private BigDecimal principalAmount;
    private BigDecimal outstandingPrincipal;
    private BigDecimal currentRate;
    private BigDecimal referenceRateValue;
    private BigDecimal margin;
    private Integer totalInstallments;
    private Integer paidInstallments;
    private BigDecimal installmentAmount;
    private String currency;
    private LocalDate disbursementDate;
    private LocalDate firstInstallmentDate;
    private LocalDate maturityDate;
    private String gracePeriodType;
    private Integer gracePeriodMonths;
    private BigDecimal accruedInterest;
    private BigDecimal totalInterestPaid;
    private BigDecimal totalPenaltyAccrued;
    private String status;
    private Integer daysOverdue;
    private String dayCountConvention;
    private String repaymentFrequency;

    // Borrower info
    private String userName;
    private String userEmail;

    // Link back to originating credit request when available
    private UUID creditRequestId;

    private LocalDateTime createdAt;
}
