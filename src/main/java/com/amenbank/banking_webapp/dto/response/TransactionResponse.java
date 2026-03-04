package com.amenbank.banking_webapp.dto.response;

import com.amenbank.banking_webapp.model.Transaction;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class TransactionResponse {

    private UUID id;
    private Transaction.TransactionType type;
    private BigDecimal amount;
    private BigDecimal balanceAfter;
    private String description;
    private String category;
    private LocalDateTime createdAt;
}
