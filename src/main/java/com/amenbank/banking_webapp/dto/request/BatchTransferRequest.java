package com.amenbank.banking_webapp.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * GAP-3: Request DTO for batch (grouped) transfers — multiple beneficiaries in one request.
 */
@Data
public class BatchTransferRequest {

    @NotNull(message = "Le compte émetteur est obligatoire")
    private UUID senderAccountId;

    @NotEmpty(message = "Au moins un virement est requis")
    @Size(max = 20, message = "Maximum 20 virements par lot")
    @Valid
    private List<BatchItem> items;

    /** TOTP code for 2FA verification (required if 2FA is enabled) */
    private String totpCode;

    @Data
    public static class BatchItem {

        @NotBlank(message = "Le numéro de compte destinataire est obligatoire")
        private String receiverAccountNumber;

        @NotNull(message = "Le montant est obligatoire")
        @DecimalMin(value = "0.001", message = "Le montant doit être supérieur à 0")
        private BigDecimal amount;

        private String motif;
    }
}

