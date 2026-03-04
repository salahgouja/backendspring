package com.amenbank.banking_webapp.model;

import jakarta.persistence.*;
import lombok.*;

import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "agencies", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "governorate", "branch_name" })
})
@Getter
@Setter
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Agency agency = (Agency) o;
        return id != null && Objects.equals(id, agency.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
