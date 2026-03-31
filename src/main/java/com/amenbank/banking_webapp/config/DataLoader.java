package com.amenbank.banking_webapp.config;

import com.amenbank.banking_webapp.model.Account;
import com.amenbank.banking_webapp.model.Agency;
import com.amenbank.banking_webapp.model.CreditRequest;
import com.amenbank.banking_webapp.model.LoanProduct;
import com.amenbank.banking_webapp.model.ReferenceRate;
import com.amenbank.banking_webapp.model.User;
import com.amenbank.banking_webapp.repository.AccountRepository;
import com.amenbank.banking_webapp.repository.AgencyRepository;
import com.amenbank.banking_webapp.repository.LoanProductRepository;
import com.amenbank.banking_webapp.repository.ReferenceRateRepository;
import com.amenbank.banking_webapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataLoader implements CommandLineRunner {

        private static final String TREASURY_OWNER_EMAIL = "admin.superuser@amenbank.tn";
        private static final String TREASURY_ACCOUNT_NUMBER = "AMEN-TREASURY-001";

        private final UserRepository userRepository;
        private final AccountRepository accountRepository;
        private final AgencyRepository agencyRepository;
        private final ReferenceRateRepository referenceRateRepository;
        private final LoanProductRepository loanProductRepository;
        private final PasswordEncoder passwordEncoder;
        private final JdbcTemplate jdbcTemplate;

        @Override
        @Transactional
        public void run(String... args) {
                ensureAccountTypeConstraintIncludesTreasury();

                // ── 1. Seed Agencies ──────────────────────────────────
                seedAgencies();

                // ── 2. Seed Users ─────────────────────────────────────
                // ADMIN (no agency)
                seedUser("admin.superuser@amenbank.tn", "20200001", "AdminAmen2024!",
                                "مدير النظام الرئيسي", "Super Admin", "20000001",
                                User.UserType.ADMIN, null, null);

                // AGENT x2 — linked to specific agencies
                seedUser("agent.benali@amenbank.tn", "71000001", "AgentAmen2024!",
                                "علي بن علي", "Ali Ben Ali", "09000001",
                                User.UserType.AGENT, null, "Tunis-Lafayette");

                seedUser("agent.trabelsi@amenbank.tn", "71000002", "AgentAmen2024!",
                                "سارة الطرابلسي", "Sara Trabelsi", "09000002",
                                User.UserType.AGENT, null, "Medenine-Houmt Souk (Djerba)");

                // PARTICULIER x2 — linked to an agency
                seedUser("mohamed.mejri@gmail.com", "55000001", "Test1234!",
                                "محمد المجري", "Mohamed Mejri", "08000001",
                                User.UserType.PARTICULIER, new BigDecimal("5000.000"),
                                "Tunis-Lafayette");

                // ── GAP-11: Give Mohamed Mejri a 2nd account (EPARGNE) for self-transfer testing ──
                seedSecondAccount("mohamed.mejri@gmail.com", Account.AccountType.EPARGNE, new BigDecimal("2000.000"));

                seedUser("salahgouja2001@gmail.com", "92929292", "Test1234!",
                                "صالح قوجة", "Salah Gouja", "08000002",
                                User.UserType.PARTICULIER, new BigDecimal("13500.000"),
                                "Tunis-Lafayette");

                // COMMERCANT x2 — linked to an agency
                seedUser("cafe.elmedina@gmail.com", "22000001", "Test1234!",
                                "مقهى المدينة", "Café El Medina", "07000001",
                                User.UserType.COMMERCANT, new BigDecimal("12000.000"),
                                "Medenine-Houmt Souk (Djerba)");

                seedUser("boutique.yasmine@gmail.com", "22000002", "Test1234!",
                                "بوتيك ياسمين", "Boutique Yasmine", "07000002",
                                User.UserType.COMMERCANT, new BigDecimal("8500.000"),
                                "Medenine-Houmt Souk (Djerba)");

                log.info("DataLoader finished — agencies + 7 seed users verified.");

                // ── 3. Seed Reference Rates (TMM history) ─────────────
                seedReferenceRates();

                // ── 4. Seed Loan Products ─────────────────────────────
                seedLoanProducts();

                // ── 5. Seed Treasury Account ──────────────────────────
                seedTreasuryAccount();

                log.info("DataLoader finished — full bank data seeded.");
        }

        // ============================================================
        // Seed 68 Amen Bank Agencies
        // ============================================================
        private void seedAgencies() {
                if (agencyRepository.count() > 0) {
                        log.info("Agencies already seeded ({} found)", agencyRepository.count());
                        return;
                }

                Map<String, List<String>> network = new LinkedHashMap<>();
                network.put("Tunis", List.of(
                                "Siège Social (Mohamed V)", "Lafayette", "Avenue de Carthage",
                                "Les Berges du Lac", "La Marsa", "Tunisia Mall", "Menzah V"));
                network.put("Medenine", List.of(
                                "Houmt Souk (Djerba)", "Midoun (Djerba)", "Ajim (Djerba)",
                                "El May (Djerba)", "Zarzis", "Ben Guerdane", "Medenine Centre"));
                network.put("Sousse", List.of("Sousse Mall", "Sousse Khezama", "Sousse Corniche", "Akouda"));
                network.put("Sfax", List.of("Sfax Medina", "Sfax Bab Bhar", "Sfax Hédi Chaker", "Sfax El Jadida"));
                network.put("Bizerte", List.of("Bizerte Ville", "Menzel Bourguiba"));
                network.put("Nabeul", List.of("Nabeul Centre", "Hammamet Centre", "Hammamet Yasmine", "Grombalia"));
                network.put("Kairouan", List.of("Kairouan Centre", "Kairouan Mansoura"));
                network.put("Gafsa", List.of("Gafsa Centre", "Metlaoui"));
                network.put("Gabes", List.of("Gabes Centre", "Mareth"));
                network.put("Monastir", List.of("Monastir Centre", "Ksar Hellal", "Jammel"));
                network.put("Ariana", List.of("Ariana Centre", "Ennasr", "Ettadhamen"));
                network.put("Ben Arous", List.of("Ben Arous Centre", "Megrine", "Ezzahra", "Hammam Lif"));
                network.put("Manouba", List.of("Manouba Centre", "Denden"));
                network.put("Beja", List.of("Beja Ville", "Medjez El Bab"));
                network.put("Jendouba", List.of("Jendouba Centre", "Tabarka"));
                network.put("Kasserine", List.of("Kasserine Centre"));
                network.put("Kef", List.of("Le Kef Centre"));
                network.put("Mahdia", List.of("Mahdia Centre", "Ksour Essef"));
                network.put("Sidi Bouzid", List.of("Sidi Bouzid Centre"));
                network.put("Siliana", List.of("Siliana Centre"));
                network.put("Tataouine", List.of("Tataouine Ville"));
                network.put("Tozeur", List.of("Tozeur Centre"));
                network.put("Kebili", List.of("Kebili Centre"));
                network.put("Zaghouan", List.of("Zaghouan Ville"));

                AtomicInteger counter = new AtomicInteger(0);
                network.forEach((governorate, branches) -> {
                        String prefix = governorate.substring(0, Math.min(3, governorate.length())).toUpperCase();
                        branches.forEach(branch -> {
                                int num = counter.incrementAndGet();
                                Agency agency = Agency.builder()
                                                .governorate(governorate)
                                                .branchName(branch)
                                                .code(prefix + "-" + String.format("%03d", num))
                                                .build();
                                agencyRepository.save(agency);
                        });
                });

                log.info("Seeded {} Amen Bank agencies across {} governorates",
                                counter.get(), network.size());
        }

        // ============================================================
        // Seed User (with agency linking)
        // ============================================================
        private void seedUser(String email, String phone, String password,
                        String nameAr, String nameFr, String cin,
                        User.UserType type, BigDecimal initialBalance, String agencyKey) {
                if (userRepository.existsByEmail(email)) {
                        log.info("Seed user already exists: {} ({})", nameFr, type);
                        return;
                }

                // Resolve agency if provided (format: "Governorate-BranchName")
                Agency agency = null;
                if (agencyKey != null) {
                        String[] parts = agencyKey.split("-", 2);
                        if (parts.length == 2) {
                                agency = agencyRepository
                                                .findByGovernorateOrderByBranchNameAsc(parts[0])
                                                .stream()
                                                .filter(a -> a.getBranchName().equals(parts[1]))
                                                .findFirst()
                                                .orElse(null);
                        }
                        if (agency == null) {
                                log.warn("Agency not found for key: {}", agencyKey);
                        }
                }

                User user = User.builder()
                                .email(email)
                                .phone(phone)
                                .hashedPassword(passwordEncoder.encode(password))
                                .fullNameAr(nameAr)
                                .fullNameFr(nameFr)
                                .cin(cin)
                                .userType(type)
                                .agency(agency)
                                .failedLoginAttempts(0)
                                .build();
                userRepository.save(user);

                // Create default account for PARTICULIER / COMMERCANT
                // Seed accounts are ACTIVE (already approved)
                if (initialBalance != null) {
                        Account account = Account.builder()
                                        .user(user)
                                        .accountNumber(generateAccountNumber())
                                        .accountType(type == User.UserType.COMMERCANT
                                                        ? Account.AccountType.COMMERCIAL
                                                        : Account.AccountType.COURANT)
                                        .balance(initialBalance)
                                        .currency("TND")
                                        .isActive(true)
                                        .status(Account.AccountStatus.ACTIVE)
                                        .build();
                        accountRepository.save(account);
                        log.info("Seeded: {} ({}) — account {} with {} TND [agency: {}]",
                                        nameFr, type, account.getAccountNumber(), initialBalance,
                                        agency != null ? agency.getBranchName() : "none");
                } else {
                        log.info("Seeded: {} ({}) [agency: {}]", nameFr, type,
                                        agency != null ? agency.getBranchName() : "none");
                }
        }

        private String generateAccountNumber() {
                return "AMEN" + String.format("%012d",
                                Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1_000_000_000_000L));
        }

        // ============================================================
        // Seed TMM Reference Rates (BCT historical data)
        // ============================================================
        private void seedReferenceRates() {
                if (referenceRateRepository.count() == 0) {
                        // Historical TMM rates (Taux du Marche Monetaire — BCT)
                        List.of(
                                new Object[]{"TMM", "7.0000", "2023-01-01", "BCT"},
                                new Object[]{"TMM", "7.5000", "2023-06-01", "BCT"},
                                new Object[]{"TMM", "8.0000", "2024-01-15", "BCT"},
                                new Object[]{"TMM", "7.7500", "2024-07-01", "BCT"},
                                new Object[]{"TMM", "8.0000", "2025-01-01", "BCT"},
                                new Object[]{"TMM", "7.9700", "2025-09-01", "BCT"},
                                new Object[]{"TMM", "8.0000", "2026-01-01", "BCT"}
                        ).forEach(r -> referenceRateRepository.save(ReferenceRate.builder()
                                .indexName((String) r[0])
                                .rateValue(new BigDecimal((String) r[1]))
                                .effectiveDate(LocalDate.parse((String) r[2]))
                                .source((String) r[3])
                                .build()));

                        log.info("Seeded 7 TMM historical reference rates");
                }

                if (referenceRateRepository.findCurrentRate("TMM", LocalDate.now()).isEmpty()) {
                        referenceRateRepository.save(ReferenceRate.builder()
                                .indexName("TMM")
                                .rateValue(new BigDecimal("8.00"))
                                .effectiveDate(LocalDate.now())
                                .source("BCT")
                                .build());
                        log.info("Seeded missing current TMM reference rate: 8.00%");
                }
        }

        // ============================================================
        // Seed Loan Products
        // ============================================================
        private void seedLoanProducts() {
                if (loanProductRepository.count() == 0) {
                        // 1. Credit Personnel — Variable (TMM + 3%)
                        loanProductRepository.save(LoanProduct.builder()
                                .name("Credit Personnel").code("CRED-PERSO")
                                .creditType(CreditRequest.CreditType.PERSONNEL)
                                .rateType(LoanProduct.RateType.VARIABLE)
                                .referenceIndex("TMM").margin(new BigDecimal("3.00"))
                                .floorRate(new BigDecimal("5.00")).ceilingRate(new BigDecimal("15.00"))
                                .dayCountConvention(LoanProduct.DayCountConvention.ACTUAL_360)
                                .repaymentFrequency(LoanProduct.RepaymentFrequency.MONTHLY)
                                .interestMethod(LoanProduct.InterestMethod.AMORTIZED)
                                .minAmount(new BigDecimal("1000.000")).maxAmount(new BigDecimal("100000.000"))
                                .minDurationMonths(6).maxDurationMonths(84)
                                .maxGracePeriodMonths(3).penaltyMargin(new BigDecimal("2.00"))
                                .build());

                        // 2. Credit Immobilier — Variable
                        loanProductRepository.save(LoanProduct.builder()
                                .name("Credit Immobilier").code("CRED-IMMO")
                                .creditType(CreditRequest.CreditType.IMMOBILIER)
                                .rateType(LoanProduct.RateType.VARIABLE)
                                .referenceIndex("TMM").margin(new BigDecimal("2.00"))
                                .floorRate(new BigDecimal("4.00")).ceilingRate(new BigDecimal("12.00"))
                                .dayCountConvention(LoanProduct.DayCountConvention.ACTUAL_360)
                                .repaymentFrequency(LoanProduct.RepaymentFrequency.MONTHLY)
                                .interestMethod(LoanProduct.InterestMethod.AMORTIZED)
                                .minAmount(new BigDecimal("10000.000")).maxAmount(new BigDecimal("500000.000"))
                                .minDurationMonths(12).maxDurationMonths(300)
                                .maxGracePeriodMonths(6).penaltyMargin(new BigDecimal("1.50"))
                                .build());

                        // 3. Credit Commercial — Variable
                        loanProductRepository.save(LoanProduct.builder()
                                .name("Credit Commercial").code("CRED-COMM")
                                .creditType(CreditRequest.CreditType.COMMERCIAL)
                                .rateType(LoanProduct.RateType.VARIABLE)
                                .referenceIndex("TMM").margin(new BigDecimal("2.50"))
                                .floorRate(new BigDecimal("5.00")).ceilingRate(new BigDecimal("14.00"))
                                .dayCountConvention(LoanProduct.DayCountConvention.THIRTY_360)
                                .repaymentFrequency(LoanProduct.RepaymentFrequency.MONTHLY)
                                .interestMethod(LoanProduct.InterestMethod.AMORTIZED)
                                .minAmount(new BigDecimal("5000.000")).maxAmount(new BigDecimal("300000.000"))
                                .minDurationMonths(6).maxDurationMonths(120)
                                .maxGracePeriodMonths(3).penaltyMargin(new BigDecimal("2.00"))
                                .build());

                        // 4. Credit Equipement — Variable
                        loanProductRepository.save(LoanProduct.builder()
                                .name("Credit Equipement").code("CRED-EQUIP")
                                .creditType(CreditRequest.CreditType.EQUIPEMENT)
                                .rateType(LoanProduct.RateType.VARIABLE)
                                .referenceIndex("TMM").margin(new BigDecimal("2.75"))
                                .floorRate(new BigDecimal("5.00")).ceilingRate(new BigDecimal("14.00"))
                                .dayCountConvention(LoanProduct.DayCountConvention.ACTUAL_365)
                                .repaymentFrequency(LoanProduct.RepaymentFrequency.QUARTERLY)
                                .interestMethod(LoanProduct.InterestMethod.AMORTIZED)
                                .minAmount(new BigDecimal("2000.000")).maxAmount(new BigDecimal("200000.000"))
                                .minDurationMonths(6).maxDurationMonths(84)
                                .penaltyMargin(new BigDecimal("2.00"))
                                .build());

                        // 5. Facilite de Caisse (Overdraft / Revolving)
                        loanProductRepository.save(LoanProduct.builder()
                                .name("Facilite de Caisse").code("FAC-CAISSE")
                                .creditType(CreditRequest.CreditType.COMMERCIAL)
                                .rateType(LoanProduct.RateType.VARIABLE)
                                .referenceIndex("TMM").margin(new BigDecimal("3.50"))
                                .floorRate(new BigDecimal("6.00")).ceilingRate(new BigDecimal("16.00"))
                                .dayCountConvention(LoanProduct.DayCountConvention.ACTUAL_360)
                                .repaymentFrequency(LoanProduct.RepaymentFrequency.MONTHLY)
                                .interestMethod(LoanProduct.InterestMethod.REVOLVING)
                                .minAmount(new BigDecimal("5000.000")).maxAmount(new BigDecimal("100000.000"))
                                .minDurationMonths(1).maxDurationMonths(12)
                                .maxGracePeriodMonths(0).penaltyMargin(new BigDecimal("3.00"))
                                .build());

                        log.info("Seeded 5 loan products");
                }

                // Ensure required core products stay aligned with disbursement pricing rules.
                upsertLoanProduct(LoanProduct.builder()
                        .name("Credit Personnel")
                        .code("CRED-PERSO")
                        .creditType(CreditRequest.CreditType.PERSONNEL)
                        .rateType(LoanProduct.RateType.VARIABLE)
                        .referenceIndex("TMM")
                        .margin(new BigDecimal("3.00"))
                        .floorRate(new BigDecimal("5.00"))
                        .ceilingRate(new BigDecimal("15.00"))
                        .minAmount(new BigDecimal("1000.000"))
                        .maxAmount(new BigDecimal("100000.000"))
                        .minDurationMonths(6)
                        .maxDurationMonths(84)
                        .penaltyMargin(new BigDecimal("2.00"))
                        .isActive(true)
                        .build());

                upsertLoanProduct(LoanProduct.builder()
                        .name("Credit Immobilier")
                        .code("CRED-IMMO")
                        .creditType(CreditRequest.CreditType.IMMOBILIER)
                        .rateType(LoanProduct.RateType.VARIABLE)
                        .referenceIndex("TMM")
                        .margin(new BigDecimal("2.00"))
                        .floorRate(new BigDecimal("4.00"))
                        .ceilingRate(new BigDecimal("12.00"))
                        .minAmount(new BigDecimal("10000.000"))
                        .maxAmount(new BigDecimal("500000.000"))
                        .minDurationMonths(12)
                        .maxDurationMonths(300)
                        .maxGracePeriodMonths(6)
                        .penaltyMargin(new BigDecimal("1.50"))
                        .isActive(true)
                        .build());

                upsertLoanProduct(LoanProduct.builder()
                        .name("Credit Commercial")
                        .code("CRED-COMM")
                        .creditType(CreditRequest.CreditType.COMMERCIAL)
                        .rateType(LoanProduct.RateType.VARIABLE)
                        .referenceIndex("TMM")
                        .margin(new BigDecimal("2.50"))
                        .floorRate(new BigDecimal("5.00"))
                        .ceilingRate(new BigDecimal("14.00"))
                        .minAmount(new BigDecimal("5000.000"))
                        .maxAmount(new BigDecimal("300000.000"))
                        .minDurationMonths(6)
                        .maxDurationMonths(120)
                        .maxGracePeriodMonths(3)
                        .penaltyMargin(new BigDecimal("2.00"))
                        .isActive(true)
                        .build());

                upsertLoanProduct(LoanProduct.builder()
                        .name("Credit Equipement")
                        .code("CRED-EQUIP")
                        .creditType(CreditRequest.CreditType.EQUIPEMENT)
                        .rateType(LoanProduct.RateType.VARIABLE)
                        .referenceIndex("TMM")
                        .margin(new BigDecimal("2.75"))
                        .floorRate(new BigDecimal("5.00"))
                        .ceilingRate(new BigDecimal("14.00"))
                        .minAmount(new BigDecimal("2000.000"))
                        .maxAmount(new BigDecimal("200000.000"))
                        .minDurationMonths(6)
                        .maxDurationMonths(84)
                        .penaltyMargin(new BigDecimal("2.00"))
                        .isActive(true)
                        .build());
        }

        private void upsertLoanProduct(LoanProduct desired) {
                LoanProduct product = loanProductRepository.findByCode(desired.getCode()).orElse(null);
                if (product == null) {
                        loanProductRepository.save(desired);
                        return;
                }

                product.setName(desired.getName());
                product.setCreditType(desired.getCreditType());
                product.setRateType(LoanProduct.RateType.VARIABLE);
                product.setReferenceIndex("TMM");
                product.setMargin(desired.getMargin());
                product.setFixedRate(null);
                product.setFloorRate(desired.getFloorRate());
                product.setCeilingRate(desired.getCeilingRate());
                product.setMinAmount(desired.getMinAmount());
                product.setMaxAmount(desired.getMaxAmount());
                product.setMinDurationMonths(desired.getMinDurationMonths());
                product.setMaxDurationMonths(desired.getMaxDurationMonths());
                product.setMaxGracePeriodMonths(desired.getMaxGracePeriodMonths());
                product.setPenaltyMargin(desired.getPenaltyMargin());
                product.setIsActive(Boolean.TRUE);
                loanProductRepository.save(product);
        }

        private void seedTreasuryAccount() {
                if (accountRepository.findByAccountType(Account.AccountType.TREASURY).isPresent()) {
                        return;
                }

                User treasuryOwner = userRepository.findByEmail(TREASURY_OWNER_EMAIL)
                                .orElseThrow(() -> new IllegalStateException(
                                                "Treasury owner admin not found: " + TREASURY_OWNER_EMAIL));

                Account treasury = Account.builder()
                                .user(treasuryOwner)
                                .accountNumber(TREASURY_ACCOUNT_NUMBER)
                                .accountType(Account.AccountType.TREASURY)
                                .balance(new BigDecimal("100000000.000"))
                                .currency("TND")
                                .isActive(true)
                                .status(Account.AccountStatus.ACTIVE)
                                .build();

                accountRepository.save(treasury);
                log.info("Seeded bank treasury account {} with 100,000,000 TND", TREASURY_ACCOUNT_NUMBER);
        }

        private void ensureAccountTypeConstraintIncludesTreasury() {
                try {
                        jdbcTemplate.execute("ALTER TABLE accounts DROP CONSTRAINT IF EXISTS accounts_account_type_check");
                        jdbcTemplate.execute(
                                        "ALTER TABLE accounts ADD CONSTRAINT accounts_account_type_check " +
                                                        "CHECK (account_type IN ('COURANT','EPARGNE','COMMERCIAL','TREASURY'))");
                } catch (DataAccessException ex) {
                        log.debug("Could not adjust accounts_account_type_check constraint: {}", ex.getMessage());
                }
        }

        // Seed a second account for an existing user (for self-transfer testing)
        private void seedSecondAccount(String email, Account.AccountType accountType, BigDecimal balance) {
                User user = userRepository.findByEmail(email).orElse(null);
                if (user == null) {
                        log.warn("Cannot seed second account - user not found: {}", email);
                        return;
                }

                List<Account> existing = accountRepository.findByUserIdAndAccountType(user.getId(), accountType);
                if (!existing.isEmpty()) {
                        log.info("User {} already has a {} account: {}",
                                        email, accountType, existing.getFirst().getAccountNumber());
                        return;
                }

                Account account = Account.builder()
                                .user(user)
                                .accountNumber(generateAccountNumber())
                                .accountType(accountType)
                                .balance(balance)
                                .currency("TND")
                                .isActive(true)
                                .status(Account.AccountStatus.ACTIVE)
                                .build();
                accountRepository.save(account);

                log.info("Seeded 2nd account for {}: {} ({}) with {} TND",
                                email, account.getAccountNumber(), accountType, balance);
        }
}
