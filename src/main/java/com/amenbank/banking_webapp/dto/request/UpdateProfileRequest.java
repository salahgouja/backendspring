package com.amenbank.banking_webapp.dto.request;

import lombok.Data;

@Data
public class UpdateProfileRequest {
    private String phone;
    private String fullNameAr;
    private String fullNameFr;
    private String profileImageUrl;
}

