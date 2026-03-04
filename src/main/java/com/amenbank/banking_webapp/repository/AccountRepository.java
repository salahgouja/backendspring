package com.amenbank.banking_webapp.repository;

import com.amenbank.banking_webapp.model.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {

    List<Account> findByUserId(UUID userId);

    Optional<Account> findByAccountNumber(String accountNumber);

    List<Account> findByUserIdAndIsActiveTrue(UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdForUpdate(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.accountNumber = :accountNumber")
    Optional<Account> findByAccountNumberForUpdate(String accountNumber);

    boolean existsByAccountNumber(String accountNumber);

    List<Account> findByUserIdAndAccountType(UUID userId, Account.AccountType accountType);

    /**
     * Find accounts pending approval for a specific agency
     */
    @Query("SELECT a FROM Account a JOIN a.user u WHERE u.agency.id = :agencyId AND a.status = :status ORDER BY a.createdAt DESC")
    List<Account> findByUserAgencyIdAndStatus(UUID agencyId, Account.AccountStatus status);

    /**
     * Find all accounts pending approval (for admin)
     */
    List<Account> findByStatusOrderByCreatedAtDesc(Account.AccountStatus status);

    long countByStatus(Account.AccountStatus status);

    @Query("SELECT a FROM Account a JOIN a.user u WHERE u.agency.id = :agencyId AND a.status IN :statuses ORDER BY a.createdAt DESC")
    List<Account> findByUserAgencyIdAndStatusIn(UUID agencyId, List<Account.AccountStatus> statuses);
}
