package com.example.autoauction.policy.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Single-row counter backing PolicyVersion.versionNumber allocation. Read/incremented under a
 * pessimistic write lock (see {@link SpringDataPolicyVersionCounterJpaRepository}) within the
 * same transaction as the PolicyVersion insert it allocates for - this is what makes concurrent
 * allocation race-free, portable across H2 (tests) and PostgreSQL (production) alike, unlike a
 * Postgres-only sequence.
 */
@Entity
@Table(name = "policy_version_counter")
public class JpaPolicyVersionCounterEntity {

    @Id
    private Integer id;

    @Column(name = "next_value", nullable = false)
    private Long nextValue;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public Long getNextValue() { return nextValue; }
    public void setNextValue(Long nextValue) { this.nextValue = nextValue; }
}
