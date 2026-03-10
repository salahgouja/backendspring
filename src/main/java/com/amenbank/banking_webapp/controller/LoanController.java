package com.amenbank.banking_webapp.controller;

import com.amenbank.banking_webapp.dto.request.LoanSimulationRequest;
import com.amenbank.banking_webapp.dto.response.AmortizationLineResponse;
import com.amenbank.banking_webapp.dto.response.LoanContractResponse;
import com.amenbank.banking_webapp.dto.response.LoanProductResponse;
import com.amenbank.banking_webapp.exception.BankingException;
import com.amenbank.banking_webapp.exception.BankingException.NotFoundException;
import com.amenbank.banking_webapp.model.*;
import com.amenbank.banking_webapp.repository.*;
import com.amenbank.banking_webapp.service.LoanEngineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/loans")
@RequiredArgsConstructor
@Tag(name = "Loan Engine", description = "Banking-grade loan management — products, contracts, schedules, rate revisions")
@SecurityRequirement(name = "bearerAuth")
public class LoanController {

    private final LoanEngineService loanEngineService;
    private final LoanProductRepository loanProductRepository;
    private final LoanContractRepository loanContractRepository;
    private final ReferenceRateRepository referenceRateRepository;
    private final UserRepository userRepository;
    private final AccountRepository accountRepository;

    // ════════════════════════════════════════════════════════════
    // LOAN PRODUCTS
    // ════════════════════════════════════════════════════════════

    @GetMapping("/products")
    @Operation(summary = "List all active loan products with current rates")
    public ResponseEntity<List<LoanProductResponse>> listProducts() {
        List<LoanProduct> products = loanProductRepository.findByIsActiveTrueOrderByName();
        List<LoanProductResponse> responses = products.stream().map(p -> {
            BigDecimal currentRefRate = null;
            BigDecimal totalRate = null;
            if (p.getRateType() == LoanProduct.RateType.VARIABLE && p.getReferenceIndex() != null) {
                currentRefRate = referenceRateRepository
                        .findCurrentRate(p.getReferenceIndex(), LocalDate.now())
                        .map(ReferenceRate::getRateValue)
                        .orElse(null);
                if (currentRefRate != null) {
                    totalRate = currentRefRate.add(p.getMargin());
                }
            } else if (p.getFixedRate() != null) {
                totalRate = p.getFixedRate();
            }
            return toProductResponse(p, currentRefRate, totalRate);
        }).toList();
        return ResponseEntity.ok(responses);
    }

    @GetMapping("/products/{code}")
    @Operation(summary = "Get loan product details by code")
    public ResponseEntity<LoanProductResponse> getProduct(@PathVariable String code) {
        LoanProduct p = loanProductRepository.findByCode(code)
                .orElseThrow(() -> new NotFoundException("Produit introuvable: " + code));
        BigDecimal currentRefRate = null;
        BigDecimal totalRate = null;
        if (p.getRateType() == LoanProduct.RateType.VARIABLE && p.getReferenceIndex() != null) {
            currentRefRate = referenceRateRepository
                    .findCurrentRate(p.getReferenceIndex(), LocalDate.now())
                    .map(ReferenceRate::getRateValue).orElse(null);
            if (currentRefRate != null) totalRate = currentRefRate.add(p.getMargin());
        } else if (p.getFixedRate() != null) {
            totalRate = p.getFixedRate();
        }
        return ResponseEntity.ok(toProductResponse(p, currentRefRate, totalRate));
    }

    // ════════════════════════════════════════════════════════════
    // REFERENCE RATES (TMM History)
    // ════════════════════════════════════════════════════════════

    @GetMapping("/reference-rates")
    @Operation(summary = "List all reference rate indices")
    public ResponseEntity<List<String>> listIndices() {
        return ResponseEntity.ok(referenceRateRepository.findDistinctIndexNames());
    }

    @GetMapping("/reference-rates/{indexName}")
    @Operation(summary = "Get historical rates for an index (e.g. TMM)")
    public ResponseEntity<List<ReferenceRate>> getRateHistory(@PathVariable String indexName) {
        return ResponseEntity.ok(referenceRateRepository.findByIndexNameOrderByEffectiveDateDesc(indexName));
    }

