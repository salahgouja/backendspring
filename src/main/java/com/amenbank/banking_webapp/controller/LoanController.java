package com.amenbank.banking_webapp.controller;

import com.amenbank.banking_webapp.dto.request.LoanApplicationRequest;
import com.amenbank.banking_webapp.dto.request.LoanSimulationRequest;
import com.amenbank.banking_webapp.dto.response.AmortizationLineResponse;
import com.amenbank.banking_webapp.dto.response.CreditResponse;
import com.amenbank.banking_webapp.dto.response.LoanContractResponse;
import com.amenbank.banking_webapp.dto.response.LoanPaymentResponse;
import com.amenbank.banking_webapp.dto.response.LoanProductResponse;
import com.amenbank.banking_webapp.dto.request.CreditSimulationRequest;
import com.amenbank.banking_webapp.exception.BankingException;
import com.amenbank.banking_webapp.exception.BankingException.NotFoundException;
import com.amenbank.banking_webapp.model.*;
import com.amenbank.banking_webapp.repository.*;
import com.amenbank.banking_webapp.service.CreditService;
import com.amenbank.banking_webapp.service.LoanEngineService;
import com.amenbank.banking_webapp.service.StatementService;
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
    private final CreditService creditService;
    private final LoanProductRepository loanProductRepository;
    private final LoanContractRepository loanContractRepository;
    private final ReferenceRateRepository referenceRateRepository;
    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final StatementService statementService;

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
        String indexName = readRequiredString(body, "indexName");
        BigDecimal rateValue = readRequiredBigDecimal(body, "rateValue");
        LocalDate effectiveDate = readOptionalDate(body, "effectiveDate", LocalDate.now());
        String source = readOptionalString(body, "source", "BCT");

        ReferenceRate rate = ReferenceRate.builder()
                .indexName(indexName)
                .rateValue(rateValue)
                .effectiveDate(effectiveDate)
                .source(source)
                .build();

        validateReferenceRate(rate);
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

    @PutMapping("/reference-rates/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update a reference rate (Admin only)")
    public ResponseEntity<Map<String, Object>> updateRate(@PathVariable UUID id, @RequestBody ReferenceRate request) {
        ReferenceRate existing = referenceRateRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Taux de référence introuvable: " + id));

        String oldIndexName = existing.getIndexName();

        if (request.getIndexName() != null) existing.setIndexName(request.getIndexName());
        if (request.getRateValue() != null) existing.setRateValue(request.getRateValue());
        if (request.getEffectiveDate() != null) existing.setEffectiveDate(request.getEffectiveDate());
        if (request.getSource() != null) existing.setSource(request.getSource());

        validateReferenceRate(existing);
        referenceRateRepository.save(existing);

        int revisedOld = loanEngineService.reviseVariableRateLoans(oldIndexName);
        int revisedNew = oldIndexName.equals(existing.getIndexName())
                ? revisedOld
                : loanEngineService.reviseVariableRateLoans(existing.getIndexName());

        return ResponseEntity.ok(Map.of(
                "message", "Taux de référence mis à jour avec succès",
                "id", existing.getId(),
                "indexName", existing.getIndexName(),
                "rateValue", existing.getRateValue(),
                "effectiveDate", existing.getEffectiveDate(),
                "loansRevised", Math.max(revisedOld, revisedNew)
        ));
    }

    @DeleteMapping("/reference-rates/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete a reference rate (Admin only)")
    public ResponseEntity<Map<String, Object>> deleteRate(@PathVariable UUID id) {
        ReferenceRate rate = referenceRateRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Taux de référence introuvable: " + id));

        long ratesForIndex = referenceRateRepository.countByIndexName(rate.getIndexName());
        boolean hasActiveVariableLoans = !loanContractRepository.findActiveVariableRateLoans(rate.getIndexName()).isEmpty();

        if (ratesForIndex <= 1 && hasActiveVariableLoans) {
            throw new BankingException("Suppression refusée: ce taux est le dernier pour l'indice "
                    + rate.getIndexName() + " et des prêts variables actifs en dépendent");
        }

        referenceRateRepository.delete(rate);
        int revised = loanEngineService.reviseVariableRateLoans(rate.getIndexName());

        return ResponseEntity.ok(Map.of(
                "message", "Taux de référence supprimé avec succès",
                "deletedId", id,
                "indexName", rate.getIndexName(),
                "loansRevised", revised
        ));
    }

    // ════════════════════════════════════════════════════════════
    // LOAN SIMULATION
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
    // GAP-F: LOAN APPLICATION (Bridge Simulation → Credit Request)
    // ════════════════════════════════════════════════════════════

    @PostMapping("/apply")
    @Operation(summary = "Apply for a loan from simulation — creates a credit request",
            description = "Bridges the loan simulation to the credit application flow. " +
                          "Uses the LoanProduct's real rate (not hardcoded) to create a CreditRequest " +
                          "that will go through the standard approval workflow.")
    public ResponseEntity<CreditResponse> applyForLoan(
            Authentication auth,
            @Valid @RequestBody LoanApplicationRequest request) {

        // Validate the product exists and is active
        LoanProduct product = loanProductRepository.findByCode(request.getProductCode())
                .orElseThrow(() -> new NotFoundException("Produit introuvable: " + request.getProductCode()));

        if (!Boolean.TRUE.equals(product.getIsActive())) {
            throw new BankingException("Ce produit de prêt n'est plus actif");
        }

        // Validate against product limits
        if (product.getMinAmount() != null && request.getAmount().compareTo(product.getMinAmount()) < 0)
            throw new BankingException("Montant minimum: " + product.getMinAmount() + " TND");
        if (product.getMaxAmount() != null && request.getAmount().compareTo(product.getMaxAmount()) > 0)
            throw new BankingException("Montant maximum: " + product.getMaxAmount() + " TND");
        if (product.getMinDurationMonths() != null && request.getDurationMonths() < product.getMinDurationMonths())
            throw new BankingException("Durée minimum: " + product.getMinDurationMonths() + " mois");
        if (product.getMaxDurationMonths() != null && request.getDurationMonths() > product.getMaxDurationMonths())
            throw new BankingException("Durée maximum: " + product.getMaxDurationMonths() + " mois");

        // Convert to CreditSimulationRequest and submit through CreditService
        CreditSimulationRequest creditRequest = new CreditSimulationRequest();
        creditRequest.setCreditType(product.getCreditType());
        creditRequest.setAmountRequested(request.getAmount());
        creditRequest.setDurationMonths(request.getDurationMonths());
        creditRequest.setPurpose(request.getPurpose() != null ? request.getPurpose()
                : "Demande via simulation produit " + product.getName());

        CreditResponse response = creditService.submit(auth.getName(), creditRequest);
        return ResponseEntity.ok(response);
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
        LoanContract loan = requireLoanAccess(auth, id);
        return ResponseEntity.ok(toLoanResponse(loan));
    }

    @GetMapping("/contracts/by-credit/{creditRequestId}")
    @Operation(summary = "Get loan contract by credit request ID")
    public ResponseEntity<LoanContractResponse> getLoanByCreditRequest(
            Authentication auth,
            @PathVariable UUID creditRequestId) {
        LoanContract loan = loanContractRepository.findByCreditRequestId(creditRequestId)
                .orElseThrow(() -> new NotFoundException("Aucun contrat de pret lie a cette demande de credit"));
        requireLoanAccess(auth, loan.getId());
        return ResponseEntity.ok(toLoanResponse(loan));
    }

    @GetMapping("/contracts/{id}/schedule")
    @Operation(summary = "Get full amortization schedule (échéancier)")
    public ResponseEntity<List<AmortizationLineResponse>> getSchedule(Authentication auth, @PathVariable UUID id) {
        requireLoanAccess(auth, id);
        List<AmortizationSchedule> lines = loanEngineService.getAmortizationSchedule(id);
        return ResponseEntity.ok(lines.stream().map(this::toLineResponse).toList());
    }

    @GetMapping("/contracts/{id}/payments")
    @Operation(summary = "Get payment history for a loan")
    public ResponseEntity<List<LoanPaymentResponse>> getPayments(Authentication auth, @PathVariable UUID id) {
        requireLoanAccess(auth, id);
        List<LoanPaymentResponse> payments = loanEngineService.getLoanPayments(id)
                .stream()
                .map(this::toPaymentResponse)
                .toList();
        return ResponseEntity.ok(payments);
    }

    @GetMapping("/contracts/{id}/rate-revisions")
    @Operation(summary = "Get rate revision history for a variable-rate loan")
    public ResponseEntity<List<RateRevision>> getRevisions(Authentication auth, @PathVariable UUID id) {
        requireLoanAccess(auth, id);
        return ResponseEntity.ok(loanEngineService.getRateRevisions(id));
    }

    @PostMapping("/contracts/{id}/pay")
    @Operation(summary = "Make a payment on a loan")
    public ResponseEntity<Map<String, Object>> makePayment(
            Authentication auth,
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body) {
        requireLoanAccess(auth, id);
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
    public ResponseEntity<Map<String, Object>> simulateEarlyRepayment(Authentication auth, @PathVariable UUID id) {
        requireLoanAccess(auth, id);
        return ResponseEntity.ok(loanEngineService.simulateEarlyRepayment(id));
    }

    @PostMapping("/contracts/{id}/early-repayment/execute")
    @Operation(summary = "Execute early repayment — closes the loan",
            description = "Debits the full amount from the linked account and marks the loan as PAID_OFF.")
    public ResponseEntity<Map<String, Object>> executeEarlyRepayment(
            Authentication auth,
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, Object> body) {
        requireLoanAccess(auth, id);
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
    // GAP-H: PAYMENT RECEIPT
    // ════════════════════════════════════════════════════════════

    @GetMapping(value = "/payments/{paymentId}/receipt", produces = org.springframework.http.MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "Get payment receipt (proof of payment)",
            description = "Returns a detailed PDF receipt for a specific loan payment. " +
                          "Includes borrower info, contract details, payment breakdown, and outstanding balance.")
    public ResponseEntity<byte[]> getPaymentReceipt(
            Authentication auth,
            @PathVariable UUID paymentId) {

        LoanPayment payment = loanEngineService.getPaymentById(paymentId);
        LoanContract loan = payment.getLoanContract();

        // Verify the user owns this loan
        User user = userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable"));
        if (!loan.getUser().getId().equals(user.getId())
                && user.getUserType() != User.UserType.ADMIN
                && user.getUserType() != User.UserType.AGENT) {
            throw new BankingException.ForbiddenException("Ce paiement ne vous appartient pas");
        }

        byte[] pdfBytes = statementService.generateLoanReceiptPdf(payment);
        
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"recu_paiement_" + paymentId + ".pdf\"")
                .body(pdfBytes);
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
        if (loanProductRepository.existsByName(product.getName())) {
            throw new BankingException("Un produit avec le nom " + product.getName() + " existe déjà");
        }

        validateProductConfig(product);
        loanProductRepository.save(product);
        return ResponseEntity.ok(toProductResponse(product, null, null));
    }

    @PutMapping("/products/{code}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update a loan product by code (Admin only)")
    public ResponseEntity<LoanProductResponse> updateProduct(@PathVariable String code, @RequestBody LoanProduct request) {
        LoanProduct existing = loanProductRepository.findByCode(code)
                .orElseThrow(() -> new NotFoundException("Produit introuvable: " + code));

        if (request.getCode() != null && !request.getCode().equals(code)) {
            throw new BankingException("Le code produit est immuable. Utilisez le même code que l'URL.");
        }

        if (request.getName() != null
                && !request.getName().equals(existing.getName())
                && loanProductRepository.existsByNameAndIdNot(request.getName(), existing.getId())) {
            throw new BankingException("Un produit avec le nom " + request.getName() + " existe déjà");
        }

        mergeProduct(existing, request);
        validateProductConfig(existing);
        loanProductRepository.save(existing);

        BigDecimal currentRefRate = null;
        BigDecimal totalRate = null;
        if (existing.getRateType() == LoanProduct.RateType.VARIABLE && existing.getReferenceIndex() != null) {
            currentRefRate = referenceRateRepository
                    .findCurrentRate(existing.getReferenceIndex(), LocalDate.now())
                    .map(ReferenceRate::getRateValue)
                    .orElse(null);
            if (currentRefRate != null) {
                totalRate = currentRefRate.add(existing.getMargin());
            }
        } else if (existing.getFixedRate() != null) {
            totalRate = existing.getFixedRate();
        }

        return ResponseEntity.ok(toProductResponse(existing, currentRefRate, totalRate));
    }

    @DeleteMapping("/products/{code}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete a loan product by code (Admin only)")
    public ResponseEntity<Map<String, Object>> deleteProduct(@PathVariable String code) {
        LoanProduct product = loanProductRepository.findByCode(code)
                .orElseThrow(() -> new NotFoundException("Produit introuvable: " + code));

        if (loanContractRepository.existsByProductId(product.getId())) {
            throw new BankingException("Suppression refusée: ce produit est déjà lié à au moins un contrat de prêt");
        }

        loanProductRepository.delete(product);
        return ResponseEntity.ok(Map.of(
                "message", "Produit supprimé avec succès",
                "code", code
        ));
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
                .creditRequestId(loan.getCreditRequest() != null ? loan.getCreditRequest().getId() : null)
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

    private LoanPaymentResponse toPaymentResponse(LoanPayment payment) {
        return LoanPaymentResponse.builder()
                .id(payment.getId())
                .paymentDate(payment.getPaymentDate())
                .totalPaid(payment.getTotalPaid())
                .principalPaid(payment.getPrincipalPaid())
                .interestPaid(payment.getInterestPaid())
                .penaltyPaid(payment.getPenaltyPaid())
                .outstandingAfter(payment.getOutstandingAfter())
                .paymentType(payment.getPaymentType() != null ? payment.getPaymentType().name() : null)
                .createdAt(payment.getCreatedAt())
                .build();
    }

    private LoanContract requireLoanAccess(Authentication auth, UUID loanId) {
        LoanContract loan = loanContractRepository.findById(loanId)
                .orElseThrow(() -> new NotFoundException("Contrat introuvable"));

        User actor = userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new NotFoundException("Utilisateur introuvable"));

        boolean isOwner = loan.getUser().getId().equals(actor.getId());
        boolean isStaff = actor.getUserType() == User.UserType.ADMIN || actor.getUserType() == User.UserType.AGENT;
        if (!isOwner && !isStaff) {
            throw new BankingException.ForbiddenException("Acces refuse a ce contrat de pret");
        }

        if (actor.getUserType() == User.UserType.AGENT) {
            UUID agentAgencyId = actor.getAgency() != null ? actor.getAgency().getId() : null;
            UUID borrowerAgencyId = loan.getUser().getAgency() != null ? loan.getUser().getAgency().getId() : null;
            if (agentAgencyId == null || borrowerAgencyId == null || !agentAgencyId.equals(borrowerAgencyId)) {
                throw new BankingException.ForbiddenException("Acces agence refuse pour ce contrat de pret");
            }
        }

        return loan;
    }

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

    private void validateReferenceRate(ReferenceRate rate) {
        if (rate.getIndexName() == null || rate.getIndexName().isBlank()) {
            throw new BankingException("indexName est obligatoire");
        }
        if (rate.getRateValue() == null) {
            throw new BankingException("rateValue est obligatoire");
        }
        if (rate.getRateValue().compareTo(BigDecimal.ZERO) < 0) {
            throw new BankingException("rateValue doit être positif");
        }
        if (rate.getEffectiveDate() == null) {
            throw new BankingException("effectiveDate est obligatoire");
        }
    }

    private void mergeProduct(LoanProduct target, LoanProduct source) {
        if (source.getName() != null) target.setName(source.getName());
        if (source.getCreditType() != null) target.setCreditType(source.getCreditType());
        if (source.getRateType() != null) target.setRateType(source.getRateType());
        if (source.getReferenceIndex() != null) target.setReferenceIndex(source.getReferenceIndex());
        if (source.getMargin() != null) target.setMargin(source.getMargin());
        if (source.getFixedRate() != null) target.setFixedRate(source.getFixedRate());
        if (source.getFloorRate() != null) target.setFloorRate(source.getFloorRate());
        if (source.getCeilingRate() != null) target.setCeilingRate(source.getCeilingRate());
        if (source.getDayCountConvention() != null) target.setDayCountConvention(source.getDayCountConvention());
        if (source.getRepaymentFrequency() != null) target.setRepaymentFrequency(source.getRepaymentFrequency());
        if (source.getInterestMethod() != null) target.setInterestMethod(source.getInterestMethod());
        if (source.getMinAmount() != null) target.setMinAmount(source.getMinAmount());
        if (source.getMaxAmount() != null) target.setMaxAmount(source.getMaxAmount());
        if (source.getMinDurationMonths() != null) target.setMinDurationMonths(source.getMinDurationMonths());
        if (source.getMaxDurationMonths() != null) target.setMaxDurationMonths(source.getMaxDurationMonths());
        if (source.getMaxGracePeriodMonths() != null) target.setMaxGracePeriodMonths(source.getMaxGracePeriodMonths());
        if (source.getPenaltyMargin() != null) target.setPenaltyMargin(source.getPenaltyMargin());
        if (source.getIsActive() != null) target.setIsActive(source.getIsActive());
    }

    private void validateProductConfig(LoanProduct product) {
        if (product.getName() == null || product.getName().isBlank()) {
            throw new BankingException("name est obligatoire");
        }
        if (product.getCode() == null || product.getCode().isBlank()) {
            throw new BankingException("code est obligatoire");
        }
        if (product.getCreditType() == null) {
            throw new BankingException("creditType est obligatoire");
        }
        if (product.getRateType() == null) {
            throw new BankingException("rateType est obligatoire");
        }
        if (product.getRateType() == LoanProduct.RateType.FIXED && product.getFixedRate() == null) {
            throw new BankingException("fixedRate est obligatoire pour un produit a taux fixe");
        }
        if (product.getRateType() == LoanProduct.RateType.VARIABLE) {
            if (product.getReferenceIndex() == null || product.getReferenceIndex().isBlank()) {
                throw new BankingException("referenceIndex est obligatoire pour un produit a taux variable");
            }
            if (product.getMargin() == null) {
                throw new BankingException("margin est obligatoire pour un produit a taux variable");
            }
        }
        if (product.getMinAmount() != null && product.getMaxAmount() != null
                && product.getMinAmount().compareTo(product.getMaxAmount()) > 0) {
            throw new BankingException("minAmount doit etre inferieur ou egal a maxAmount");
        }
        if (product.getMinDurationMonths() != null && product.getMaxDurationMonths() != null
                && product.getMinDurationMonths() > product.getMaxDurationMonths()) {
            throw new BankingException("minDurationMonths doit etre inferieur ou egal a maxDurationMonths");
        }
        if (product.getFloorRate() != null && product.getCeilingRate() != null
                && product.getFloorRate().compareTo(product.getCeilingRate()) > 0) {
            throw new BankingException("floorRate doit etre inferieur ou egal a ceilingRate");
        }
    }

    private String readRequiredString(Map<String, Object> body, String key) {
        Object raw = body != null ? body.get(key) : null;
        if (raw == null || raw.toString().isBlank()) {
            throw new BankingException(key + " est obligatoire");
        }
        return raw.toString().trim();
    }

    private String readOptionalString(Map<String, Object> body, String key, String defaultValue) {
        Object raw = body != null ? body.get(key) : null;
        if (raw == null || raw.toString().isBlank()) {
            return defaultValue;
        }
        return raw.toString().trim();
    }

    private BigDecimal readRequiredBigDecimal(Map<String, Object> body, String key) {
        Object raw = body != null ? body.get(key) : null;
        if (raw == null) {
            throw new BankingException(key + " est obligatoire");
        }
        try {
            return new BigDecimal(raw.toString().trim());
        } catch (NumberFormatException ex) {
            throw new BankingException(key + " invalide: format numérique attendu");
        }
    }

    private LocalDate readOptionalDate(Map<String, Object> body, String key, LocalDate defaultValue) {
        Object raw = body != null ? body.get(key) : null;
        if (raw == null || raw.toString().isBlank()) {
            return defaultValue;
        }
        try {
            return LocalDate.parse(raw.toString().trim());
        } catch (java.time.format.DateTimeParseException ex) {
            throw new BankingException(key + " invalide: format attendu yyyy-MM-dd");
        }
    }

}
