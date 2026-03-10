package com.amenbank.banking_webapp.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
public class TransferRequest {

    @NotNull(message = "Le compte émetteur est obligatoire")
    private UUID senderAccountId;

    @NotBlank(message = "Le numéro de compte destinataire est obligatoire")
    private String receiverAccountNumber;

    @NotNull(message = "Le montant est obligatoire")
    @DecimalMin(value = "0.001", message = "Le montant doit être supérieur à 0")
    private BigDecimal amount;

    private String motif;

    /** TOTP code for 2FA verification before transfer execution (required if 2FA is enabled) */
    private String totpCode;
}
