package com.amenbank.banking_webapp.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Data
@Builder
public class AmortizationLineResponse {
    private UUID id;
    private Integer installmentNumber;
    private LocalDate dueDate;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private Integer daysInPeriod;
    private BigDecimal installmentAmount;
    private BigDecimal principalAmount;
    private BigDecimal interestAmount;
    private BigDecimal openingBalance;
    private BigDecimal closingBalance;
    private BigDecimal rateApplied;
    private String status;
    private LocalDate paidDate;
    private BigDecimal paidAmount;
    private BigDecimal penaltyAmount;
}

