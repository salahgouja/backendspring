package com.amenbank.banking_webapp.service;

import com.amenbank.banking_webapp.exception.BankingException;
import com.amenbank.banking_webapp.model.CreditRequest;
import com.amenbank.banking_webapp.model.LoanProduct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
@Slf4j
public class PricingComplianceService {

    private static final String POLICY_VERSION = "BCT-CAP-2026.1";

    @Value("${app.compliance.fixed-rate.max-retail:16.00}")
    private BigDecimal maxRetailFixedRate;

    public void validateAndSnapshot(CreditRequest credit,
                                    LoanProduct product,
                                    BigDecimal benchmarkRate,
                                    BigDecimal finalRate) {
        credit.setPricingPolicyVersion(POLICY_VERSION);
        credit.setBenchmarkRate(benchmarkRate);
        credit.setAppliedMargin(product.getMargin());
        credit.setFinalContractRate(finalRate);
        credit.setCapCheckTimestamp(LocalDateTime.now());

        if (isRetailCredit(credit.getCreditType()) && product.getRateType() == LoanProduct.RateType.FIXED) {
            boolean passed = finalRate.compareTo(maxRetailFixedRate) <= 0;
            credit.setCapCheckPassed(passed);
            if (!passed) {
                String message = String.format(
                        "Taux fixe non conforme a la politique %s: %.4f%% > plafond %.4f%%",
                        POLICY_VERSION, finalRate, maxRetailFixedRate);
                log.warn("Pricing compliance failed for credit {}: {}", credit.getId(), message);
                throw new BankingException(message);
            }
            return;
        }

        // Variable-rate products pass this cap rule; snapshot still stored for auditability.
        credit.setCapCheckPassed(true);
    }

    private boolean isRetailCredit(CreditRequest.CreditType type) {
        return type == CreditRequest.CreditType.PERSONNEL
                || type == CreditRequest.CreditType.IMMOBILIER
                || type == CreditRequest.CreditType.EQUIPEMENT;
    }
}

