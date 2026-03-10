package com.amenbank.banking_webapp.repository;

import com.amenbank.banking_webapp.model.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByOtpCodeAndIsUsedFalse(String otpCode);

    @Query("SELECT t FROM PasswordResetToken t WHERE t.user.email = :email AND t.isUsed = false AND t.expiresAt > :now ORDER BY t.createdAt DESC LIMIT 1")
    Optional<PasswordResetToken> findActiveTokenByEmail(String email, LocalDateTime now);

    @Modifying
    @Query("DELETE FROM PasswordResetToken t WHERE t.expiresAt < :now")
    int deleteExpiredTokens(LocalDateTime now);
}

