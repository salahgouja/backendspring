package com.amenbank.banking_webapp.controller;

import com.amenbank.banking_webapp.dto.request.BeneficiaryRequest;
import com.amenbank.banking_webapp.dto.response.BeneficiaryResponse;
import com.amenbank.banking_webapp.service.BeneficiaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/beneficiaries")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Beneficiaries", description = "Saved recipients for quick transfers")
public class BeneficiaryController {

    private final BeneficiaryService beneficiaryService;

    @GetMapping
    @Operation(summary = "List my saved beneficiaries")
    public ResponseEntity<List<BeneficiaryResponse>> getMyBeneficiaries(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(beneficiaryService.getUserBeneficiaries(userDetails.getUsername()));
    }

    @PostMapping
    @Operation(summary = "Add a new beneficiary")
    public ResponseEntity<BeneficiaryResponse> addBeneficiary(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody BeneficiaryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(beneficiaryService.addBeneficiary(userDetails.getUsername(), request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Remove a saved beneficiary")
    public ResponseEntity<Void> deleteBeneficiary(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable UUID id) {
        beneficiaryService.deleteBeneficiary(userDetails.getUsername(), id);
        return ResponseEntity.noContent().build();
    }
}

