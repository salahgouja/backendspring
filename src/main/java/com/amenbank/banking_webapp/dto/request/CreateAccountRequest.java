package com.amenbank.banking_webapp.dto.request;

import com.amenbank.banking_webapp.model.Account;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateAccountRequest {

    @NotNull(message = "Le type de compte est obligatoire")
    private Account.AccountType accountType;

    private String currency;
}

