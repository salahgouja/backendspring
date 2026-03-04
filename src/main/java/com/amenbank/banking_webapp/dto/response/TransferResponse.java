package com.amenbank.banking_webapp.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class TransferResponse {
    private UUID id;
    private UUID senderAccountId;
    private String senderAccountNumber;
    private UUID receiverAccountId;
    private String receiverAccountNumber;
    private String receiverName;
    private BigDecimal amount;
    private String currency;
    private String motif;
    private String status;
    private LocalDateTime executedAt;
    private LocalDateTime createdAt;
}
