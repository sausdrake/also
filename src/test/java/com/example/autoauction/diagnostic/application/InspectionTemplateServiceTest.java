package com.example.autoauction.diagnostic.application;

import com.example.autoauction.diagnostic.domain.InspectionGroup;
import com.example.autoauction.diagnostic.domain.InspectionItem;
import com.example.autoauction.diagnostic.domain.InspectionTemplate;
import com.example.autoauction.diagnostic.domain.port.InspectionTemplateRepository;
import com.example.autoauction.diagnostic.web.dto.InspectionGroupDto;
import com.example.autoauction.diagnostic.web.dto.InspectionItemDto;
import com.example.autoauction.diagnostic.web.dto.InspectionTemplateDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InspectionTemplateServiceTest {

    @Mock
    private InspectionTemplateRepository repository;

    private InspectionTemplateService service() {
        return new InspectionTemplateService(repository);
    }

    private InspectionTemplateDto templateDto(String name, String vehicleType, String description, Boolean isActive) {
        InspectionTemplateDto dto = new InspectionTemplateDto();
        dto.setName(name);
        dto.setVehicleType(vehicleType);
        dto.setDescription(description);
        dto.setIsActive(isActive);
        return dto;
    }

    private InspectionItemDto itemDto(String fieldType, boolean hasPhoto, boolean hasRating) {
        InspectionItemDto dto = new InspectionItemDto();
        dto.setTitle("title");
        dto.setDescription("desc");
        dto.setFieldType(fieldType);
        dto.setHasPhoto(hasPhoto);
        dto.setHasRating(hasRating);
        return dto;
    }

    private InspectionTemplate existingTemplate(boolean active) {
        InspectionTemplate template = new InspectionTemplate();
        template.setId(1L);
        template.setName("old");
        template.setActive(active);
        return template;
    }

    private InspectionItem savedItemFromAdd(Long groupId, InspectionItemDto dto) {
        when(repository.saveItem(any(InspectionItem.class))).thenAnswer(inv -> inv.getArgument(0));
        service().addItem(groupId, dto);
        ArgumentCaptor<InspectionItem> captor = ArgumentCaptor.forClass(InspectionItem.class);
        verify(repository).saveItem(captor.capture());
        return captor.getValue();
    }

    private InspectionItem savedItemFromUpdate(InspectionItemDto dto) {
        when(repository.findItemById(5L)).thenReturn(Optional.of(new InspectionItem()));
        when(repository.saveItem(any(InspectionItem.class))).thenAnswer(inv -> inv.getArgument(0));
        return service().updateItem(5L, dto);
    }

    // ==================== Templates ====================

    @Test
    void getAllTemplates_delegatesAndReturnsRepositoryPage() {
        Page<InspectionTemplate> page = new PageImpl<>(List.of(new InspectionTemplate()));
        when(repository.findAllTemplates(Pageable.unpaged())).thenReturn(page);

        assertThat(service().getAllTemplates(Pageable.unpaged())).isSameAs(page);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void getActiveTemplates_delegatesAndReturnsRepositoryPage() {
        Page<InspectionTemplate> page = new PageImpl<>(List.of(new InspectionTemplate()));
        when(repository.findActiveTemplates(Pageable.unpaged())).thenReturn(page);

        assertThat(service().getActiveTemplates(Pageable.unpaged())).isSameAs(page);
        verifyNoMoreInteractions(repository);
    }

    @Test
    void getFullTemplateById_found_returnsTemplate() {
        InspectionTemplate template = existingTemplate(true);
        when(repository.findFullTemplateById(1L)).thenReturn(Optional.of(template));

        assertThat(service().getFullTemplateById(1L)).isSameAs(template);
    }

    @Test
    void getFullTemplateById_unknown_throwsIllegalArgument() {
        when(repository.findFullTemplateById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getFullTemplateById(99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("99");
    }

    @Test
    void createTemplate_copiesFieldsMarksActiveAndSetsCreator() {
        when(repository.saveTemplate(any(InspectionTemplate.class))).thenAnswer(inv -> inv.getArgument(0));

        InspectionTemplate result = service().createTemplate(templateDto("n", "CAR", "d", false), 7L);

        ArgumentCaptor<InspectionTemplate> captor = ArgumentCaptor.forClass(InspectionTemplate.class);
        verify(repository).saveTemplate(captor.capture());
        InspectionTemplate saved = captor.getValue();
        assertThat(result).isSameAs(saved);
        assertThat(saved.getName()).isEqualTo("n");
        assertThat(saved.getVehicleType()).isEqualTo("CAR");
        assertThat(saved.getDescription()).isEqualTo("d");
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getCreatedBy()).isEqualTo(7L);
    }

    @Test
    void updateTemplate_nullIsActive_keepsActiveFlag() {
        InspectionTemplate active = existingTemplate(true);
        when(repository.findTemplateById(1L)).thenReturn(Optional.of(active));
        when(repository.saveTemplate(any(InspectionTemplate.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service().updateTemplate(1L, templateDto("n", "CAR", "d", null)).isActive()).isTrue();

        InspectionTemplate inactive = existingTemplate(false);
        when(repository.findTemplateById(2L)).thenReturn(Optional.of(inactive));

        assertThat(service().updateTemplate(2L, templateDto("n", "CAR", "d", null)).isActive()).isFalse();
    }

    @Test
    void updateTemplate_nonNullIsActive_appliesIt() {
        when(repository.findTemplateById(1L)).thenReturn(Optional.of(existingTemplate(true)));
        when(repository.findTemplateById(2L)).thenReturn(Optional.of(existingTemplate(false)));
        when(repository.saveTemplate(any(InspectionTemplate.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service().updateTemplate(1L, templateDto("n", "CAR", "d", false)).isActive()).isFalse();
        assertThat(service().updateTemplate(2L, templateDto("n", "CAR", "d", true)).isActive()).isTrue();
    }

    @Test
    void updateTemplate_copiesFields_andOverwritesWithNull() {
        InspectionTemplate template = existingTemplate(true);
        template.setVehicleType("TRUCK");
        template.setDescription("old description");
        when(repository.findTemplateById(1L)).thenReturn(Optional.of(template));
        when(repository.saveTemplate(any(InspectionTemplate.class))).thenAnswer(inv -> inv.getArgument(0));

        InspectionTemplate result = service().updateTemplate(1L, templateDto(null, null, null, null));

        assertThat(result.getName()).isNull();
        assertThat(result.getVehicleType()).isNull();
        assertThat(result.getDescription()).isNull();
    }

    @Test
    void updateTemplate_unknown_throwsIllegalArgumentAndSavesNothing() {
        when(repository.findTemplateById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().updateTemplate(99L, templateDto("n", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).saveTemplate(any());
    }

    @Test
    void deleteTemplate_onlyDelegates() {
        service().deleteTemplate(3L);

        verify(repository).deleteTemplate(3L);
        verifyNoMoreInteractions(repository);
    }

    // ==================== Groups ====================

    @Test
    void addGroup_copiesTemplateIdNameAndSortOrder() {
        when(repository.findTemplateById(1L)).thenReturn(Optional.of(existingTemplate(true)));
        when(repository.saveGroup(any(InspectionGroup.class))).thenAnswer(inv -> inv.getArgument(0));
        InspectionGroupDto dto = new InspectionGroupDto();
        dto.setName("g");
        dto.setSortOrder(4);

        InspectionGroup result = service().addGroup(1L, dto);

        assertThat(result.getTemplateId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("g");
        assertThat(result.getSortOrder()).isEqualTo(4);
    }

    @Test
    void addGroup_nullSortOrder_copiedAsNull() {
        when(repository.findTemplateById(1L)).thenReturn(Optional.of(existingTemplate(true)));
        when(repository.saveGroup(any(InspectionGroup.class))).thenAnswer(inv -> inv.getArgument(0));
        InspectionGroupDto dto = new InspectionGroupDto();
        dto.setName("g");
        dto.setSortOrder(null);

        assertThat(service().addGroup(1L, dto).getSortOrder()).isNull();
    }

    @Test
    void addGroup_unknownTemplate_throwsIllegalArgumentAndSavesNothing() {
        when(repository.findTemplateById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().addGroup(99L, new InspectionGroupDto()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).saveGroup(any());
    }

    @Test
    void updateGroup_setsNameAndAppliesNonNullSortOrder() {
        InspectionGroup group = new InspectionGroup();
        group.setName("old");
        group.setSortOrder(1);
        when(repository.findGroupById(2L)).thenReturn(Optional.of(group));
        when(repository.saveGroup(any(InspectionGroup.class))).thenAnswer(inv -> inv.getArgument(0));
        InspectionGroupDto dto = new InspectionGroupDto();
        dto.setName("new");
        dto.setSortOrder(9);

        InspectionGroup result = service().updateGroup(2L, dto);

        assertThat(result.getName()).isEqualTo("new");
        assertThat(result.getSortOrder()).isEqualTo(9);
    }

    @Test
    void updateGroup_nullSortOrder_keepsExistingOrder() {
        InspectionGroup group = new InspectionGroup();
        group.setSortOrder(1);
        when(repository.findGroupById(2L)).thenReturn(Optional.of(group));
        when(repository.saveGroup(any(InspectionGroup.class))).thenAnswer(inv -> inv.getArgument(0));
        InspectionGroupDto dto = new InspectionGroupDto();
        dto.setName("new");
        dto.setSortOrder(null);

        InspectionGroup result = service().updateGroup(2L, dto);

        assertThat(result.getName()).isEqualTo("new");
        assertThat(result.getSortOrder()).isEqualTo(1);
    }

    @Test
    void updateGroup_unknown_throwsIllegalArgument() {
        when(repository.findGroupById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().updateGroup(99L, new InspectionGroupDto()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).saveGroup(any());
    }

    @Test
    void deleteGroup_onlyDelegates() {
        service().deleteGroup(4L);

        verify(repository).deleteGroup(4L);
        verifyNoMoreInteractions(repository);
    }

    // ==================== Items: addItem ====================

    @Test
    void addItem_copiesFieldsFromRequest() {
        InspectionItemDto dto = itemDto("TEXT", false, false);
        dto.setHasVideo(true);
        dto.setRequired(false);
        dto.setSortOrder(6);

        InspectionItem saved = savedItemFromAdd(8L, dto);

        assertThat(saved.getGroupId()).isEqualTo(8L);
        assertThat(saved.getTitle()).isEqualTo("title");
        assertThat(saved.getDescription()).isEqualTo("desc");
        assertThat(saved.getFieldType()).isEqualTo("TEXT");
        assertThat(saved.isHasVideo()).isTrue();
        assertThat(saved.isRequired()).isFalse();
        assertThat(saved.getSortOrder()).isEqualTo(6);
    }

    @Test
    void addItem_doesNotCheckThatGroupExists() {
        InspectionItem saved = savedItemFromAdd(12345L, itemDto("TEXT", false, false));

        assertThat(saved.getGroupId()).isEqualTo(12345L);
        verify(repository, never()).findGroupById(any());
    }

    @Test
    void addItem_nullFieldType_becomesTextAndFlagsComeFromRequest() {
        InspectionItem saved = savedItemFromAdd(1L, itemDto(null, true, true));

        assertThat(saved.getFieldType()).isEqualTo("TEXT");
        assertThat(saved.isHasPhoto()).isTrue();
        assertThat(saved.isHasRating()).isTrue();
    }

    @Test
    void addItem_photoFieldType_forcesHasPhotoOnly() {
        InspectionItem saved = savedItemFromAdd(1L, itemDto("PHOTO", false, false));

        assertThat(saved.getFieldType()).isEqualTo("PHOTO");
        assertThat(saved.isHasPhoto()).isTrue();
        assertThat(saved.isHasRating()).isFalse();
    }

    @Test
    void addItem_numberFieldType_forcesHasRatingOnly() {
        InspectionItem saved = savedItemFromAdd(1L, itemDto("NUMBER", false, false));

        assertThat(saved.getFieldType()).isEqualTo("NUMBER");
        assertThat(saved.isHasRating()).isTrue();
        assertThat(saved.isHasPhoto()).isFalse();
    }

    @Test
    void addItem_photoFieldType_doesNotClearRequestedRating() {
        InspectionItem saved = savedItemFromAdd(1L, itemDto("PHOTO", false, true));

        assertThat(saved.isHasPhoto()).isTrue();
        assertThat(saved.isHasRating()).isTrue();
    }

    @Test
    void addItem_otherFieldType_takesFlagsFromRequest() {
        InspectionItem withFlags = savedItemFromAdd(1L, itemDto("TEXT", true, true));
        assertThat(withFlags.isHasPhoto()).isTrue();
        assertThat(withFlags.isHasRating()).isTrue();
    }

    @Test
    void addItem_otherFieldType_withoutFlagsKeepsThemOff() {
        InspectionItem saved = savedItemFromAdd(1L, itemDto("CHECKBOX", false, false));

        assertThat(saved.getFieldType()).isEqualTo("CHECKBOX");
        assertThat(saved.isHasPhoto()).isFalse();
        assertThat(saved.isHasRating()).isFalse();
    }

    @Test
    void addItem_fieldTypeMatchingIsCaseSensitive() {
        InspectionItem saved = savedItemFromAdd(1L, itemDto("photo", false, false));

        assertThat(saved.getFieldType()).isEqualTo("photo");
        assertThat(saved.isHasPhoto()).isFalse();
    }

    @Test
    void addItem_nullSortOrder_copiedAsNull() {
        InspectionItemDto dto = itemDto("TEXT", false, false);
        dto.setSortOrder(null);

        assertThat(savedItemFromAdd(1L, dto).getSortOrder()).isNull();
    }

    // ==================== Items: updateItem ====================

    @Test
    void updateItem_copiesFieldsFromRequest() {
        InspectionItemDto dto = itemDto("TEXT", false, false);
        dto.setHasVideo(true);
        dto.setRequired(false);
        dto.setSortOrder(3);

        InspectionItem result = savedItemFromUpdate(dto);

        assertThat(result.getTitle()).isEqualTo("title");
        assertThat(result.getDescription()).isEqualTo("desc");
        assertThat(result.getFieldType()).isEqualTo("TEXT");
        assertThat(result.isHasVideo()).isTrue();
        assertThat(result.isRequired()).isFalse();
        assertThat(result.getSortOrder()).isEqualTo(3);
    }

    @Test
    void updateItem_nullFieldType_becomesTextAndFlagsComeFromRequest() {
        InspectionItem result = savedItemFromUpdate(itemDto(null, true, true));

        assertThat(result.getFieldType()).isEqualTo("TEXT");
        assertThat(result.isHasPhoto()).isTrue();
        assertThat(result.isHasRating()).isTrue();
    }

    @Test
    void updateItem_photoFieldType_forcesHasPhotoOnly() {
        InspectionItem result = savedItemFromUpdate(itemDto("PHOTO", false, false));

        assertThat(result.isHasPhoto()).isTrue();
        assertThat(result.isHasRating()).isFalse();
    }

    @Test
    void updateItem_numberFieldType_forcesHasRatingOnly() {
        InspectionItem result = savedItemFromUpdate(itemDto("NUMBER", false, false));

        assertThat(result.isHasRating()).isTrue();
        assertThat(result.isHasPhoto()).isFalse();
    }

    @Test
    void updateItem_otherFieldType_takesFlagsFromRequest() {
        InspectionItem result = savedItemFromUpdate(itemDto("TEXT", true, true));

        assertThat(result.isHasPhoto()).isTrue();
        assertThat(result.isHasRating()).isTrue();
    }

    @Test
    void updateItem_nullSortOrder_keepsExistingOrder() {
        InspectionItem existing = new InspectionItem();
        existing.setSortOrder(2);
        when(repository.findItemById(5L)).thenReturn(Optional.of(existing));
        when(repository.saveItem(any(InspectionItem.class))).thenAnswer(inv -> inv.getArgument(0));
        InspectionItemDto dto = itemDto("TEXT", false, false);
        dto.setSortOrder(null);

        assertThat(service().updateItem(5L, dto).getSortOrder()).isEqualTo(2);
    }

    @Test
    void updateItem_nonNullSortOrder_appliesIt() {
        InspectionItemDto dto = itemDto("TEXT", false, false);
        dto.setSortOrder(11);

        assertThat(savedItemFromUpdate(dto).getSortOrder()).isEqualTo(11);
    }

    @Test
    void updateItem_unknown_throwsIllegalArgumentAndSavesNothing() {
        when(repository.findItemById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().updateItem(99L, new InspectionItemDto()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).saveItem(any());
    }

    @Test
    void addAndUpdateItem_copyRequiredTrueAndHasVideoFalse() {
        InspectionItemDto dto = itemDto("TEXT", false, false);
        dto.setRequired(true);
        dto.setHasVideo(false);

        InspectionItem added = savedItemFromAdd(1L, dto);
        assertThat(added.isRequired()).isTrue();
        assertThat(added.isHasVideo()).isFalse();

        InspectionItem existing = new InspectionItem();
        existing.setRequired(false);
        existing.setHasVideo(true);
        when(repository.findItemById(5L)).thenReturn(Optional.of(existing));
        InspectionItem updated = service().updateItem(5L, dto);
        assertThat(updated.isRequired()).isTrue();
        assertThat(updated.isHasVideo()).isFalse();
    }

    @Test
    void deleteItem_onlyDelegates() {
        service().deleteItem(6L);

        verify(repository).deleteItem(6L);
        verifyNoMoreInteractions(repository);
    }
}
