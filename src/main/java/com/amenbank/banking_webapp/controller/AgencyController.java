package com.amenbank.banking_webapp.controller;

import com.amenbank.banking_webapp.model.Agency;
import com.amenbank.banking_webapp.repository.AgencyRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/agencies")
@RequiredArgsConstructor
@Tag(name = "Agencies", description = "Réseau d'agences Amen Bank")
public class AgencyController {

    private final AgencyRepository agencyRepository;

    @GetMapping
    @Operation(summary = "Lister toutes les agences", description = "Retourne les agences groupées par gouvernorat")
    public ResponseEntity<Map<String, List<Agency>>> getAllAgencies() {
        List<Agency> agencies = agencyRepository.findAllByOrderByGovernorateAscBranchNameAsc();
        Map<String, List<Agency>> grouped = agencies.stream()
                .collect(Collectors.groupingBy(
                        Agency::getGovernorate,
                        LinkedHashMap::new,
                        Collectors.toList()));
        return ResponseEntity.ok(grouped);
    }

    @GetMapping("/list")
    @Operation(summary = "Liste plate de toutes les agences")
    public ResponseEntity<List<Agency>> getAllAgenciesFlat() {
        return ResponseEntity.ok(agencyRepository.findAllByOrderByGovernorateAscBranchNameAsc());
    }

    @GetMapping("/governorate/{governorate}")
    @Operation(summary = "Agences par gouvernorat")
    public ResponseEntity<List<Agency>> getByGovernorate(@PathVariable String governorate) {
        return ResponseEntity.ok(agencyRepository.findByGovernorateOrderByBranchNameAsc(governorate));
    }
}