    @PostMapping("/reference-rates")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Publish a new reference rate (Admin only)")
    public ResponseEntity<Map<String, Object>> publishRate(@RequestBody Map<String, Object> body) {
        String indexName = (String) body.get("indexName");
        BigDecimal rateValue = new BigDecimal(body.get("rateValue").toString());
        LocalDate effectiveDate = body.get("effectiveDate") != null
                ? LocalDate.parse(body.get("effectiveDate").toString())
                : LocalDate.now();
        String source = (String) body.getOrDefault("source", "BCT");

        ReferenceRate rate = ReferenceRate.builder()
                .indexName(indexName)
                .rateValue(rateValue)
                .effectiveDate(effectiveDate)
                .source(source)
                .build();
        referenceRateRepository.save(rate);

        // Trigger rate revision for all affected variable-rate loans
        int revised = loanEngineService.reviseVariableRateLoans(indexName);

        return ResponseEntity.ok(Map.of(
                "message", "Taux de référence publié avec succès",
                "indexName", indexName,
                "rateValue", rateValue,
                "effectiveDate", effectiveDate,
                "loansRevised", revised
        ));
    }

    // ════════════════════════════════════════════════════════════
    // LOAN SIMULATION (No persistence)
    // ════════════════════════════════════════════════════════════

