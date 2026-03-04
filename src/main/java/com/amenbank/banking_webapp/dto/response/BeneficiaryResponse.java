package com.amenbank.banking_webapp.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class BeneficiaryResponse {
    private UUID id;
    private String accountNumber;
    private String name;
    private String bankName;
    private LocalDateTime createdAt;
}

