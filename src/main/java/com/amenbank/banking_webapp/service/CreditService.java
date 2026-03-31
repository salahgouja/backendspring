package com.amenbank.banking_webapp.service;

import com.amenbank.banking_webapp.dto.request.CreditReviewRequest;
import com.amenbank.banking_webapp.dto.request.CreditSimulationRequest;
import com.amenbank.banking_webapp.dto.response.CreditResponse;
import com.amenbank.banking_webapp.exception.BankingException;
import com.amenbank.banking_webapp.exception.BankingException.ForbiddenException;
import com.amenbank.banking_webapp.exception.BankingException.NotFoundException;
import com.amenbank.banking_webapp.model.*;
import com.amenbank.banking_webapp.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CreditService {

        private static final String USER_NOT_FOUND = "Utilisateur introuvable";
        private static final String CREDIT_NOT_FOUND = "Demande de crédit introuvable";

        private final CreditRequestRepository creditRequestRepository;
        private final NotificationRepository notificationRepository;
        private final UserRepository userRepository;
        private final AccountRepository accountRepository;
        private final TransactionRepository transactionRepository;
        private final LoanProductRepository loanProductRepository;
        private final LoanContractRepository loanContractRepository;
        private final ReferenceRateRepository referenceRateRepository;
        private final AuditService auditService;
        private final LoanEngineService loanEngineService;

        // ── Fallback interest rates by credit type (annual %) ──
        // Used only when no active LoanProduct exists for the credit type
        private static final Map<CreditRequest.CreditType, BigDecimal> FALLBACK_INTEREST_RATES = Map.of(
                        CreditRequest.CreditType.PERSONNEL, new BigDecimal("8.50"),
                        CreditRequest.CreditType.IMMOBILIER, new BigDecimal("5.75"),
                        CreditRequest.CreditType.COMMERCIAL, new BigDecimal("7.00"),
                        CreditRequest.CreditType.EQUIPEMENT, new BigDecimal("6.50"));

        // ── GAP-G: Resolve rate from LoanProduct (real rate) or fallback ──
        private BigDecimal resolveRate(CreditRequest.CreditType creditType) {
                return loanProductRepository.findByCreditTypeAndIsActiveTrue(creditType)
                                .stream()
                                .findFirst()
                                .map(product -> {
                                        if (product.getRateType() == LoanProduct.RateType.VARIABLE
                                                        && product.getReferenceIndex() != null) {
                                                return referenceRateRepository
                                                                .findCurrentRate(product.getReferenceIndex(), LocalDate.now())
                                                                .map(ref -> ref.getRateValue().add(product.getMargin()))
                                                                .orElse(FALLBACK_INTEREST_RATES.get(creditType));
                                        } else if (product.getFixedRate() != null) {
                                                return product.getFixedRate();
                                        }
                                        return FALLBACK_INTEREST_RATES.get(creditType);
                                })
                                .orElse(FALLBACK_INTEREST_RATES.get(creditType));
        }

        // ── Simulate (no persistence) ──────────────────────────
        public CreditResponse simulate(CreditSimulationRequest request) {
                // GAP-G: Use LoanProduct rate (same rate the actual loan will use)
                BigDecimal rate = resolveRate(request.getCreditType());
                BigDecimal monthlyPayment = calculateMonthlyPayment(
                                request.getAmountRequested(), rate, request.getDurationMonths());

                BigDecimal totalCost = monthlyPayment.multiply(BigDecimal.valueOf(request.getDurationMonths()));
                BigDecimal totalInterest = totalCost.subtract(request.getAmountRequested());

                return CreditResponse.builder()
                                .creditType(request.getCreditType().name())
                                .amountRequested(request.getAmountRequested())
                                .durationMonths(request.getDurationMonths())
                                .interestRate(rate)
                                .monthlyPayment(monthlyPayment)
                                .totalCost(totalCost.setScale(3, RoundingMode.HALF_UP))
                                .totalInterest(totalInterest.setScale(3, RoundingMode.HALF_UP))
                                .purpose(request.getPurpose())
                                .status(CreditRequest.CreditStatus.SIMULATION.name())
                                .build();
        }

        // ── Submit (persist) ───────────────────────────────────
        @Transactional
        public CreditResponse submit(String userEmail, CreditSimulationRequest request) {
                User user = userRepository.findByEmail(userEmail)
                                .orElseThrow(() -> new NotFoundException(USER_NOT_FOUND));

                // GAP-G: Use LoanProduct rate (consistent with what the actual loan will use)
                BigDecimal rate = resolveRate(request.getCreditType());
                BigDecimal monthlyPayment = calculateMonthlyPayment(
                                request.getAmountRequested(), rate, request.getDurationMonths());

                CreditRequest credit = CreditRequest.builder()
                                .user(user)
                                .creditType(request.getCreditType())
                                .amountRequested(request.getAmountRequested())
                                .durationMonths(request.getDurationMonths())
                                .interestRate(rate)
                                .monthlyPayment(monthlyPayment)
                                .purpose(request.getPurpose() != null ? sanitize(request.getPurpose()) : null)
                                .status(CreditRequest.CreditStatus.SUBMITTED)
                                .build();
                creditRequestRepository.save(credit);

                // Notify
                notificationRepository.save(Notification.builder()
                                .user(user)
                                .type(Notification.NotificationType.CREDIT)
                                .title("Demande de crédit soumise")
                                .body(String.format(
                                                "Votre demande de crédit %s de %.3f TND sur %d mois a été soumise. Mensualité estimée: %.3f TND.",
                                                request.getCreditType().name(), request.getAmountRequested(),
                                                request.getDurationMonths(), monthlyPayment))
                                .build());

                auditService.log(AuditLog.AuditAction.CREDIT_SUBMITTED, userEmail,
                        "CreditRequest", credit.getId().toString(),
                        credit.getCreditType() + " " + credit.getAmountRequested() + " TND");

                return toResponse(credit);
        }

        // ── Cancel credit (fix #20) ───────────────────────────
        @Transactional
        public CreditResponse cancelCredit(String userEmail, UUID creditId) {
                User user = userRepository.findByEmail(userEmail)
                                .orElseThrow(() -> new NotFoundException(USER_NOT_FOUND));
                CreditRequest credit = creditRequestRepository.findById(creditId)
                                .orElseThrow(() -> new NotFoundException(CREDIT_NOT_FOUND));
                if (!credit.getUser().getId().equals(user.getId())) {
                        throw new ForbiddenException("Cette demande de crédit ne vous appartient pas");
                }
                if (credit.getStatus() != CreditRequest.CreditStatus.SUBMITTED
                                && credit.getStatus() != CreditRequest.CreditStatus.SIMULATION) {
                        throw new BankingException("Impossible d'annuler (statut actuel: " + credit.getStatus() + ")");
                }
                credit.setStatus(CreditRequest.CreditStatus.CANCELLED);
                credit.setDecidedAt(LocalDateTime.now());
                creditRequestRepository.save(credit);

                notificationRepository.save(Notification.builder()
                                .user(user).type(Notification.NotificationType.CREDIT)
                                .title("Demande de crédit annulée")
                                .body(String.format("Votre demande de crédit %s de %.3f TND a été annulée.",
                                                credit.getCreditType().name(), credit.getAmountRequested()))
                                .build());

                auditService.log(AuditLog.AuditAction.CREDIT_CANCELLED, userEmail,
                        "CreditRequest", credit.getId().toString(), "Cancelled by client");
                return toResponse(credit);
        }

        // ── List user's credits ────────────────────────────────
        @Transactional(readOnly = true)
        public List<CreditResponse> getUserCredits(String userEmail) {
                User user = userRepository.findByEmail(userEmail)
                                .orElseThrow(() -> new NotFoundException(USER_NOT_FOUND));

                return creditRequestRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                                .map(this::toResponse)
                                .toList();
        }

        // ── List pending credits (AGENT = same agency, ADMIN = all) ──
        @Transactional(readOnly = true)
        public List<CreditResponse> getPendingCredits(String agentEmail) {
                User agent = userRepository.findByEmail(agentEmail)
                                .orElseThrow(() -> new NotFoundException(USER_NOT_FOUND));

                List<CreditRequest> pending;
                if (agent.getUserType() == User.UserType.ADMIN) {
                        // Admin sees ALL submitted credits
                        pending = creditRequestRepository.findPendingWithUser(
                                        CreditRequest.CreditStatus.SUBMITTED);
                } else {
                        // Agent sees only credits from their agency
                        // Force initialize the lazy agency proxy
                        Agency agentAgency = agent.getAgency();
                        if (agentAgency == null) {
                                return List.of();
                        }
                        UUID agencyId = agentAgency.getId();
                        pending = creditRequestRepository.findPendingByAgency(
                                        CreditRequest.CreditStatus.SUBMITTED, agencyId);
                }

                return pending.stream().map(this::toResponse).toList();
        }

        // ── Single credit detail ───────────────────────────────
        @Transactional(readOnly = true)
        public CreditResponse getCreditById(String userEmail, UUID creditId) {
                User user = userRepository.findByEmail(userEmail)
                                .orElseThrow(() -> new NotFoundException(USER_NOT_FOUND));

                CreditRequest credit = creditRequestRepository.findById(creditId)
                                .orElseThrow(() -> new NotFoundException(CREDIT_NOT_FOUND));

                if (!credit.getUser().getId().equals(user.getId())) {
                        throw new ForbiddenException("Cette demande de crédit ne vous appartient pas");
                }

                return toResponse(credit);
        }

        // ── Review credit (AGENT / ADMIN) ─────────────────────
        @Transactional
        public CreditResponse reviewCredit(UUID creditId, CreditReviewRequest request, String agentEmail) {
                CreditRequest credit = creditRequestRepository.findById(creditId)
                                .orElseThrow(() -> new NotFoundException(CREDIT_NOT_FOUND));

                // Only SUBMITTED or IN_REVIEW credits can be reviewed
                if (credit.getStatus() != CreditRequest.CreditStatus.SUBMITTED
                                && credit.getStatus() != CreditRequest.CreditStatus.IN_REVIEW) {
                        throw new BankingException(
                                        "Impossible de traiter cette demande (statut actuel: " + credit.getStatus()
                                                        + ")");
                }

                // Only APPROVED or REJECTED are valid decisions
                if (request.getDecision() != CreditRequest.CreditStatus.APPROVED
                                && request.getDecision() != CreditRequest.CreditStatus.REJECTED) {
                        throw new BankingException("La décision doit être APPROVED ou REJECTED");
                }

                credit.setStatus(request.getDecision());
                credit.setDecidedAt(LocalDateTime.now());

                if (request.getAiRiskScore() != null) {
                        credit.setAiRiskScore(request.getAiRiskScore());
                }

                creditRequestRepository.save(credit);

                // Notify client
                String statusLabel = request.getDecision() == CreditRequest.CreditStatus.APPROVED
                                ? "approuvée"
                                : "refusée";
                String notifBody = String.format(
                                "Votre demande de crédit %s de %.3f TND a été %s.",
                                credit.getCreditType().name(), credit.getAmountRequested(), statusLabel);
                if (request.getComment() != null && !request.getComment().isBlank()) {
                        notifBody += " Motif: " + request.getComment();
                }

                notificationRepository.save(Notification.builder()
                                .user(credit.getUser())
                                .type(Notification.NotificationType.CREDIT)
                                .title("Demande de crédit " + statusLabel)
                                .body(notifBody)
                                .build());

                auditService.log(AuditLog.AuditAction.CREDIT_REVIEWED, agentEmail,
                        "CreditRequest", credit.getId().toString(),
                        "Decision: " + request.getDecision());

                return toResponse(credit);
        }

        // ── Disburse credit (ADMIN only) ──────────────────────
        @Transactional
        public CreditResponse disburseCredit(UUID creditId, String adminEmail) {
                CreditRequest credit = creditRequestRepository.findById(creditId)
                                .orElseThrow(() -> new NotFoundException(CREDIT_NOT_FOUND));

                if (credit.getStatus() != CreditRequest.CreditStatus.APPROVED) {
                        throw new BankingException(
                                        "Seules les demandes APPROVED peuvent être décaissées (statut actuel: "
                                                        + credit.getStatus() + ")");
                }

                // Find client's first active account
                User client = credit.getUser();
                List<Account> accounts = accountRepository.findByUserId(client.getId());
                Account targetAccount = accounts.stream()
                                .filter(a -> Boolean.TRUE.equals(a.getIsActive()))
                                .findFirst()
                                .orElseThrow(() -> new NotFoundException(
                                                "Le client n'a aucun compte actif pour le décaissement"));

                // Credit the loan amount
                targetAccount.setBalance(targetAccount.getBalance().add(credit.getAmountRequested()));
                accountRepository.save(targetAccount);

                // Create CREDIT transaction
                Transaction tx = Transaction.builder()
                                .account(targetAccount)
                                .type(Transaction.TransactionType.CREDIT)
                                .amount(credit.getAmountRequested())
                                .balanceAfter(targetAccount.getBalance())
                                .description("Décaissement crédit " + credit.getCreditType().name()
                                                + " #" + credit.getId().toString().substring(0, 8))
                                .category("credit")
                                .build();
                transactionRepository.save(tx);

                // Update credit status
                credit.setStatus(CreditRequest.CreditStatus.DISBURSED);
                creditRequestRepository.save(credit);

                // ── GAP-14: Bridge to Loan Engine — Create LoanContract ──
                try {
                        LoanProduct matchingProduct = loanProductRepository
                                        .findByCreditTypeAndIsActiveTrue(credit.getCreditType())
                                        .stream()
                                        .findFirst()
                                        .orElse(null);

                        if (matchingProduct != null) {
                                LoanContract loan = loanEngineService.createLoan(
                                                client,
                                                matchingProduct,
                                                credit.getAmountRequested(),
                                                credit.getDurationMonths(),
                                                LoanContract.GracePeriodType.NONE,
                                                0,
                                                LocalDate.now(),
                                                targetAccount,
                                                credit);

                                log.info("Loan contract {} created from credit request {}",
                                                loan.getContractNumber(), credit.getId());

                                // GAP-E: Update credit status to REPAYING (loan is now active)
                                credit.setStatus(CreditRequest.CreditStatus.REPAYING);
                                creditRequestRepository.save(credit);
                        } else {
                                log.warn("No active loan product found for credit type {}, skipping loan creation",
                                                credit.getCreditType());
                        }
                } catch (Exception e) {
                        log.error("Failed to create loan contract for credit {}: {}",
                                        credit.getId(), e.getMessage());
                        // Don't fail the disbursement if loan creation fails
                }

                // Notify client
                notificationRepository.save(Notification.builder()
                                .user(client)
                                .type(Notification.NotificationType.CREDIT)
                                .title("Crédit décaissé")
                                .body(String.format(
                                                "Votre crédit %s de %.3f TND a été décaissé sur votre compte %s. Nouveau solde: %.3f TND.",
                                                credit.getCreditType().name(), credit.getAmountRequested(),
                                                targetAccount.getAccountNumber(), targetAccount.getBalance()))
                                .build());

                auditService.log(AuditLog.AuditAction.CREDIT_DISBURSED, adminEmail,
                        "CreditRequest", credit.getId().toString(),
                        "Disbursed " + credit.getAmountRequested() + " TND to " + targetAccount.getAccountNumber());

                return toResponse(credit);
        }

        // ── Annuity formula ────────────────────────────────────
        // M = P × [r(1+r)^n] / [(1+r)^n − 1]
        // where P = principal, r = monthly rate, n = number of months
        private BigDecimal calculateMonthlyPayment(BigDecimal principal, BigDecimal annualRate, int months) {
                if (annualRate.compareTo(BigDecimal.ZERO) == 0) {
                        return principal.divide(BigDecimal.valueOf(months), 3, RoundingMode.HALF_UP);
                }

                MathContext mc = MathContext.DECIMAL128;
                BigDecimal monthlyRate = annualRate.divide(BigDecimal.valueOf(1200), mc); // annual% / 12 / 100

                // (1 + r)^n
                BigDecimal onePlusR = BigDecimal.ONE.add(monthlyRate);
                BigDecimal power = onePlusR.pow(months, mc);

                // r × (1+r)^n
                BigDecimal numerator = monthlyRate.multiply(power, mc);

                // (1+r)^n − 1
                BigDecimal denominator = power.subtract(BigDecimal.ONE, mc);

                // M = P × numerator / denominator
                return principal.multiply(numerator, mc)
                                .divide(denominator, 3, RoundingMode.HALF_UP);
        }

        // ── Mapper ─────────────────────────────────────────────
        private CreditResponse toResponse(CreditRequest c) {
                BigDecimal totalCost = c.getMonthlyPayment().multiply(BigDecimal.valueOf(c.getDurationMonths()));
                BigDecimal totalInterest = totalCost.subtract(c.getAmountRequested());

                User requester = c.getUser();

                // GAP-C: Lookup linked loan contract
                UUID loanContractId = null;
                String loanContractNumber = null;
                if (c.getId() != null && (c.getStatus() == CreditRequest.CreditStatus.DISBURSED
                                || c.getStatus() == CreditRequest.CreditStatus.REPAYING
                                || c.getStatus() == CreditRequest.CreditStatus.COMPLETED)) {
                        var loanOpt = loanContractRepository.findByCreditRequestId(c.getId());
                        if (loanOpt.isPresent()) {
                                loanContractId = loanOpt.get().getId();
                                loanContractNumber = loanOpt.get().getContractNumber();
                        }
                }

                return CreditResponse.builder()
                                .id(c.getId())
                                .creditType(c.getCreditType().name())
                                .amountRequested(c.getAmountRequested())
                                .durationMonths(c.getDurationMonths())
                                .interestRate(c.getInterestRate())
                                .monthlyPayment(c.getMonthlyPayment())
                                .totalCost(totalCost.setScale(3, RoundingMode.HALF_UP))
                                .totalInterest(totalInterest.setScale(3, RoundingMode.HALF_UP))
                                .status(c.getStatus().name())
                                .purpose(c.getPurpose())
                                .aiRiskScore(c.getAiRiskScore())
                                .createdAt(c.getCreatedAt())
                                .decidedAt(c.getDecidedAt())
                                .userName(requester != null ? requester.getFullNameFr() : null)
                                .userEmail(requester != null ? requester.getEmail() : null)
                                .agencyName(requester != null && requester.getAgency() != null
                                                ? requester.getAgency().getBranchName()
                                                : null)
                                .loanContractId(loanContractId)
                                .loanContractNumber(loanContractNumber)
                                .build();
        }

        /** Sanitize input to prevent XSS — SEC-6 fix */
        private String sanitize(String input) {
                if (input == null) return null;
                return org.springframework.web.util.HtmlUtils.htmlEscape(input);
        }
}
