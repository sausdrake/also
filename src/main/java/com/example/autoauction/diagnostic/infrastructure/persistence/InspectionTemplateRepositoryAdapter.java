package com.example.autoauction.diagnostic.infrastructure.persistence;

import com.example.autoauction.diagnostic.domain.InspectionGroup;
import com.example.autoauction.diagnostic.domain.InspectionItem;
import com.example.autoauction.diagnostic.domain.InspectionTemplate;
import com.example.autoauction.diagnostic.domain.port.InspectionTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Repository
@RequiredArgsConstructor
@Transactional
public class InspectionTemplateRepositoryAdapter implements InspectionTemplateRepository {

    private final SpringDataInspectionTemplateJpaRepository templateJpaRepository;
    private final SpringDataInspectionGroupJpaRepository groupJpaRepository;
    private final SpringDataInspectionItemJpaRepository itemJpaRepository;
    private final InspectionTemplateMapper templateMapper;
    private final InspectionGroupMapper groupMapper;
    private final InspectionItemMapper itemMapper;

    // ==================== Шаблоны ====================

    @Override
    public InspectionTemplate saveTemplate(InspectionTemplate template) {
        JpaInspectionTemplateEntity entity = templateMapper.toEntity(template);
        JpaInspectionTemplateEntity saved = templateJpaRepository.save(entity);
        log.info("Saved template: id={}, name={}", saved.getId(), saved.getName());
        return templateMapper.toDomain(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InspectionTemplate> findTemplateById(Long id) {
        return templateJpaRepository.findById(id).map(templateMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InspectionTemplate> findActiveTemplateById(Long id) {
        return templateJpaRepository.findByIdAndIsActiveTrue(id).map(templateMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<InspectionTemplate> findAllTemplates(Pageable pageable) {
        return templateJpaRepository.findAll(pageable).map(templateMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<InspectionTemplate> findActiveTemplates(Pageable pageable) {
        return templateJpaRepository.findByIsActiveTrue(pageable).map(templateMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<InspectionTemplate> findTemplatesByVehicleType(String vehicleType) {
        return templateJpaRepository.findByVehicleType(vehicleType)
                .stream()
                .map(templateMapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public void deleteTemplate(Long id) {
        templateJpaRepository.deleteById(id);
        log.info("Deleted template: id={}", id);
    }

    // ==================== Группы ====================

    @Override
    @Transactional(readOnly = true)
    public Optional<InspectionGroup> findGroupById(Long id) {
        return groupJpaRepository.findById(id).map(groupMapper::toDomain);
    }

    @Override
    public InspectionGroup saveGroup(InspectionGroup group) {
        JpaInspectionGroupEntity entity = groupMapper.toEntity(group);
        JpaInspectionGroupEntity saved = groupJpaRepository.save(entity);
        log.info("Saved group: id={}, name={}, templateId={}", saved.getId(), saved.getName(), saved.getTemplateId());
        return groupMapper.toDomain(saved);
    }

    @Override
    public List<InspectionGroup> findGroupsByTemplateId(Long templateId) {
        return groupJpaRepository.findByTemplateIdOrderBySortOrderAsc(templateId)
                .stream()
                .map(groupMapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public void deleteGroup(Long id) {
        groupJpaRepository.deleteById(id);
        log.info("Deleted group: id={}", id);
    }

    @Override
    public void deleteGroupsByTemplateId(Long templateId) {
        groupJpaRepository.deleteByTemplateId(templateId);
        log.info("Deleted all groups for template: id={}", templateId);
    }

    // ==================== Пункты ====================

    @Override
    @Transactional(readOnly = true)
    public Optional<InspectionItem> findItemById(Long id) {
        return itemJpaRepository.findById(id).map(itemMapper::toDomain);
    }

    @Override
    public InspectionItem saveItem(InspectionItem item) {
        JpaInspectionItemEntity entity = itemMapper.toEntity(item);
        JpaInspectionItemEntity saved = itemJpaRepository.save(entity);
        log.info("Saved item: id={}, title={}, groupId={}", saved.getId(), saved.getTitle(), saved.getGroupId());
        return itemMapper.toDomain(saved);
    }

    @Override
    public List<InspectionItem> findItemsByGroupId(Long groupId) {
        return itemJpaRepository.findByGroupIdOrderBySortOrderAsc(groupId)
                .stream()
                .map(itemMapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public void deleteItem(Long id) {
        itemJpaRepository.deleteById(id);
        log.info("Deleted item: id={}", id);
    }

    @Override
    public void deleteItemsByGroupId(Long groupId) {
        itemJpaRepository.deleteByGroupId(groupId);
        log.info("Deleted all items for group: id={}", groupId);
    }

    // ==================== Полный шаблон ====================

    @Override
    @Transactional(readOnly = true)
    public Optional<InspectionTemplate> findFullTemplateById(Long id) {
        Optional<JpaInspectionTemplateEntity> templateOpt = templateJpaRepository.findById(id);
        if (templateOpt.isEmpty()) {
            return Optional.empty();
        }

        JpaInspectionTemplateEntity templateEntity = templateOpt.get();
        List<JpaInspectionGroupEntity> groupEntities = groupJpaRepository.findByTemplateIdOrderBySortOrderAsc(id);

        InspectionTemplate template = templateMapper.toDomain(templateEntity);

        for (JpaInspectionGroupEntity groupEntity : groupEntities) {
            InspectionGroup group = groupMapper.toDomain(groupEntity);
            List<JpaInspectionItemEntity> itemEntities = itemJpaRepository.findByGroupIdOrderBySortOrderAsc(groupEntity.getId());
            group.setItems(itemEntities.stream()
                    .map(itemMapper::toDomain)
                    .collect(Collectors.toList()));
            template.getGroups().add(group);
        }

        return Optional.of(template);
    }
}