    @PostMapping("/simulate")
    @Operation(summary = "Simulate a loan — generates a full amortization schedule without saving",
            description = "Uses the real banking formula: I = (CRD × Rate × Days) / 36000. " +
                          "Supports day count conventions, grace periods, and variable/fixed rates.")
    public ResponseEntity<Map<String, Object>> simulate(@Valid @RequestBody LoanSimulationRequest request) {
        LoanProduct product = loanProductRepository.findByCode(request.getProductCode())
                .orElseThrow(() -> new NotFoundException("Produit introuvable: " + request.getProductCode()));

        // Validate against product limits
        validateLoanRequest(product, request);

        LocalDate disbursement = request.getDisbursementDate() != null
                ? request.getDisbursementDate() : LocalDate.now();
        int graceMonths = request.getGracePeriodMonths() != null ? request.getGracePeriodMonths() : 0;
        LoanContract.GracePeriodType graceType = request.getGracePeriodType() != null
                ? request.getGracePeriodType() : LoanContract.GracePeriodType.NONE;

        // Resolve current rate
        BigDecimal loanRate;
        BigDecimal refRateValue = BigDecimal.ZERO;
        if (product.getRateType() == LoanProduct.RateType.VARIABLE) {
            ReferenceRate refRate = referenceRateRepository
                    .findCurrentRate(product.getReferenceIndex(), disbursement)
                    .orElseThrow(() -> new NotFoundException("Taux de référence introuvable"));
            refRateValue = refRate.getRateValue();
            loanRate = refRateValue.add(product.getMargin());
        } else {
            loanRate = product.getFixedRate();
        }

        // Apply floor/ceiling
        if (product.getFloorRate() != null && loanRate.compareTo(product.getFloorRate()) < 0)
            loanRate = product.getFloorRate();
        if (product.getCeilingRate() != null && loanRate.compareTo(product.getCeilingRate()) > 0)
            loanRate = product.getCeilingRate();

        int totalInstallments = switch (product.getRepaymentFrequency()) {
            case MONTHLY -> request.getDurationMonths();
            case QUARTERLY -> request.getDurationMonths() / 3;
            case ANNUAL -> request.getDurationMonths() / 12;
        };

        int graceInstallments = graceMonths > 0 ? switch (product.getRepaymentFrequency()) {
            case MONTHLY -> graceMonths;
            case QUARTERLY -> graceMonths / 3;
            case ANNUAL -> graceMonths / 12;
        } : 0;

        int activeInstallments = totalInstallments - graceInstallments;
        BigDecimal installment = loanEngineService.calculateInstallment(
                request.getAmount(), loanRate, activeInstallments, product);

        // Generate virtual schedule
        List<AmortizationLineResponse> scheduleLines = new java.util.ArrayList<>();
        BigDecimal crd = request.getAmount();
        BigDecimal totalInterest = BigDecimal.ZERO;
        LocalDate periodStart = disbursement;
        int yearBasis = product.yearBasis();

        for (int i = 1; i <= totalInstallments; i++) {
            LocalDate dueDate = switch (product.getRepaymentFrequency()) {
                case MONTHLY -> disbursement.plusMonths(i);
                case QUARTERLY -> disbursement.plusMonths((long) i * 3);
                case ANNUAL -> disbursement.plusYears(i);
            };
            int daysInPeriod = loanEngineService.calculateDaysInPeriod(
                    periodStart, dueDate, product.getDayCountConvention());

            BigDecimal interest = crd.multiply(loanRate, java.math.MathContext.DECIMAL128)
                    .multiply(BigDecimal.valueOf(daysInPeriod), java.math.MathContext.DECIMAL128)
                    .divide(BigDecimal.valueOf((long) yearBasis * 100), 6, java.math.RoundingMode.HALF_UP);

            boolean isGrace = i <= graceInstallments;
            BigDecimal instAmt;
            BigDecimal principalPortion;

            if (isGrace && graceType == LoanContract.GracePeriodType.TOTAL) {
                instAmt = BigDecimal.ZERO;
                principalPortion = BigDecimal.ZERO;
                crd = crd.add(interest);
            } else if (isGrace) {
                instAmt = interest.setScale(3, java.math.RoundingMode.HALF_UP);
                principalPortion = BigDecimal.ZERO;
            } else if (i == totalInstallments) {
                principalPortion = crd;
                instAmt = principalPortion.add(interest).setScale(3, java.math.RoundingMode.HALF_UP);
            } else {
                instAmt = installment;
                principalPortion = instAmt.subtract(interest.setScale(3, java.math.RoundingMode.HALF_UP));
                if (principalPortion.compareTo(BigDecimal.ZERO) < 0) {
                    principalPortion = BigDecimal.ZERO;
                    instAmt = interest.setScale(3, java.math.RoundingMode.HALF_UP);
                }
            }

            totalInterest = totalInterest.add(interest);
            BigDecimal closing = crd.subtract(principalPortion)
                    .setScale(3, java.math.RoundingMode.HALF_UP);
            if (closing.compareTo(BigDecimal.ZERO) < 0) closing = BigDecimal.ZERO;

            scheduleLines.add(AmortizationLineResponse.builder()
                    .installmentNumber(i)
                    .dueDate(dueDate)
                    .periodStart(periodStart)
                    .periodEnd(dueDate)
                    .daysInPeriod(daysInPeriod)
                    .installmentAmount(instAmt.setScale(3, java.math.RoundingMode.HALF_UP))
                    .principalAmount(principalPortion.setScale(3, java.math.RoundingMode.HALF_UP))
                    .interestAmount(interest.setScale(3, java.math.RoundingMode.HALF_UP))
                    .openingBalance(crd.setScale(3, java.math.RoundingMode.HALF_UP))
                    .closingBalance(closing)
                    .rateApplied(loanRate)
                    .status(isGrace ? "GRACE" : "PENDING")
                    .build());

            crd = closing;
            periodStart = dueDate;
        }

        BigDecimal totalCost = request.getAmount().add(totalInterest);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("productName", product.getName());
        result.put("productCode", product.getCode());
        result.put("rateType", product.getRateType().name());
        result.put("referenceIndex", product.getReferenceIndex() != null ? product.getReferenceIndex() : "N/A");
        result.put("referenceRateValue", refRateValue);
        result.put("margin", product.getMargin());
        result.put("loanRate", loanRate);
        result.put("principalAmount", request.getAmount());
        result.put("durationMonths", request.getDurationMonths());
        result.put("totalInstallments", totalInstallments);
        result.put("installmentAmount", installment);
        result.put("totalInterest", totalInterest.setScale(3, RoundingMode.HALF_UP));
        result.put("totalCost", totalCost.setScale(3, RoundingMode.HALF_UP));
        result.put("dayCountConvention", product.getDayCountConvention().name());
        result.put("repaymentFrequency", product.getRepaymentFrequency().name());
        result.put("gracePeriodType", graceType.name());
        result.put("gracePeriodMonths", graceMonths);
        result.put("amortizationSchedule", scheduleLines);

        return ResponseEntity.ok(result);
    }

    // ════════════════════════════════════════════════════════════
    // LOAN CONTRACTS
    // ════════════════════════════════════════════════════════════

    @GetMapping("/contracts")
    @Operation(summary = "List my loan contracts")
    public ResponseEntity<List<LoanContractResponse>> getMyLoans(Authentication auth) {
        User user = userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable"));
        List<LoanContract> loans = loanContractRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        return ResponseEntity.ok(loans.stream().map(this::toLoanResponse).toList());
    }

    @GetMapping("/contracts/{id}")
    @Operation(summary = "Get loan contract details")
    public ResponseEntity<LoanContractResponse> getLoan(Authentication auth, @PathVariable UUID id) {
        LoanContract loan = loanContractRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Contrat introuvable"));
        return ResponseEntity.ok(toLoanResponse(loan));
    }

