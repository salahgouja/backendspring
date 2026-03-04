package com.amenbank.banking_webapp.service;

import com.amenbank.banking_webapp.dto.request.BeneficiaryRequest;
import com.amenbank.banking_webapp.dto.response.BeneficiaryResponse;
import com.amenbank.banking_webapp.exception.BankingException;
import com.amenbank.banking_webapp.model.Beneficiary;
import com.amenbank.banking_webapp.model.User;
import com.amenbank.banking_webapp.repository.BeneficiaryRepository;
import com.amenbank.banking_webapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BeneficiaryService {

    private static final int MAX_BENEFICIARIES = 50;

    private final BeneficiaryRepository beneficiaryRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<BeneficiaryResponse> getUserBeneficiaries(String email) {
        User user = findUser(email);
        return beneficiaryRepository.findByUserIdOrderByNameAsc(user.getId())
                .stream().map(this::toResponse).toList();
    }

    @Transactional
    public BeneficiaryResponse addBeneficiary(String email, BeneficiaryRequest request) {
        User user = findUser(email);
        if (beneficiaryRepository.existsByUserIdAndAccountNumber(user.getId(), request.getAccountNumber())) {
            throw new BankingException("Ce bénéficiaire existe déjà");
        }
        if (beneficiaryRepository.countByUserId(user.getId()) >= MAX_BENEFICIARIES) {
            throw new BankingException("Nombre maximum de bénéficiaires atteint (" + MAX_BENEFICIARIES + ")");
        }
        Beneficiary beneficiary = Beneficiary.builder()
                .user(user)
                .accountNumber(sanitize(request.getAccountNumber()))
                .name(sanitize(request.getName()))
                .bankName(request.getBankName() != null ? sanitize(request.getBankName()) : null)
                .build();
        beneficiaryRepository.save(beneficiary);
        log.info("Beneficiary added: {} -> {}", email, request.getAccountNumber());
        return toResponse(beneficiary);
    }

    @Transactional
    public void deleteBeneficiary(String email, UUID beneficiaryId) {
        User user = findUser(email);
        Beneficiary beneficiary = beneficiaryRepository.findById(beneficiaryId)
                .orElseThrow(() -> new BankingException.NotFoundException("Bénéficiaire introuvable"));
        if (!beneficiary.getUser().getId().equals(user.getId())) {
            throw new BankingException.ForbiddenException("Ce bénéficiaire ne vous appartient pas");
        }
        beneficiaryRepository.delete(beneficiary);
    }

    private User findUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new BankingException.NotFoundException("Utilisateur introuvable"));
    }

    private String sanitize(String input) {
        if (input == null) return null;
        return input.replaceAll("[<>\"'&]", "");
    }

    private BeneficiaryResponse toResponse(Beneficiary b) {
        return BeneficiaryResponse.builder()
                .id(b.getId())
                .accountNumber(b.getAccountNumber())
                .name(b.getName())
                .bankName(b.getBankName())
                .createdAt(b.getCreatedAt())
                .build();
    }
}

