package com.amenbank.banking_webapp.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
public class LoanProductResponse {
    private UUID id;
    private String name;
    private String code;
    private String creditType;
    private String rateType;
    private String referenceIndex;
    private BigDecimal margin;
    private BigDecimal fixedRate;
    private BigDecimal floorRate;
    private BigDecimal ceilingRate;
    private String dayCountConvention;
    private String repaymentFrequency;
    private String interestMethod;
    private BigDecimal minAmount;
    private BigDecimal maxAmount;
    private Integer minDurationMonths;
    private Integer maxDurationMonths;
    private Integer maxGracePeriodMonths;
    private BigDecimal penaltyMargin;
    private Boolean isActive;
    // Current reference rate value (resolved at query time)
    private BigDecimal currentReferenceRate;
    private BigDecimal currentTotalRate;
}

