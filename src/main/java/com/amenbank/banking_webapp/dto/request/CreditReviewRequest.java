package com.amenbank.banking_webapp.dto.request;

import com.amenbank.banking_webapp.model.CreditRequest;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreditReviewRequest {

    @NotNull(message = "La décision est obligatoire (APPROVED ou REJECTED)")
    private CreditRequest.CreditStatus decision; // APPROVED or REJECTED

    private Double aiRiskScore; // Optional: 0.0 (no risk) to 1.0 (high risk)

    private String comment; // Optional: reason for rejection, etc.
}
