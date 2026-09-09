package com.example.autoauction.policy.domain.port;

import com.example.autoauction.policy.domain.ViolationDefinition;

import java.util.List;
import java.util.Optional;

/**
 * X3-E: read-only port for the stable violation catalogue. No save/create/delete method -
 * X3-E's locked scope seeds the catalogue via migration (V37) only and explicitly excludes
 * catalogue mutation (add/retire a code) - see the X3-E final scope report, section A.
 */
public interface ViolationDefinitionRepository {

    List<ViolationDefinition> findAll();

    Optional<ViolationDefinition> findById(Long id);
}
