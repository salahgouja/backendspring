package com.amenbank.banking_webapp.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * GAP-3: Response DTO for batch (grouped) transfers.
 */
@Data
@Builder
public class BatchTransferResponse {

    private UUID id;
    private UUID senderAccountId;
    private String senderAccountNumber;
    private BigDecimal totalAmount;
    private Integer totalCount;
    private Integer successCount;
    private Integer failedCount;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    private List<BatchItemResponse> items;

    @Data
    @Builder
    public static class BatchItemResponse {
        private UUID id;
        private String receiverAccountNumber;
        private BigDecimal amount;
        private String motif;
        private String status;
        private String errorMessage;
        private UUID transferId;
    }
}

