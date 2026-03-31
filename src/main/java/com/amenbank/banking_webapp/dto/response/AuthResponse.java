package com.amenbank.banking_webapp.dto.response;

import com.amenbank.banking_webapp.model.User;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class AuthResponse {

    private String accessToken;
    private String refreshToken;
    private String tokenType;
    private UUID userId;
    private String email;
    private String fullName;
    private String profileImageUrl;
    private User.UserType userType;
    private Boolean is2faEnabled;
    private Boolean requires2fa; // True if user needs to verify 2FA before full access
}
