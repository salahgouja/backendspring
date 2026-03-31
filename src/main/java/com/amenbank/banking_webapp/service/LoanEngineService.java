package com.amenbank.banking_webapp.service;

import com.amenbank.banking_webapp.exception.BankingException;
import com.amenbank.banking_webapp.exception.BankingException.NotFoundException;
import com.amenbank.banking_webapp.model.*;
import com.amenbank.banking_webapp.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ═══════════════════════════════════════════════════════════════
 * LOAN ENGINE — Banking-Grade Interest Calculation Module
 * ═══════════════════════════════════════════════════════════════
  * Implements the full loan lifecycle:
 *   1. Loan creation & amortization schedule generation
 *   2. Daily interest accrual: I = (CRD × Rate × Days) / 36000
 *   3. Installment payment processing
 *   4. Variable rate revision when TMM index changes
 *   5. Penalty interest for overdue installments
 *   6. Grace period handling (total / interest-only)
  * Rate = TMM + Bank Margin  (variable)
 * Rate = fixedRate           (fixed)
  * Day count conventions: 30/360, Actual/360, Actual/365
 * Repayment frequencies: Monthly, Quarterly, Annual
  * Rounding: 3 decimal places (TND millimes), HALF_UP
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoanEngineService {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final int SCALE = 3;    // TND has 3 decimals (millimes)
    private static final int ACCRUAL_SCALE = 6; // internal precision for accruals
    private static final RoundingMode RM = RoundingMode.HALF_UP;

    private final LoanContractRepository loanContractRepository;
    private final LoanProductRepository loanProductRepository;
    private final AmortizationScheduleRepository scheduleRepository;
    private final InterestAccrualRepository accrualRepository;
    private final LoanPaymentRepository paymentRepository;
    private final RateRevisionRepository rateRevisionRepository;
    private final ReferenceRateRepository referenceRateRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final NotificationRepository notificationRepository;
    private final AuditService auditService;

    // ════════════════════════════════════════════════════════════
    // 1. LOAN CREATION & AMORTIZATION SCHEDULE GENERATION
    // ════════════════════════════════════════════════════════════

    /**
     * Creates a loan contract and generates the full amortization schedule.
     *
     * @param user             the borrower
     * @param product          the loan product
     * @param principalAmount  loan amount in TND
     * @param durationMonths   total duration in months
     * @param gracePeriodType  NONE, TOTAL, or INTEREST_ONLY
     * @param gracePeriodMonths number of grace months
     * @param disbursementDate date of disbursement
     * @param account          account to credit and later debit
     * @param creditRequest    optional link to original credit request
     * @return the created loan contract with schedule
     */
    @Transactional
    public LoanContract createLoan(User user, LoanProduct product, BigDecimal principalAmount,
                                    int durationMonths, LoanContract.GracePeriodType gracePeriodType,
                                    int gracePeriodMonths, LocalDate disbursementDate,
                                    Account account, CreditRequest creditRequest) {

        log.info("Creating loan: {} {} TND, {} months, product={}",
                user.getEmail(), principalAmount, durationMonths, product.getCode());

        // 1. Determine the loan rate
        BigDecimal referenceRateValue = BigDecimal.ZERO;
        BigDecimal loanRate;

        if (product.getRateType() == LoanProduct.RateType.VARIABLE) {
            ReferenceRate refRate = referenceRateRepository
                    .findCurrentRate(product.getReferenceIndex(), disbursementDate)
                    .orElseThrow(() -> new NotFoundException(
                            "Taux de référence introuvable pour l'indice: " + product.getReferenceIndex()));
            referenceRateValue = refRate.getRateValue();
            loanRate = referenceRateValue.add(product.getMargin());
        } else {
            loanRate = product.getFixedRate();
        }

        // Apply floor / ceiling
        loanRate = applyFloorCeiling(loanRate, product);

        // 2. Calculate installment amount
        int totalInstallments = calculateTotalInstallments(durationMonths, product.getRepaymentFrequency());
        int activeInstallments = totalInstallments - graceInstallments(gracePeriodMonths, product.getRepaymentFrequency());

        BigDecimal installmentAmount = calculateInstallment(
                principalAmount, loanRate, activeInstallments, product);

        // 3. Calculate dates
        LocalDate firstInstallmentDate = calculateFirstInstallmentDate(
                disbursementDate, gracePeriodMonths, product.getRepaymentFrequency());
        LocalDate maturityDate = calculateMaturityDate(firstInstallmentDate, activeInstallments, product.getRepaymentFrequency());

        // 4. Generate contract number
        String contractNumber = generateContractNumber();

        // 5. Create contract
        LoanContract contract = LoanContract.builder()
                .contractNumber(contractNumber)
                .user(user)
                .product(product)
                .creditRequest(creditRequest)
                .account(account)
                .principalAmount(principalAmount)
                .outstandingPrincipal(principalAmount)
                .currentRate(loanRate)
                .referenceRateValue(referenceRateValue)
                .margin(product.getMargin())
                .totalInstallments(totalInstallments)
                .installmentAmount(installmentAmount)
                .disbursementDate(disbursementDate)
                .firstInstallmentDate(firstInstallmentDate)
                .maturityDate(maturityDate)
                .lastAccrualDate(disbursementDate)
                .gracePeriodType(gracePeriodType)
                .gracePeriodMonths(gracePeriodMonths)
                .build();
        loanContractRepository.save(contract);

        // 6. Generate amortization schedule
        List<AmortizationSchedule> schedule = generateAmortizationSchedule(
                contract, principalAmount, loanRate, totalInstallments,
                gracePeriodType, gracePeriodMonths, product);
        scheduleRepository.saveAll(schedule);

        log.info("Loan {} created: {} TND at {}% for {} installments",
                contractNumber, principalAmount, loanRate, totalInstallments);

        auditService.log(AuditLog.AuditAction.CREDIT_DISBURSED, user.getEmail(),
                "LoanContract", contract.getId().toString(),
                String.format("Loan %s created: %.3f TND at %.4f%% (%s)",
                        contractNumber, principalAmount, loanRate, product.getRateType()));

        return contract;
    }

    /**
     * Generates the full amortization table (échéancier).
     * Uses the banking interest formula: I = (CRD × Rate × Days) / (yearBasis × 100)
     */
    private List<AmortizationSchedule> generateAmortizationSchedule(
            LoanContract contract, BigDecimal principal, BigDecimal annualRate,
            int totalInstallments, LoanContract.GracePeriodType graceType,
            int graceMonths, LoanProduct product) {

        List<AmortizationSchedule> schedule = new ArrayList<>();
        BigDecimal crd = principal; // Capital Restant Dû
        int yearBasis = product.yearBasis();
        BigDecimal yearBasisDec = BigDecimal.valueOf(yearBasis);
        BigDecimal hundred = BigDecimal.valueOf(100);
        int graceInstallmentCount = graceInstallments(graceMonths, product.getRepaymentFrequency());

        // Compute the constant installment for the active (non-grace) period
        int activeInstallments = totalInstallments - graceInstallmentCount;
        BigDecimal constantInstallment = calculateInstallment(principal, annualRate, activeInstallments, product);

        LocalDate periodStart = contract.getDisbursementDate();

        for (int i = 1; i <= totalInstallments; i++) {
            LocalDate dueDate = addPeriod(contract.getDisbursementDate(), i, product.getRepaymentFrequency());
            int daysInPeriod = calculateDaysInPeriod(periodStart, dueDate, product.getDayCountConvention());

            // I = (CRD × Rate × Days) / (yearBasis × 100)
            BigDecimal interest = crd.multiply(annualRate, MC)
                    .multiply(BigDecimal.valueOf(daysInPeriod), MC)
                    .divide(yearBasisDec.multiply(hundred, MC), ACCRUAL_SCALE, RM);

            BigDecimal installmentAmt;
            BigDecimal principalPortion;
            boolean isGrace = i <= graceInstallmentCount;

            if (isGrace) {
                if (graceType == LoanContract.GracePeriodType.TOTAL) {
                    // Total grace: no payment, interest capitalised
                    installmentAmt = BigDecimal.ZERO;
                    principalPortion = BigDecimal.ZERO;
                    // Capitalize interest into CRD
                    crd = crd.add(interest);
                } else {
                    // Interest-only: pay interest, no principal reduction
                    installmentAmt = interest.setScale(SCALE, RM);
                    principalPortion = BigDecimal.ZERO;
                }
            } else if (i == totalInstallments) {
                // Last installment: close out rounding difference
                principalPortion = crd;
                installmentAmt = principalPortion.add(interest).setScale(SCALE, RM);
            } else {
                installmentAmt = constantInstallment;
                principalPortion = installmentAmt.subtract(interest.setScale(SCALE, RM));
                // Guard against negative principal (rare rounding edge case)
                if (principalPortion.compareTo(BigDecimal.ZERO) < 0) {
                    principalPortion = BigDecimal.ZERO;
                    installmentAmt = interest.setScale(SCALE, RM);
                }
            }

            BigDecimal openingBalance = crd;
            BigDecimal closingBalance = crd.subtract(principalPortion).setScale(SCALE, RM);

            AmortizationSchedule line = AmortizationSchedule.builder()
                    .loanContract(contract)
                    .installmentNumber(i)
                    .dueDate(dueDate)
                    .periodStart(periodStart)
                    .periodEnd(dueDate)
                    .daysInPeriod(daysInPeriod)
                    .installmentAmount(installmentAmt.setScale(SCALE, RM))
                    .principalAmount(principalPortion.setScale(SCALE, RM))
                    .interestAmount(interest.setScale(SCALE, RM))
                    .openingBalance(openingBalance.setScale(SCALE, RM))
                    .closingBalance(closingBalance.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : closingBalance)
                    .rateApplied(annualRate)
                    .status(isGrace ? AmortizationSchedule.ScheduleStatus.GRACE
                                    : AmortizationSchedule.ScheduleStatus.PENDING)
                    .build();
            schedule.add(line);

            crd = closingBalance.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : closingBalance;
            periodStart = dueDate;
        }

        return schedule;
    }

    // ════════════════════════════════════════════════════════════
    // 2. DAILY INTEREST ACCRUAL (Scheduled Job)
    // ════════════════════════════════════════════════════════════

    /**
     * Runs daily at 01:00 AM. Accrues interest for all active loans.
     * Interest = (CRD × Rate × 1 day) / (yearBasis × 100)
     */
    @Scheduled(cron = "0 0 1 * * *")
    @Transactional
    public void dailyInterestAccrual() {
        LocalDate today = LocalDate.now();
        List<LoanContract> activeLoans = loanContractRepository.findAllActiveLoans();
        int accrued = 0;

        for (LoanContract loan : activeLoans) {
            // Skip if already accrued today
            if (accrualRepository.existsByLoanContractIdAndAccrualDate(loan.getId(), today)) {
                continue;
            }

            LoanProduct product = loan.getProduct();
            int yearBasis = product.yearBasis();
            BigDecimal rate = loan.getCurrentRate();
            BigDecimal crd = loan.getOutstandingPrincipal();

            if (crd.compareTo(BigDecimal.ZERO) <= 0) continue;

            // I = (CRD × Rate × 1) / (yearBasis × 100)
            BigDecimal dailyInterest = crd.multiply(rate, MC)
                    .divide(BigDecimal.valueOf((long) yearBasis * 100), ACCRUAL_SCALE, RM);

            InterestAccrual accrual = InterestAccrual.builder()
                    .loanContract(loan)
                    .accrualDate(today)
                    .days(1)
                    .outstandingPrincipal(crd)
                    .rateApplied(rate)
                    .interestAmount(dailyInterest)
                    .isPenalty(false)
                    .yearBasis(yearBasis)
                    .build();
            accrualRepository.save(accrual);

            // Update loan's accrued interest
            loan.setAccruedInterest(loan.getAccruedInterest().add(dailyInterest));
            loan.setLastAccrualDate(today);

            // Check for overdue installments and apply penalty
            accrueOverduePenalty(loan, today);

            loanContractRepository.save(loan);
            accrued++;
        }

        if (accrued > 0) {
            log.info("Daily accrual completed: {} loans processed on {}", accrued, today);
        }
    }

    /**
     * Penalty accrual for overdue installments.
     * Penalty rate = loan rate + product penalty margin
     */
    private void accrueOverduePenalty(LoanContract loan, LocalDate today) {
        List<AmortizationSchedule> overdueLines = scheduleRepository.findOverdueByLoanId(loan.getId());
        if (overdueLines.isEmpty()) return;

        LoanProduct product = loan.getProduct();
        BigDecimal penaltyRate = loan.getCurrentRate().add(product.getPenaltyMargin());
        int yearBasis = product.yearBasis();

        for (AmortizationSchedule line : overdueLines) {
            // Days overdue
            long daysOverdue = ChronoUnit.DAYS.between(line.getDueDate(), today);
            if (daysOverdue <= 0) continue;

            BigDecimal overdueAmount = line.getInstallmentAmount()
                    .subtract(line.getPaidAmount() != null ? line.getPaidAmount() : BigDecimal.ZERO);
            if (overdueAmount.compareTo(BigDecimal.ZERO) <= 0) continue;

            // Penalty I = (overdueAmount × penaltyRate × 1 day) / (yearBasis × 100)
            BigDecimal penaltyInterest = overdueAmount.multiply(penaltyRate, MC)
                    .divide(BigDecimal.valueOf((long) yearBasis * 100), ACCRUAL_SCALE, RM);

            InterestAccrual penaltyAccrual = InterestAccrual.builder()
                    .loanContract(loan)
                    .accrualDate(today)
                    .days(1)
                    .outstandingPrincipal(overdueAmount)
                    .rateApplied(penaltyRate)
                    .interestAmount(penaltyInterest)
                    .isPenalty(true)
                    .yearBasis(yearBasis)
                    .build();
            accrualRepository.save(penaltyAccrual);

            loan.setTotalPenaltyAccrued(loan.getTotalPenaltyAccrued().add(penaltyInterest));
            line.setPenaltyAmount(line.getPenaltyAmount().add(penaltyInterest.setScale(SCALE, RM)));
            scheduleRepository.save(line);
        }

        // Update loan overdue status
        loan.setDaysOverdue((int) ChronoUnit.DAYS.between(
                overdueLines.get(0).getDueDate(), today));
        if (loan.getDaysOverdue() > 90) {
            loan.setStatus(LoanContract.LoanStatus.DEFAULTED);
        } else {
            loan.setStatus(LoanContract.LoanStatus.OVERDUE);
        }
    }

    /**
     * Marks installments as DUE or OVERDUE. Runs daily at 00:30 AM.
     */
    @Scheduled(cron = "0 30 0 * * *")
    @Transactional
    public void updateInstallmentStatuses() {
        LocalDate today = LocalDate.now();

        // Mark PENDING installments whose due date has arrived as DUE
        List<AmortizationSchedule> nowDue = scheduleRepository.findDueOnOrBefore(today);
        for (AmortizationSchedule line : nowDue) {
            long daysLate = ChronoUnit.DAYS.between(line.getDueDate(), today);
            if (daysLate > 5) { // 5-day grace tolerance
                line.setStatus(AmortizationSchedule.ScheduleStatus.OVERDUE);

                // GAP-16: Notify client about overdue installment
                LoanContract loan = line.getLoanContract();
                notificationRepository.save(Notification.builder()
                        .user(loan.getUser())
                        .type(Notification.NotificationType.CREDIT)
                        .title("Échéance impayée ⚠️")
                        .body(String.format("L'échéance n°%d de votre prêt %s (%.3f TND) est en retard de %d jours. " +
                                        "Des pénalités de retard s'appliquent. Veuillez régulariser votre situation.",
                                line.getInstallmentNumber(), loan.getContractNumber(),
                                line.getInstallmentAmount(), daysLate))
                        .build());
            } else {
                line.setStatus(AmortizationSchedule.ScheduleStatus.DUE);

                // Notify client about upcoming due installment
                if (daysLate == 0) {
                    LoanContract loan = line.getLoanContract();
                    notificationRepository.save(Notification.builder()
                            .user(loan.getUser())
                            .type(Notification.NotificationType.CREDIT)
                            .title("Échéance de prêt due aujourd'hui")
                            .body(String.format("L'échéance n°%d de votre prêt %s de %.3f TND est due aujourd'hui.",
                                    line.getInstallmentNumber(), loan.getContractNumber(),
                                    line.getInstallmentAmount()))
                            .build());
                }
            }
            scheduleRepository.save(line);
        }
    }

    // ════════════════════════════════════════════════════════════
    // 3. INSTALLMENT PAYMENT PROCESSING
    // ════════════════════════════════════════════════════════════

    /**
     * Process a payment against a loan contract.
     * Waterfall: penalty → interest → principal
     * GAP-B FIX: Added balance check before payment
     * GAP-J FIX: Overpayment carries across multiple installments
     */
    @Transactional
    public LoanPayment processPayment(UUID loanId, BigDecimal paymentAmount, LocalDate paymentDate) {
        LoanContract loan = loanContractRepository.findById(loanId)
                .orElseThrow(() -> new NotFoundException("Contrat de prêt introuvable"));

        if (loan.getStatus() == LoanContract.LoanStatus.PAID_OFF) {
            throw new BankingException("Ce prêt est déjà soldé");
        }

        // ── GAP-B: Balance check before payment ──────────────
        Account account = loan.getAccount();
        if (account.getBalance().compareTo(paymentAmount) < 0) {
            throw new BankingException.InsufficientFundsException(
                    String.format("Solde insuffisant. Nécessaire: %.3f TND, Disponible: %.3f TND",
                            paymentAmount, account.getBalance()));
        }

        BigDecimal remaining = paymentAmount;
        BigDecimal totalPrincipalPaid = BigDecimal.ZERO;
        BigDecimal totalInterestPaid = BigDecimal.ZERO;
        BigDecimal totalPenaltyPaid = BigDecimal.ZERO;
        int installmentsPaid = 0;
        AmortizationSchedule lastProcessedLine = null;

        // ── GAP-J: Loop across multiple installments if overpayment ──
        while (remaining.compareTo(BigDecimal.ZERO) > 0) {
            Optional<AmortizationSchedule> nextDueOpt = scheduleRepository.findNextDue(loanId);
            if (nextDueOpt.isEmpty()) break;
            AmortizationSchedule nextDue = nextDueOpt.get();
            lastProcessedLine = nextDue;

            // Step 1: Pay penalty first
            BigDecimal penaltyPaid = BigDecimal.ZERO;
            if (nextDue.getPenaltyAmount() != null && nextDue.getPenaltyAmount().compareTo(BigDecimal.ZERO) > 0) {
                penaltyPaid = remaining.min(nextDue.getPenaltyAmount());
                remaining = remaining.subtract(penaltyPaid);
                nextDue.setPenaltyAmount(nextDue.getPenaltyAmount().subtract(penaltyPaid));
            }

            // Step 2: Pay interest
            BigDecimal interestPaid = remaining.min(nextDue.getInterestAmount());
            remaining = remaining.subtract(interestPaid);

            // Step 3: Pay principal
            BigDecimal principalPaid = remaining.min(nextDue.getPrincipalAmount());
            remaining = remaining.subtract(principalPaid);

            // Update schedule line
            BigDecimal totalPaidOnLine = penaltyPaid.add(interestPaid).add(principalPaid);
            nextDue.setPaidAmount((nextDue.getPaidAmount() != null ? nextDue.getPaidAmount() : BigDecimal.ZERO)
                    .add(totalPaidOnLine));
            nextDue.setPaidDate(paymentDate);

            if (nextDue.getPaidAmount().compareTo(nextDue.getInstallmentAmount()) >= 0) {
                nextDue.setStatus(AmortizationSchedule.ScheduleStatus.PAID);
                installmentsPaid++;
            } else {
                nextDue.setStatus(AmortizationSchedule.ScheduleStatus.PARTIAL);
            }
            scheduleRepository.save(nextDue);

            totalPrincipalPaid = totalPrincipalPaid.add(principalPaid);
            totalInterestPaid = totalInterestPaid.add(interestPaid);
            totalPenaltyPaid = totalPenaltyPaid.add(penaltyPaid);

            // If this line is only partially paid, stop — don't move to next
            if (nextDue.getStatus() == AmortizationSchedule.ScheduleStatus.PARTIAL) {
                break;
            }
        }

        // Update loan contract
        loan.setOutstandingPrincipal(loan.getOutstandingPrincipal().subtract(totalPrincipalPaid));
        loan.setTotalInterestPaid(loan.getTotalInterestPaid().add(totalInterestPaid));
        loan.setAccruedInterest(loan.getAccruedInterest().subtract(totalInterestPaid)
                .max(BigDecimal.ZERO));
        loan.setPaidInstallments(loan.getPaidInstallments() + installmentsPaid);

        // Check if loan is fully paid
        if (loan.getOutstandingPrincipal().compareTo(BigDecimal.ZERO) <= 0) {
            loan.setOutstandingPrincipal(BigDecimal.ZERO);
            loan.setStatus(LoanContract.LoanStatus.PAID_OFF);
            loan.setDaysOverdue(0);
            log.info("Loan {} fully paid off", loan.getContractNumber());

            // ── GAP-E: Sync CreditRequest status to COMPLETED ──
            syncCreditStatusToCompleted(loan);
        } else if (loan.getDaysOverdue() > 0) {
            List<AmortizationSchedule> stillOverdue = scheduleRepository.findOverdueByLoanId(loanId);
            if (stillOverdue.isEmpty()) {
                loan.setStatus(LoanContract.LoanStatus.ACTIVE);
                loan.setDaysOverdue(0);
            }
        }
        loanContractRepository.save(loan);

        // Debit client account
        BigDecimal actuallyPaid = totalPrincipalPaid.add(totalInterestPaid).add(totalPenaltyPaid);
        account.setBalance(account.getBalance().subtract(actuallyPaid));
        accountRepository.save(account);

        // Create transaction record
        transactionRepository.save(Transaction.builder()
                .account(account)
                .type(Transaction.TransactionType.DEBIT)
                .amount(actuallyPaid)
                .balanceAfter(account.getBalance())
                .description(String.format("Paiement prêt %s (%d échéance(s))",
                        loan.getContractNumber(), Math.max(installmentsPaid, 1)))
                .category("loan_payment")
                .build());

        // Record payment
        LoanPayment payment = LoanPayment.builder()
                .loanContract(loan)
                .scheduleEntry(lastProcessedLine)
                .paymentDate(paymentDate)
                .totalPaid(actuallyPaid)
                .principalPaid(totalPrincipalPaid)
                .interestPaid(totalInterestPaid)
                .penaltyPaid(totalPenaltyPaid)
                .outstandingAfter(loan.getOutstandingPrincipal())
                .paymentType(LoanPayment.PaymentType.SCHEDULED)
                .build();
        paymentRepository.save(payment);

        auditService.log(AuditLog.AuditAction.LOAN_PAYMENT, loan.getUser().getEmail(),
                "LoanPayment", payment.getId().toString(),
                String.format("Payment %.3f TND on loan %s (P=%.3f I=%.3f Pen=%.3f) CRD=%.3f [%d installment(s)]",
                        actuallyPaid, loan.getContractNumber(), totalPrincipalPaid, totalInterestPaid,
                        totalPenaltyPaid, loan.getOutstandingPrincipal(), Math.max(installmentsPaid, 1)));

        return payment;
    }

    // ════════════════════════════════════════════════════════════
    // GAP-A: AUTOMATIC PAYMENT COLLECTION (Direct Debit)
    // ════════════════════════════════════════════════════════════

    /**
     * Scheduled job: auto-collect due payments every day at 6:00 AM.
     * For each installment due today or overdue, attempts to debit the linked account.
     * If insufficient funds, marks as OVERDUE and sends notification.
     */
    @Scheduled(cron = "0 0 6 * * *")
    @Transactional
    public void collectDuePayments() {
        LocalDate today = LocalDate.now();
        log.info("Auto-collection: checking for due payments on {}", today);

        List<AmortizationSchedule> dueLines = scheduleRepository.findDueOnOrBefore(today);
        int collected = 0;
        int failed = 0;

        for (AmortizationSchedule line : dueLines) {
            LoanContract loan = line.getLoanContract();
            if (loan.getStatus() == LoanContract.LoanStatus.PAID_OFF ||
                loan.getStatus() == LoanContract.LoanStatus.CANCELLED) {
                continue;
            }

            Account account = loan.getAccount();
            BigDecimal amountDue = line.getInstallmentAmount();

            // Add any penalty
            if (line.getPenaltyAmount() != null && line.getPenaltyAmount().compareTo(BigDecimal.ZERO) > 0) {
                amountDue = amountDue.add(line.getPenaltyAmount());
            }

            if (account.getBalance().compareTo(amountDue) >= 0) {
                // Sufficient funds: auto-debit
                try {
                    processPayment(loan.getId(), amountDue, today);
                    collected++;
                    log.info("Auto-collected {} TND for loan {} installment #{}",
                            amountDue, loan.getContractNumber(), line.getInstallmentNumber());
                } catch (Exception e) {
                    log.error("Auto-collection failed for loan {}: {}",
                            loan.getContractNumber(), e.getMessage());
                    failed++;
                }
            } else {
                // Insufficient funds: mark as OVERDUE
                line.setStatus(AmortizationSchedule.ScheduleStatus.OVERDUE);
                scheduleRepository.save(line);

                long daysLate = java.time.temporal.ChronoUnit.DAYS.between(line.getDueDate(), today);
                loan.setDaysOverdue((int) daysLate);
                loan.setStatus(daysLate > 90 ? LoanContract.LoanStatus.DEFAULTED : LoanContract.LoanStatus.OVERDUE);
                loanContractRepository.save(loan);

                // Notify client
                notificationRepository.save(Notification.builder()
                        .user(loan.getUser())
                        .type(Notification.NotificationType.CREDIT)
                        .title("⚠️ Échéance impayée — Solde insuffisant")
                        .body(String.format(
                                "L'échéance #%d de votre prêt %s (%.3f TND) n'a pas pu être prélevée. " +
                                "Solde disponible: %.3f TND. Veuillez approvisionner votre compte.",
                                line.getInstallmentNumber(), loan.getContractNumber(),
                                amountDue, account.getBalance()))
                        .build());

                // Sync credit status to REPAYING/OVERDUE
                syncCreditStatusToRepaying(loan);

                failed++;
            }
        }

        log.info("Auto-collection completed: {} collected, {} failed/overdue", collected, failed);
    }

    // ════════════════════════════════════════════════════════════
    // GAP-I: PAYMENT REMINDER NOTIFICATIONS (3 days before)
    // ════════════════════════════════════════════════════════════

    /**
     * Scheduled job: send payment reminders 3 days before due date.
     * Runs daily at 9:00 AM.
     */
    @Scheduled(cron = "0 0 9 * * *")
    @Transactional
    public void sendPaymentReminders() {
        LocalDate reminderDate = LocalDate.now().plusDays(3);
        log.info("Sending payment reminders for installments due on {}", reminderDate);

        List<AmortizationSchedule> upcomingDue = scheduleRepository.findDueOnDate(reminderDate);

        for (AmortizationSchedule line : upcomingDue) {
            LoanContract loan = line.getLoanContract();
            if (loan.getStatus() == LoanContract.LoanStatus.PAID_OFF ||
                loan.getStatus() == LoanContract.LoanStatus.CANCELLED) {
                continue;
            }

            Account account = loan.getAccount();
            BigDecimal amountDue = line.getInstallmentAmount();
            boolean sufficientFunds = account.getBalance().compareTo(amountDue) >= 0;

            String body = String.format(
                    "Rappel: l'échéance #%d de votre prêt %s (%.3f TND) est prévue le %s. %s",
                    line.getInstallmentNumber(), loan.getContractNumber(),
                    amountDue, reminderDate,
                    sufficientFunds
                            ? "Votre solde est suffisant pour le prélèvement automatique."
                            : String.format("⚠️ Solde insuffisant (%.3f TND). Veuillez approvisionner votre compte.", account.getBalance()));

            notificationRepository.save(Notification.builder()
                    .user(loan.getUser())
                    .type(Notification.NotificationType.CREDIT)
                    .title("📅 Rappel d'échéance — " + loan.getContractNumber())
                    .body(body)
                    .build());
        }

        log.info("Payment reminders sent: {} notifications", upcomingDue.size());
    }

    // ════════════════════════════════════════════════════════════
    // GAP-E: SYNC CREDIT STATUS WITH LOAN STATUS
    // ════════════════════════════════════════════════════════════

    /**
     * When a loan starts being repaid, update the linked CreditRequest to REPAYING.
     */
    private void syncCreditStatusToRepaying(LoanContract loan) {
        if (loan.getCreditRequest() != null) {
            CreditRequest cr = loan.getCreditRequest();
            if (cr.getStatus() == CreditRequest.CreditStatus.DISBURSED) {
                cr.setStatus(CreditRequest.CreditStatus.REPAYING);
                // Repository save is handled by cascade or in the calling @Transactional
            }
        }
    }

    /**
     * When a loan is fully paid off, update the linked CreditRequest to COMPLETED.
     */
    private void syncCreditStatusToCompleted(LoanContract loan) {
        if (loan.getCreditRequest() != null) {
            CreditRequest cr = loan.getCreditRequest();
            cr.setStatus(CreditRequest.CreditStatus.COMPLETED);
        }
    }

    // ════════════════════════════════════════════════════════════
    // 4. VARIABLE RATE REVISION (When TMM changes)
    // ════════════════════════════════════════════════════════════

    /**
     * Called when a new reference rate (e.g. TMM) is published.
     * Recalculates the loan rate and future installments for all affected loans.
     */
    @Transactional
    public int reviseVariableRateLoans(String indexName) {
        log.info("Starting rate revision for index: {}", indexName);

        ReferenceRate newRefRate = referenceRateRepository
                .findCurrentRate(indexName, LocalDate.now())
                .orElseThrow(() -> new NotFoundException("Aucun taux de référence trouvé pour: " + indexName));

        List<LoanContract> affectedLoans = loanContractRepository.findActiveVariableRateLoans(indexName);
        int revised = 0;

        for (LoanContract loan : affectedLoans) {
            BigDecimal previousRate = loan.getCurrentRate();
            BigDecimal previousRefRate = loan.getReferenceRateValue();

            // Skip if reference rate hasn't actually changed
            if (previousRefRate != null && previousRefRate.compareTo(newRefRate.getRateValue()) == 0) {
                continue;
            }

            // New rate = new TMM + margin
            BigDecimal newLoanRate = newRefRate.getRateValue().add(loan.getMargin());
            newLoanRate = applyFloorCeiling(newLoanRate, loan.getProduct());

            // Recalculate future installments
            int remainingInstallments = loan.getTotalInstallments() - loan.getPaidInstallments();
            BigDecimal newInstallment = calculateInstallment(
                    loan.getOutstandingPrincipal(), newLoanRate, remainingInstallments, loan.getProduct());

            BigDecimal previousInstallment = loan.getInstallmentAmount();

            // Update contract
            loan.setReferenceRateValue(newRefRate.getRateValue());
            loan.setCurrentRate(newLoanRate);
            loan.setInstallmentAmount(newInstallment);
            loanContractRepository.save(loan);

            // Log the revision
            RateRevision revision = RateRevision.builder()
                    .loanContract(loan)
                    .effectiveDate(LocalDate.now())
                    .previousReferenceRate(previousRefRate != null ? previousRefRate : BigDecimal.ZERO)
                    .newReferenceRate(newRefRate.getRateValue())
                    .margin(loan.getMargin())
                    .previousLoanRate(previousRate)
                    .newLoanRate(newLoanRate)
                    .previousInstallment(previousInstallment)
                    .newInstallment(newInstallment)
                    .build();
            rateRevisionRepository.save(revision);

            // Regenerate future schedule lines
            regenerateFutureSchedule(loan, newLoanRate, remainingInstallments, newInstallment);

            // Notify client
            notificationRepository.save(Notification.builder()
                    .user(loan.getUser())
                    .type(Notification.NotificationType.CREDIT)
                    .title("Révision du taux de votre prêt")
                    .body(String.format(
                            "Le taux de votre prêt %s a été révisé de %.4f%% à %.4f%% " +
                            "(nouveau TMM: %.4f%%). Nouvelle mensualité: %.3f TND.",
                            loan.getContractNumber(), previousRate, newLoanRate,
                            newRefRate.getRateValue(), newInstallment))
                    .build());

            revised++;
            log.info("Loan {} revised: rate {}% → {}%, installment {} → {}",
                    loan.getContractNumber(), previousRate, newLoanRate,
                    previousInstallment, newInstallment);
        }

        log.info("Rate revision completed: {} loans revised for index {}", revised, indexName);
        return revised;
    }

    /**
     * Regenerate the remaining (unpaid) amortization schedule after a rate change.
     */
    private void regenerateFutureSchedule(LoanContract loan, BigDecimal newRate,
                                           int remainingInstallments, BigDecimal newInstallment) {
        List<AmortizationSchedule> remaining = scheduleRepository.findRemainingByLoanId(loan.getId());
        if (remaining.isEmpty()) return;

        LoanProduct product = loan.getProduct();
        int yearBasis = product.yearBasis();
        BigDecimal crd = loan.getOutstandingPrincipal();

        for (int i = 0; i < remaining.size(); i++) {
            AmortizationSchedule line = remaining.get(i);
            int daysInPeriod = line.getDaysInPeriod();

            // I = (CRD × Rate × Days) / (yearBasis × 100)
            BigDecimal interest = crd.multiply(newRate, MC)
                    .multiply(BigDecimal.valueOf(daysInPeriod), MC)
                    .divide(BigDecimal.valueOf((long) yearBasis * 100), ACCRUAL_SCALE, RM);

            BigDecimal principalPortion;
            BigDecimal installmentAmt;

            if (i == remaining.size() - 1) {
                // Last installment: close out
                principalPortion = crd;
                installmentAmt = principalPortion.add(interest).setScale(SCALE, RM);
            } else {
                installmentAmt = newInstallment;
                principalPortion = installmentAmt.subtract(interest.setScale(SCALE, RM));
                if (principalPortion.compareTo(BigDecimal.ZERO) < 0) {
                    principalPortion = BigDecimal.ZERO;
                    installmentAmt = interest.setScale(SCALE, RM);
                }
            }

            line.setInstallmentAmount(installmentAmt.setScale(SCALE, RM));
            line.setPrincipalAmount(principalPortion.setScale(SCALE, RM));
            line.setInterestAmount(interest.setScale(SCALE, RM));
            line.setOpeningBalance(crd.setScale(SCALE, RM));
            BigDecimal closing = crd.subtract(principalPortion).setScale(SCALE, RM);
            line.setClosingBalance(closing.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : closing);
            line.setRateApplied(newRate);
            scheduleRepository.save(line);

            crd = line.getClosingBalance();
        }
    }

    // ════════════════════════════════════════════════════════════
    // 5. CALCULATION HELPERS
    // ════════════════════════════════════════════════════════════

    // ── GAP-15: Early Repayment Simulation ────────────────────
    /**
     * Simulate the cost of early repayment (remboursement anticipé).
     * Returns: outstanding principal + accrued interest + early repayment penalty.
     */
    @Transactional(readOnly = true)
    public java.util.Map<String, Object> simulateEarlyRepayment(UUID loanId) {
        LoanContract loan = loanContractRepository.findById(loanId)
                .orElseThrow(() -> new NotFoundException("Contrat de prêt introuvable"));

        if (loan.getStatus() == LoanContract.LoanStatus.PAID_OFF) {
            throw new BankingException("Ce prêt est déjà soldé");
        }

        BigDecimal outstandingPrincipal = loan.getOutstandingPrincipal();
        BigDecimal accruedInterest = loan.getAccruedInterest();
        BigDecimal totalPenalty = loan.getTotalPenaltyAccrued();

        // Early repayment penalty = 2% of outstanding principal (standard Tunisian banking)
        BigDecimal earlyRepaymentPenalty = outstandingPrincipal
                .multiply(new BigDecimal("0.02"))
                .setScale(SCALE, RM);

        BigDecimal totalToPay = outstandingPrincipal
                .add(accruedInterest)
                .add(totalPenalty)
                .add(earlyRepaymentPenalty)
                .setScale(SCALE, RM);

        // Calculate savings (remaining interest that won't be paid)
        List<AmortizationSchedule> remainingSchedule = scheduleRepository.findRemainingByLoanId(loanId);
        BigDecimal remainingInterest = remainingSchedule.stream()
                .map(AmortizationSchedule::getInterestAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal interestSaved = remainingInterest.subtract(accruedInterest).max(BigDecimal.ZERO).setScale(SCALE, RM);

        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("loanContractNumber", loan.getContractNumber());
        result.put("outstandingPrincipal", outstandingPrincipal);
        result.put("accruedInterest", accruedInterest);
        result.put("outstandingPenalties", totalPenalty);
        result.put("earlyRepaymentPenalty", earlyRepaymentPenalty);
        result.put("earlyRepaymentPenaltyRate", "2%");
        result.put("totalToPay", totalToPay);
        result.put("interestSaved", interestSaved);
        result.put("remainingInstallments", loan.getTotalInstallments() - loan.getPaidInstallments());
        result.put("currentRate", loan.getCurrentRate());
        return result;
    }

    // ── GAP-15: Execute Early Repayment ───────────────────────
    @Transactional
    public LoanPayment executeEarlyRepayment(UUID loanId, LocalDate paymentDate) {
        LoanContract loan = loanContractRepository.findById(loanId)
                .orElseThrow(() -> new NotFoundException("Contrat de prêt introuvable"));

        if (loan.getStatus() == LoanContract.LoanStatus.PAID_OFF) {
            throw new BankingException("Ce prêt est déjà soldé");
        }

        BigDecimal outstandingPrincipal = loan.getOutstandingPrincipal();
        BigDecimal accruedInterest = loan.getAccruedInterest();
        BigDecimal totalPenalty = loan.getTotalPenaltyAccrued();
        BigDecimal earlyRepaymentPenalty = outstandingPrincipal
                .multiply(new BigDecimal("0.02"))
                .setScale(SCALE, RM);

        BigDecimal totalToPay = outstandingPrincipal
                .add(accruedInterest)
                .add(totalPenalty)
                .add(earlyRepaymentPenalty)
                .setScale(SCALE, RM);

        // Verify account has sufficient funds
        Account account = loan.getAccount();
        if (account.getBalance().compareTo(totalToPay) < 0) {
            throw new BankingException(String.format(
                    "Solde insuffisant pour le remboursement anticipé. Nécessaire: %.3f TND, Disponible: %.3f TND",
                    totalToPay, account.getBalance()));
        }

        // Debit account
        account.setBalance(account.getBalance().subtract(totalToPay));
        accountRepository.save(account);

        // Create transaction
        transactionRepository.save(Transaction.builder()
                .account(account)
                .type(Transaction.TransactionType.DEBIT)
                .amount(totalToPay)
                .balanceAfter(account.getBalance())
                .description("Remboursement anticipé prêt " + loan.getContractNumber())
                .category("loan_early_repayment")
                .build());

        // Mark all remaining schedule lines as PAID
        List<AmortizationSchedule> remaining = scheduleRepository.findRemainingByLoanId(loanId);
        for (AmortizationSchedule line : remaining) {
            line.setStatus(AmortizationSchedule.ScheduleStatus.PAID);
            line.setPaidDate(paymentDate);
            line.setPaidAmount(line.getInstallmentAmount());
            scheduleRepository.save(line);
        }

        // Close the loan
        loan.setOutstandingPrincipal(BigDecimal.ZERO);
        loan.setAccruedInterest(BigDecimal.ZERO);
        loan.setTotalPenaltyAccrued(BigDecimal.ZERO);
        loan.setStatus(LoanContract.LoanStatus.PAID_OFF);
        loan.setPaidInstallments(loan.getTotalInstallments());
        loan.setDaysOverdue(0);
        loanContractRepository.save(loan);

        // Record payment
        LoanPayment payment = LoanPayment.builder()
                .loanContract(loan)
                .paymentDate(paymentDate)
                .totalPaid(totalToPay)
                .principalPaid(outstandingPrincipal)
                .interestPaid(accruedInterest)
                .penaltyPaid(totalPenalty.add(earlyRepaymentPenalty))
                .outstandingAfter(BigDecimal.ZERO)
                .paymentType(LoanPayment.PaymentType.EARLY)
                .build();
        paymentRepository.save(payment);

        // Notify client
        notificationRepository.save(Notification.builder()
                .user(loan.getUser())
                .type(Notification.NotificationType.CREDIT)
                .title("Remboursement anticipé effectué ✅")
                .body(String.format("Votre prêt %s a été soldé par remboursement anticipé. " +
                                "Montant total payé: %.3f TND (capital: %.3f + intérêts: %.3f + pénalités: %.3f).",
                        loan.getContractNumber(), totalToPay, outstandingPrincipal,
                        accruedInterest, totalPenalty.add(earlyRepaymentPenalty)))
                .build());

        auditService.log(AuditLog.AuditAction.CREDIT_REVIEWED, loan.getUser().getEmail(),
                "LoanContract", loan.getId().toString(),
                "Early repayment: " + totalToPay + " TND — loan closed");

        log.info("Loan {} early repayment: {} TND — PAID OFF", loan.getContractNumber(), totalToPay);
        return payment;
    }

    /**
     * Annuity formula: Installment = C × [i / (1 − (1+i)^(−n))]
     * Where i = periodic rate (annual rate adjusted for frequency and day count)
     */
    public BigDecimal calculateInstallment(BigDecimal capital, BigDecimal annualRate,
                                            int numberOfInstallments, LoanProduct product) {
        if (annualRate.compareTo(BigDecimal.ZERO) == 0) {
            return capital.divide(BigDecimal.valueOf(numberOfInstallments), SCALE, RM);
        }

        // Periodic rate: convert annual % to periodic decimal
        // For Actual/360: i = (rate / 100) × (daysInPeriod / 360)
        // For simplicity in schedule generation, we use: i = rate / (periodsPerYear × 100)
        int periodsPerYear = product.periodsPerYear();
        BigDecimal periodicRate = annualRate.divide(
                BigDecimal.valueOf((long) periodsPerYear * 100), MC);

        // (1 + i)
        BigDecimal onePlusI = BigDecimal.ONE.add(periodicRate);

        // (1 + i)^n
        BigDecimal power = onePlusI.pow(numberOfInstallments, MC);

        // i / (1 − (1+i)^(−n)) = i × (1+i)^n / ((1+i)^n − 1)
        BigDecimal numerator = periodicRate.multiply(power, MC);
        BigDecimal denominator = power.subtract(BigDecimal.ONE, MC);

        // Installment = C × numerator / denominator
        return capital.multiply(numerator, MC)
                .divide(denominator, SCALE, RM);
    }

    /**
     * Calculate days in period based on the day count convention.
     */
    public int calculateDaysInPeriod(LocalDate start, LocalDate end,
                                      LoanProduct.DayCountConvention convention) {
        return switch (convention) {
            case THIRTY_360 -> {
                // 30/360 European: each month = 30 days
                int d1 = Math.min(start.getDayOfMonth(), 30);
                int d2 = Math.min(end.getDayOfMonth(), 30);
                int m1 = start.getMonthValue();
                int m2 = end.getMonthValue();
                int y1 = start.getYear();
                int y2 = end.getYear();
                yield 360 * (y2 - y1) + 30 * (m2 - m1) + (d2 - d1);
            }
            case ACTUAL_360, ACTUAL_365 ->
                (int) ChronoUnit.DAYS.between(start, end);
        };
    }

    /**
     * Apply floor and ceiling constraints to the loan rate.
     */
    private BigDecimal applyFloorCeiling(BigDecimal rate, LoanProduct product) {
        if (product.getFloorRate() != null && rate.compareTo(product.getFloorRate()) < 0) {
            return product.getFloorRate();
        }
        if (product.getCeilingRate() != null && rate.compareTo(product.getCeilingRate()) > 0) {
            return product.getCeilingRate();
        }
        return rate;
    }

    private int calculateTotalInstallments(int durationMonths, LoanProduct.RepaymentFrequency freq) {
        return switch (freq) {
            case MONTHLY -> durationMonths;
            case QUARTERLY -> durationMonths / 3;
            case ANNUAL -> durationMonths / 12;
        };
    }

    private int graceInstallments(int graceMonths, LoanProduct.RepaymentFrequency freq) {
        if (graceMonths <= 0) return 0;
        return switch (freq) {
            case MONTHLY -> graceMonths;
            case QUARTERLY -> graceMonths / 3;
            case ANNUAL -> graceMonths / 12;
        };
    }

    private LocalDate addPeriod(LocalDate base, int periods, LoanProduct.RepaymentFrequency freq) {
        return switch (freq) {
            case MONTHLY -> base.plusMonths(periods);
            case QUARTERLY -> base.plusMonths((long) periods * 3);
            case ANNUAL -> base.plusYears(periods);
        };
    }

    private LocalDate calculateFirstInstallmentDate(LocalDate disbursement, int graceMonths,
                                                     LoanProduct.RepaymentFrequency freq) {
        return addPeriod(disbursement, 1, freq).plusMonths(graceMonths);
    }

    private LocalDate calculateMaturityDate(LocalDate firstInstallment, int activeInstallments,
                                             LoanProduct.RepaymentFrequency freq) {
        return addPeriod(firstInstallment, activeInstallments - 1, freq);
    }

    private String generateContractNumber() {
        String number;
        int attempts = 0;
        do {
            number = "LOAN-" + String.format("%010d",
                    Math.abs(UUID.randomUUID().getLeastSignificantBits() % 10_000_000_000L));
            attempts++;
        } while (loanContractRepository.existsByContractNumber(number) && attempts < 10);
        if (attempts >= 10) throw new BankingException("Erreur lors de la génération du numéro de contrat");
        return number;
    }

    // ════════════════════════════════════════════════════════════
    // 6. QUERY METHODS
    // ════════════════════════════════════════════════════════════

    @Transactional(readOnly = true)
    public LoanContract getLoanById(UUID loanId) {
        return loanContractRepository.findById(loanId)
                .orElseThrow(() -> new NotFoundException("Contrat de prêt introuvable"));
    }

    @Transactional(readOnly = true)
    public List<LoanContract> getUserLoans(String email) {
        // We would need the user ID — simplified for now
        return loanContractRepository.findAll(); // Override with proper user filter via controller
    }

    @Transactional(readOnly = true)
    public List<AmortizationSchedule> getAmortizationSchedule(UUID loanId) {
        return scheduleRepository.findByLoanContractIdOrderByInstallmentNumberAsc(loanId);
    }

    @Transactional(readOnly = true)
    public List<LoanPayment> getLoanPayments(UUID loanId) {
        return paymentRepository.findByLoanContractIdOrderByPaymentDateDesc(loanId);
    }

    @Transactional(readOnly = true)
    public List<RateRevision> getRateRevisions(UUID loanId) {
        return rateRevisionRepository.findByLoanContractIdOrderByEffectiveDateDesc(loanId);
    }

    @Transactional(readOnly = true)
    public List<InterestAccrual> getAccruals(UUID loanId) {
        return accrualRepository.findByLoanContractIdOrderByAccrualDateDesc(loanId);
    }

    /** GAP-H: Get a single payment by ID (for receipt generation) */
    @Transactional(readOnly = true)
    public LoanPayment getPaymentById(UUID paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new NotFoundException("Paiement introuvable"));
    }
}

