package com.amenbank.banking_webapp.dto.request;

import com.amenbank.banking_webapp.model.User;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.UUID;

@Data
public class RegisterRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    @NotBlank(message = "Phone is required")
    @Pattern(regexp = "^[0-9]{8}$", message = "Phone must be 8 digits")
    private String phone;

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;

    @NotBlank(message = "Full name in Arabic is required")
    private String fullNameAr;

    @NotBlank(message = "Full name in French is required")
    private String fullNameFr;

    @NotBlank(message = "CIN is required")
    @Pattern(regexp = "^[0-9]{8}$", message = "CIN must be 8 digits")
    private String cin;

    @NotNull(message = "User type is required")
    private User.UserType userType;

    private UUID agencyId; // Required for PARTICULIER/COMMERCANT
}
