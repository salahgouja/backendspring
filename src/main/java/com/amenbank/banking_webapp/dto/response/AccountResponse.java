package com.amenbank.banking_webapp.dto.response;

import com.amenbank.banking_webapp.model.Account;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class AccountResponse {

    private UUID id;
    private String accountNumber;
    private Account.AccountType accountType;
    private Account.AccountStatus status;
    private BigDecimal balance;
    private String currency;
    private Boolean isActive;
    private String ownerName;
    private String ownerCin;
    private String agencyName;
    private LocalDateTime createdAt;
}
