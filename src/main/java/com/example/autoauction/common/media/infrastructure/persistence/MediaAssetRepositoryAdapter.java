package com.example.autoauction.common.media.infrastructure.persistence;

import com.example.autoauction.common.media.domain.MediaAsset;
import com.example.autoauction.common.media.domain.port.MediaAssetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Repository
@RequiredArgsConstructor
@Transactional
public class MediaAssetRepositoryAdapter implements MediaAssetRepository {

    private final SpringDataMediaAssetJpaRepository jpaRepository;
    private final MediaAssetMapper mapper;

    @Override
    public MediaAsset save(MediaAsset mediaAsset) {
        JpaMediaAssetEntity saved = jpaRepository.save(mapper.toEntity(mediaAsset));
        return mapper.toDomain(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MediaAsset> findByStorageKey(String storageKey) {
        return jpaRepository.findByStorageKey(storageKey).map(mapper::toDomain);
    }

    @Override
    public void deleteByStorageKey(String storageKey) {
        jpaRepository.deleteByStorageKey(storageKey);
    }
}
