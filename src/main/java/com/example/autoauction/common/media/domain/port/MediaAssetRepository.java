package com.example.autoauction.common.media.domain.port;

import com.example.autoauction.common.media.domain.MediaAsset;

import java.util.Optional;

public interface MediaAssetRepository {

    MediaAsset save(MediaAsset mediaAsset);

    Optional<MediaAsset> findByStorageKey(String storageKey);

    void deleteByStorageKey(String storageKey);
}
