package com.amenbank.banking_webapp.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class TwoFactorCodeRequest {

    @Schema(description = "6-digit TOTP code from authenticator app", example = "123456")
    @NotBlank(message = "Le code 2FA est obligatoire")
    @Pattern(regexp = "\\d{6}", message = "Le code 2FA doit contenir 6 chiffres")
    private String code;
}