    @GetMapping("/contracts/{id}/schedule")
    @Operation(summary = "Get full amortization schedule (échéancier)")
    public ResponseEntity<List<AmortizationLineResponse>> getSchedule(@PathVariable UUID id) {
        List<AmortizationSchedule> lines = loanEngineService.getAmortizationSchedule(id);
        return ResponseEntity.ok(lines.stream().map(this::toLineResponse).toList());
    }

    @GetMapping("/contracts/{id}/payments")
    @Operation(summary = "Get payment history for a loan")
    public ResponseEntity<List<LoanPayment>> getPayments(@PathVariable UUID id) {
        return ResponseEntity.ok(loanEngineService.getLoanPayments(id));
    }

    @GetMapping("/contracts/{id}/rate-revisions")
    @Operation(summary = "Get rate revision history for a variable-rate loan")
    public ResponseEntity<List<RateRevision>> getRevisions(@PathVariable UUID id) {
        return ResponseEntity.ok(loanEngineService.getRateRevisions(id));
    }

    @PostMapping("/contracts/{id}/pay")
    @Operation(summary = "Make a payment on a loan")
    public ResponseEntity<Map<String, Object>> makePayment(
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body) {
        BigDecimal amount = new BigDecimal(body.get("amount").toString());
        LocalDate paymentDate = body.get("paymentDate") != null
                ? LocalDate.parse(body.get("paymentDate").toString())
                : LocalDate.now();

        LoanPayment payment = loanEngineService.processPayment(id, amount, paymentDate);

        return ResponseEntity.ok(Map.of(
                "message", "Paiement traité avec succès",
                "paymentId", payment.getId(),
                "totalPaid", payment.getTotalPaid(),
                "principalPaid", payment.getPrincipalPaid(),
                "interestPaid", payment.getInterestPaid(),
                "penaltyPaid", payment.getPenaltyPaid(),
                "outstandingPrincipal", payment.getOutstandingAfter()
        ));
    }

    // ── GAP-15: Early Repayment ───────────────────────────
    @GetMapping("/contracts/{id}/early-repayment/simulate")
    @Operation(summary = "Simulate early repayment cost",
            description = "Calculates: outstanding principal + accrued interest + 2% early repayment penalty. Shows total to pay and interest saved.")
    public ResponseEntity<Map<String, Object>> simulateEarlyRepayment(@PathVariable UUID id) {
        return ResponseEntity.ok(loanEngineService.simulateEarlyRepayment(id));
    }

    @PostMapping("/contracts/{id}/early-repayment/execute")
    @Operation(summary = "Execute early repayment — closes the loan",
            description = "Debits the full amount from the linked account and marks the loan as PAID_OFF.")
    public ResponseEntity<Map<String, Object>> executeEarlyRepayment(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, Object> body) {
        LocalDate paymentDate = body != null && body.get("paymentDate") != null
                ? LocalDate.parse(body.get("paymentDate").toString())
                : LocalDate.now();

        LoanPayment payment = loanEngineService.executeEarlyRepayment(id, paymentDate);

        return ResponseEntity.ok(Map.of(
                "message", "Remboursement anticipé effectué — prêt soldé",
                "paymentId", payment.getId(),
                "totalPaid", payment.getTotalPaid(),
                "principalPaid", payment.getPrincipalPaid(),
                "interestPaid", payment.getInterestPaid(),
                "penaltyPaid", payment.getPenaltyPaid(),
                "loanStatus", "PAID_OFF"
        ));
    }

    // ════════════════════════════════════════════════════════════
    // ADMIN: MANAGE PRODUCTS
    // ════════════════════════════════════════════════════════════

    @PostMapping("/products")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a new loan product (Admin only)")
    public ResponseEntity<LoanProductResponse> createProduct(@RequestBody LoanProduct product) {
        if (loanProductRepository.existsByCode(product.getCode())) {
            throw new BankingException("Un produit avec le code " + product.getCode() + " existe déjà");
        }
        loanProductRepository.save(product);
        return ResponseEntity.ok(toProductResponse(product, null, null));
    }

    // ════════════════════════════════════════════════════════════
    // PRIVATE MAPPERS
    // ════════════════════════════════════════════════════════════

