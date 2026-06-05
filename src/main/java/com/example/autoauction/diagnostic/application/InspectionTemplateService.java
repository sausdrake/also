package com.example.autoauction.diagnostic.application;

import com.example.autoauction.diagnostic.domain.InspectionGroup;
import com.example.autoauction.diagnostic.domain.InspectionItem;
import com.example.autoauction.diagnostic.domain.InspectionTemplate;
import com.example.autoauction.diagnostic.domain.port.InspectionTemplateRepository;
import com.example.autoauction.diagnostic.web.dto.InspectionGroupDto;
import com.example.autoauction.diagnostic.web.dto.InspectionItemDto;
import com.example.autoauction.diagnostic.web.dto.InspectionTemplateDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class InspectionTemplateService {

    private final InspectionTemplateRepository templateRepository;

    // ==================== Шаблоны ====================

    @Transactional(readOnly = true)
    public Page<InspectionTemplate> getAllTemplates(Pageable pageable) {
        return templateRepository.findAllTemplates(pageable);
    }

    @Transactional(readOnly = true)
    public Page<InspectionTemplate> getActiveTemplates(Pageable pageable) {
        return templateRepository.findActiveTemplates(pageable);
    }

    @Transactional(readOnly = true)
    public InspectionTemplate getFullTemplateById(Long id) {
        return templateRepository.findFullTemplateById(id)
                .orElseThrow(() -> new IllegalArgumentException("Шаблон не найден: " + id));
    }

    @Transactional
    public InspectionTemplate createTemplate(InspectionTemplateDto dto, Long adminId) {
        InspectionTemplate template = new InspectionTemplate();
        template.setName(dto.getName());
        template.setVehicleType(dto.getVehicleType());
        template.setDescription(dto.getDescription());
        template.setActive(true);
        template.setCreatedBy(adminId);

        return templateRepository.saveTemplate(template);
    }

    @Transactional
    public InspectionTemplate updateTemplate(Long id, InspectionTemplateDto dto) {
        InspectionTemplate template = templateRepository.findTemplateById(id)
                .orElseThrow(() -> new IllegalArgumentException("Шаблон не найден: " + id));

        template.setName(dto.getName());
        template.setVehicleType(dto.getVehicleType());
        template.setDescription(dto.getDescription());
        if (dto.getIsActive() != null) template.setActive(dto.getIsActive());

        return templateRepository.saveTemplate(template);
    }

    @Transactional
    public void deleteTemplate(Long id) {
        templateRepository.deleteTemplate(id);
    }

    // ==================== Группы ====================

    @Transactional
    public InspectionGroup addGroup(Long templateId, InspectionGroupDto dto) {
        // Проверяем существование шаблона
        templateRepository.findTemplateById(templateId)
                .orElseThrow(() -> new IllegalArgumentException("Шаблон не найден: " + templateId));

        InspectionGroup group = new InspectionGroup();
        group.setTemplateId(templateId);
        group.setName(dto.getName());
        group.setSortOrder(dto.getSortOrder());

        return templateRepository.saveGroup(group);
    }

    @Transactional
    public InspectionGroup updateGroup(Long groupId, InspectionGroupDto dto) {
        InspectionGroup group = templateRepository.findGroupById(groupId)
                .orElseThrow(() -> new IllegalArgumentException("Группа не найдена: " + groupId));
        group.setName(dto.getName());
        if (dto.getSortOrder() != null) group.setSortOrder(dto.getSortOrder());
        return templateRepository.saveGroup(group);
    }

    @Transactional
    public void deleteGroup(Long groupId) {
        templateRepository.deleteGroup(groupId);
    }

    // ==================== Пункты ====================

    @Transactional
    public InspectionItem addItem(Long groupId, InspectionItemDto dto) {
        InspectionItem item = new InspectionItem();
        item.setGroupId(groupId);
        item.setTitle(dto.getTitle());
        item.setDescription(dto.getDescription());
        item.setHasPhoto(dto.isHasPhoto());
        item.setHasVideo(dto.isHasVideo());
        item.setHasRating(dto.isHasRating());
        item.setRequired(dto.isRequired());
        item.setSortOrder(dto.getSortOrder());

        return templateRepository.saveItem(item);
    }

    @Transactional
    public InspectionItem updateItem(Long itemId, InspectionItemDto dto) {
        InspectionItem item = templateRepository.findItemById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Пункт не найден: " + itemId));
        item.setTitle(dto.getTitle());
        item.setDescription(dto.getDescription());
        item.setHasPhoto(dto.isHasPhoto());
        item.setHasVideo(dto.isHasVideo());
        item.setHasRating(dto.isHasRating());
        item.setRequired(dto.isRequired());
        if (dto.getSortOrder() != null) item.setSortOrder(dto.getSortOrder());
        return templateRepository.saveItem(item);
    }

    @Transactional
    public void deleteItem(Long itemId) {
        templateRepository.deleteItem(itemId);
    }
}