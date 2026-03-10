package com.amenbank.banking_webapp.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * GAP-6: Response DTO for credit documents.
 */
@Data
@Builder
public class CreditDocumentResponse {

    private UUID id;
    private UUID creditRequestId;
    private String fileName;
    private String fileType;
    private Long fileSize;
    private String documentType;
    private LocalDateTime uploadedAt;
    private String downloadUrl;
}

