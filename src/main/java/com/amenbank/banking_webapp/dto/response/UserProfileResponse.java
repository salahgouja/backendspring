package com.amenbank.banking_webapp.dto.response;

import com.amenbank.banking_webapp.model.User;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class UserProfileResponse {
    private UUID id;
    private String email;
    private String phone;
    private String fullNameAr;
    private String fullNameFr;
    private String cin;
    private String profileImageUrl;
    private User.UserType userType;
    private Boolean isActive;
    private Boolean is2faEnabled;
    private String agencyName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

