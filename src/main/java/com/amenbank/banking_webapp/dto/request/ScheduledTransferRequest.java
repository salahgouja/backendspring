package com.amenbank.banking_webapp.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * GAP-4: Request DTO for creating scheduled (one-time) or recurring (permanent) transfers.
 */
@Data
public class ScheduledTransferRequest {

    @NotNull(message = "Le compte émetteur est obligatoire")
    private UUID senderAccountId;

    @NotBlank(message = "Le numéro de compte destinataire est obligatoire")
    private String receiverAccountNumber;

    @NotNull(message = "Le montant est obligatoire")
    @DecimalMin(value = "0.001", message = "Le montant doit être supérieur à 0")
    private BigDecimal amount;

    private String motif;

    /** For one-time scheduled transfers — when to execute */
    private LocalDateTime scheduledAt;

    /** For recurring (permanent) transfers — interval in months (e.g., 1 = monthly, 3 = quarterly) */
    @Min(value = 1, message = "L'intervalle de récurrence minimum est de 1 mois")
    @Max(value = 12, message = "L'intervalle de récurrence maximum est de 12 mois")
    private Integer recurrenceIntervalMonths;

    /** TOTP code for 2FA verification (required if 2FA is enabled) */
    @Schema(description = "Code TOTP 2FA a 6 chiffres (requis si la 2FA est activee)", example = "123456")
    @Pattern(regexp = "^$|\\d{6}", message = "Le code TOTP doit contenir 6 chiffres")
    private String totpCode;
}
