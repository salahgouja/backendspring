package com.amenbank.banking_webapp.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class BeneficiaryRequest {

    @NotBlank(message = "Le numéro de compte est obligatoire")
    private String accountNumber;

    @NotBlank(message = "Le nom est obligatoire")
    private String name;

    private String bankName;
}

