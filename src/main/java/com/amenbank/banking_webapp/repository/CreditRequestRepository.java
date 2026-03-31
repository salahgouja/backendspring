package com.amenbank.banking_webapp.repository;

import com.amenbank.banking_webapp.model.CreditRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CreditRequestRepository extends JpaRepository<CreditRequest, UUID> {

    List<CreditRequest> findByUserIdOrderByCreatedAtDesc(UUID userId);

    // All pending/approved credits with user+agency eagerly fetched (for ADMIN)
    @Query("SELECT c FROM CreditRequest c JOIN FETCH c.user u LEFT JOIN FETCH u.agency " +
            "WHERE c.status IN :statuses ORDER BY c.createdAt DESC")
    List<CreditRequest> findPendingWithUser(@Param("statuses") List<CreditRequest.CreditStatus> statuses);

    // Pending/approved credits filtered by agency (for AGENT)
    @Query("SELECT c FROM CreditRequest c JOIN FETCH c.user u LEFT JOIN FETCH u.agency a " +
            "WHERE c.status IN :statuses AND a.id = :agencyId ORDER BY c.createdAt DESC")
    List<CreditRequest> findPendingByAgency(@Param("statuses") List<CreditRequest.CreditStatus> statuses,
            @Param("agencyId") UUID agencyId);
}
