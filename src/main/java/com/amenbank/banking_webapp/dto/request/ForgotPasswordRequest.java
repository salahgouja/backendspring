package com.amenbank.banking_webapp.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ForgotPasswordRequest {

    @NotBlank(message = "L'adresse email est obligatoire")
    @Email(message = "Adresse email invalide")
    private String email;
}