    private void validateLoanRequest(LoanProduct product, LoanSimulationRequest request) {
        if (product.getMinAmount() != null && request.getAmount().compareTo(product.getMinAmount()) < 0)
            throw new BankingException("Montant minimum: " + product.getMinAmount() + " TND");
        if (product.getMaxAmount() != null && request.getAmount().compareTo(product.getMaxAmount()) > 0)
            throw new BankingException("Montant maximum: " + product.getMaxAmount() + " TND");
        if (product.getMinDurationMonths() != null && request.getDurationMonths() < product.getMinDurationMonths())
            throw new BankingException("Durée minimum: " + product.getMinDurationMonths() + " mois");
        if (product.getMaxDurationMonths() != null && request.getDurationMonths() > product.getMaxDurationMonths())
            throw new BankingException("Durée maximum: " + product.getMaxDurationMonths() + " mois");
        int graceMonths = request.getGracePeriodMonths() != null ? request.getGracePeriodMonths() : 0;
        if (graceMonths > product.getMaxGracePeriodMonths())
            throw new BankingException("Période de grâce maximum: " + product.getMaxGracePeriodMonths() + " mois");
    }

    private LoanProductResponse toProductResponse(LoanProduct p, BigDecimal currentRefRate, BigDecimal totalRate) {
        return LoanProductResponse.builder()
                .id(p.getId()).name(p.getName()).code(p.getCode())
                .creditType(p.getCreditType().name()).rateType(p.getRateType().name())
                .referenceIndex(p.getReferenceIndex()).margin(p.getMargin())
                .fixedRate(p.getFixedRate()).floorRate(p.getFloorRate()).ceilingRate(p.getCeilingRate())
                .dayCountConvention(p.getDayCountConvention().name())
                .repaymentFrequency(p.getRepaymentFrequency().name())
                .interestMethod(p.getInterestMethod().name())
                .minAmount(p.getMinAmount()).maxAmount(p.getMaxAmount())
                .minDurationMonths(p.getMinDurationMonths()).maxDurationMonths(p.getMaxDurationMonths())
                .maxGracePeriodMonths(p.getMaxGracePeriodMonths())
                .penaltyMargin(p.getPenaltyMargin()).isActive(p.getIsActive())
                .currentReferenceRate(currentRefRate).currentTotalRate(totalRate)
                .build();
    }

    private LoanContractResponse toLoanResponse(LoanContract loan) {
        LoanProduct product = loan.getProduct();
        return LoanContractResponse.builder()
                .id(loan.getId()).contractNumber(loan.getContractNumber())
                .productName(product.getName()).productCode(product.getCode())
                .creditType(product.getCreditType().name()).rateType(product.getRateType().name())
                .principalAmount(loan.getPrincipalAmount())
                .outstandingPrincipal(loan.getOutstandingPrincipal())
                .currentRate(loan.getCurrentRate())
                .referenceRateValue(loan.getReferenceRateValue())
                .margin(loan.getMargin())
                .totalInstallments(loan.getTotalInstallments())
                .paidInstallments(loan.getPaidInstallments())
                .installmentAmount(loan.getInstallmentAmount())
                .currency(loan.getCurrency())
                .disbursementDate(loan.getDisbursementDate())
                .firstInstallmentDate(loan.getFirstInstallmentDate())
                .maturityDate(loan.getMaturityDate())
                .gracePeriodType(loan.getGracePeriodType().name())
                .gracePeriodMonths(loan.getGracePeriodMonths())
                .accruedInterest(loan.getAccruedInterest())
                .totalInterestPaid(loan.getTotalInterestPaid())
                .totalPenaltyAccrued(loan.getTotalPenaltyAccrued())
                .status(loan.getStatus().name())
                .daysOverdue(loan.getDaysOverdue())
                .dayCountConvention(product.getDayCountConvention().name())
                .repaymentFrequency(product.getRepaymentFrequency().name())
                .userName(loan.getUser().getFullNameFr())
                .userEmail(loan.getUser().getEmail())
                .createdAt(loan.getCreatedAt())
                .build();
    }

    private AmortizationLineResponse toLineResponse(AmortizationSchedule line) {
        return AmortizationLineResponse.builder()
                .id(line.getId()).installmentNumber(line.getInstallmentNumber())
                .dueDate(line.getDueDate()).periodStart(line.getPeriodStart()).periodEnd(line.getPeriodEnd())
                .daysInPeriod(line.getDaysInPeriod())
                .installmentAmount(line.getInstallmentAmount())
                .principalAmount(line.getPrincipalAmount())
                .interestAmount(line.getInterestAmount())
                .openingBalance(line.getOpeningBalance()).closingBalance(line.getClosingBalance())
                .rateApplied(line.getRateApplied()).status(line.getStatus().name())
                .paidDate(line.getPaidDate()).paidAmount(line.getPaidAmount())
                .penaltyAmount(line.getPenaltyAmount())
                .build();
    }
}

