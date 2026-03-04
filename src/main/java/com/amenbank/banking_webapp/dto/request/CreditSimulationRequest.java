package com.amenbank.banking_webapp.dto.request;

import com.amenbank.banking_webapp.model.CreditRequest;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreditSimulationRequest {

    @NotNull(message = "Le type de crédit est obligatoire")
    private CreditRequest.CreditType creditType;

    @NotNull(message = "Le montant est obligatoire")
    @DecimalMin(value = "1000", message = "Le montant minimum est 1 000 TND")
    private BigDecimal amountRequested;

    @NotNull(message = "La durée est obligatoire")
    @Min(value = 6, message = "La durée minimum est de 6 mois")
    @Max(value = 360, message = "La durée maximum est de 360 mois (30 ans)")
    private Integer durationMonths;
}
