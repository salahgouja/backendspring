package com.amenbank.banking_webapp.repository;

import com.amenbank.banking_webapp.model.Agency;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AgencyRepository extends JpaRepository<Agency, UUID> {

    List<Agency> findByGovernorateOrderByBranchNameAsc(String governorate);

    Optional<Agency> findByCode(String code);

    boolean existsByGovernorateAndBranchName(String governorate, String branchName);

    List<Agency> findAllByOrderByGovernorateAscBranchNameAsc();
}
