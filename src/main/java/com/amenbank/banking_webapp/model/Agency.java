package com.amenbank.banking_webapp.model;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "agencies", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "governorate", "branch_name" })
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Agency {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String governorate;

    @Column(name = "branch_name", nullable = false)
    private String branchName;

    @Column(unique = true, nullable = false, length = 10)
    private String code; // e.g. "TUN-001", "MED-002"
}
