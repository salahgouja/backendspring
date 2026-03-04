package com.amenbank.banking_webapp.config;

import com.amenbank.banking_webapp.model.Account;
import com.amenbank.banking_webapp.model.Agency;
import com.amenbank.banking_webapp.model.User;
import com.amenbank.banking_webapp.repository.AccountRepository;
import com.amenbank.banking_webapp.repository.AgencyRepository;
import com.amenbank.banking_webapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataLoader implements CommandLineRunner {

        private final UserRepository userRepository;
        private final AccountRepository accountRepository;
        private final AgencyRepository agencyRepository;
        private final PasswordEncoder passwordEncoder;

        @Override
        public void run(String... args) {
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

                seedUser("fatma.chaari@gmail.com", "55000002", "Test1234!",
                                "فاطمة الشعري", "Fatma Chaari", "08000002",
                                User.UserType.PARTICULIER, new BigDecimal("3500.000"),
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
}
