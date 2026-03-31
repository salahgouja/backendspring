package com.amenbank.banking_webapp.service;

import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Service
public class TwoFactorService {

    private static final HashingAlgorithm TOTP_ALGORITHM = HashingAlgorithm.SHA1;
    private static final int TOTP_DIGITS = 6;
    private static final int TOTP_PERIOD_SECONDS = 30;
    private static final int TOTP_ALLOWED_TIME_STEPS = 1;

    public String generateSecret() {
        SecretGenerator secretGenerator = new DefaultSecretGenerator();
        return normalizeSecret(secretGenerator.generate());
    }

    public String buildOtpAuthUrl(String issuer, String accountName, String secret) {
        String label = issuer + ":" + accountName;
        return String.format(
                "otpauth://totp/%s?secret=%s&issuer=%s&algorithm=%s&digits=%d&period=%d",
                urlEncode(label),
                normalizeSecret(secret),
                urlEncode(issuer),
                TOTP_ALGORITHM.name(),
                TOTP_DIGITS,
                TOTP_PERIOD_SECONDS);
    }

    public boolean isValidCode(String secret, String code) {
        if (secret == null || secret.isBlank() || code == null || code.isBlank()) {
            return false;
        }

        DefaultCodeVerifier verifier = new DefaultCodeVerifier(
                new DefaultCodeGenerator(TOTP_ALGORITHM, TOTP_DIGITS),
                new SystemTimeProvider());
        verifier.setTimePeriod(TOTP_PERIOD_SECONDS);
        verifier.setAllowedTimePeriodDiscrepancy(TOTP_ALLOWED_TIME_STEPS);

        return verifier.isValidCode(normalizeSecret(secret), normalizeCode(code));
    }

    private String normalizeSecret(String secret) {
        return secret == null ? null : secret.replace(" ", "").toUpperCase(Locale.ROOT);
    }

    private String normalizeCode(String code) {
        return code == null ? null : code.replaceAll("\\s+", "");
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
