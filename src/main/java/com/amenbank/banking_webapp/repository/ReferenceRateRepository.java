package com.amenbank.banking_webapp.repository;

import com.amenbank.banking_webapp.model.ReferenceRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReferenceRateRepository extends JpaRepository<ReferenceRate, UUID> {

    /** Get the most recent rate for a given index as of a date */
    @Query("SELECT r FROM ReferenceRate r WHERE r.indexName = :indexName AND r.effectiveDate <= :asOf " +
           "ORDER BY r.effectiveDate DESC LIMIT 1")
    Optional<ReferenceRate> findCurrentRate(@Param("indexName") String indexName, @Param("asOf") LocalDate asOf);

    /** History of rate changes for an index */
    List<ReferenceRate> findByIndexNameOrderByEffectiveDateDesc(String indexName);

    /** All distinct index names */
    @Query("SELECT DISTINCT r.indexName FROM ReferenceRate r ORDER BY r.indexName")
    List<String> findDistinctIndexNames();
}

