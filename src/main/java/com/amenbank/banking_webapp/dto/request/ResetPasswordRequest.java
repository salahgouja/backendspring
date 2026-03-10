package com.amenbank.banking_webapp.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ResetPasswordRequest {

    @NotBlank(message = "L'adresse email est obligatoire")
    @Email(message = "Adresse email invalide")
    private String email;

    @NotBlank(message = "Le code OTP est obligatoire")
    @Size(min = 6, max = 6, message = "Le code OTP doit faire 6 chiffres")
    private String otpCode;

    @NotBlank(message = "Le nouveau mot de passe est obligatoire")
    @Size(min = 8, message = "Le mot de passe doit faire au moins 8 caractères")
    @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]{8,}$",
             message = "Le mot de passe doit contenir: majuscule, minuscule, chiffre, caractère spécial")
    private String newPassword;
}

