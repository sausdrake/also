package com.example.autoauction.diagnostic.domain.port;

import com.example.autoauction.diagnostic.domain.InspectionGroup;
import com.example.autoauction.diagnostic.domain.InspectionItem;
import com.example.autoauction.diagnostic.domain.InspectionTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.Optional;

public interface InspectionTemplateRepository {

    // Шаблоны
    InspectionTemplate saveTemplate(InspectionTemplate template);
    Optional<InspectionTemplate> findTemplateById(Long id);
    Optional<InspectionTemplate> findActiveTemplateById(Long id);
    Page<InspectionTemplate> findAllTemplates(Pageable pageable);
    Page<InspectionTemplate> findActiveTemplates(Pageable pageable);
    List<InspectionTemplate> findTemplatesByVehicleType(String vehicleType);
    void deleteTemplate(Long id);

    // Группы
    InspectionGroup saveGroup(InspectionGroup group);
    List<InspectionGroup> findGroupsByTemplateId(Long templateId);
    void deleteGroup(Long id);
    void deleteGroupsByTemplateId(Long templateId);

    // Пункты
    InspectionItem saveItem(InspectionItem item);
    List<InspectionItem> findItemsByGroupId(Long groupId);
    void deleteItem(Long id);
    void deleteItemsByGroupId(Long groupId);

    // Полный шаблон с группами и пунктами
    Optional<InspectionTemplate> findFullTemplateById(Long id);
}