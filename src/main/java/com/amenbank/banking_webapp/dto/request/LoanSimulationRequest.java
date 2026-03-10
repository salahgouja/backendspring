package com.amenbank.banking_webapp.dto.request;

import com.amenbank.banking_webapp.model.LoanContract;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request DTO for advanced loan simulation using the banking engine.
 */
@Data
public class LoanSimulationRequest {

    @NotNull(message = "Le code produit est obligatoire")
    private String productCode;

    @NotNull(message = "Le montant est obligatoire")
    @DecimalMin(value = "1000", message = "Le montant minimum est 1 000 TND")
    private BigDecimal amount;

    @NotNull(message = "La durée est obligatoire")
    @Min(value = 6, message = "La durée minimum est de 6 mois")
    @Max(value = 360, message = "La durée maximum est de 360 mois")
    private Integer durationMonths;

    /** Grace period type: NONE, TOTAL, INTEREST_ONLY */
    private LoanContract.GracePeriodType gracePeriodType;

    @Min(value = 0)
    @Max(value = 60)
    private Integer gracePeriodMonths;

    /** Optional: override the disbursement date for simulation (defaults to today) */
    private LocalDate disbursementDate;
}

