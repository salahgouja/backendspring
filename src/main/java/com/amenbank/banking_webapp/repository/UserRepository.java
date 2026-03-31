package com.amenbank.banking_webapp.repository;

import com.amenbank.banking_webapp.model.Agency;
import com.amenbank.banking_webapp.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    Optional<User> findByPhone(String phone);

    Optional<User> findByCin(String cin);

    boolean existsByEmail(String email);

    boolean existsByPhone(String phone);

    boolean existsByCin(String cin);

    List<User> findByUserTypeAndAgency(User.UserType userType, Agency agency);

    long countByUserType(User.UserType userType);

    long countByIsActiveTrue();

    long countByIsActiveFalse();

    long countByIs2faEnabledTrue();

    // BUG-5: Paginated user listing
    org.springframework.data.domain.Page<User> findByUserType(User.UserType userType, org.springframework.data.domain.Pageable pageable);

    // BUG-6: Efficient count query for agency dashboard
    @org.springframework.data.jpa.repository.Query("SELECT COUNT(u) FROM User u WHERE u.agency.id = :agencyId AND u.userType IN :types")
    long countByAgencyIdAndUserTypeIn(UUID agencyId, List<User.UserType> types);
}
