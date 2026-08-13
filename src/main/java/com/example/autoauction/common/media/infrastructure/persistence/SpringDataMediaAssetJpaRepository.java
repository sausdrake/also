package com.example.autoauction.common.media.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SpringDataMediaAssetJpaRepository extends JpaRepository<JpaMediaAssetEntity, Long> {

    Optional<JpaMediaAssetEntity> findByStorageKey(String storageKey);

    void deleteByStorageKey(String storageKey);
}
