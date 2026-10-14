package com.example.autoauction.diagnostic.application;

import com.example.autoauction.diagnostic.domain.InspectionGroup;
import com.example.autoauction.diagnostic.domain.InspectionItem;
import com.example.autoauction.diagnostic.domain.InspectionTemplate;
import com.example.autoauction.diagnostic.domain.port.InspectionTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Scenario 14.4: "Копировать" creates an exact, inactive copy of a template under a new name. */
@ExtendWith(MockitoExtension.class)
@DisplayName("Scenario 14.4: InspectionTemplateService.copyTemplate()")
class InspectionTemplateCopyTest {

    @Mock private InspectionTemplateRepository repository;

    private InspectionTemplateService service;
    private InspectionTemplate source;
    private final AtomicLong ids = new AtomicLong(1000);

    private static InspectionItem item(Long id, Long groupId, String title, String fieldType, boolean photo,
                                       boolean video, boolean rating, boolean required, int sortOrder) {
        InspectionItem i = new InspectionItem();
        i.setId(id);
        i.setGroupId(groupId);
        i.setTitle(title);
        i.setDescription(title + " description");
        i.setFieldType(fieldType);
        i.setHasPhoto(photo);
        i.setHasVideo(video);
        i.setHasRating(rating);
        i.setRequired(required);
        i.setSortOrder(sortOrder);
        return i;
    }

    @BeforeEach
    void setUp() {
        service = new InspectionTemplateService(repository);

        source = new InspectionTemplate();
        source.setId(1L);
        source.setName("Седан");
        source.setVehicleType("SEDAN");
        source.setDescription("Шаблон для седанов");
        source.setActive(true);
        source.setCreatedBy(5L);
        InspectionGroup engine = new InspectionGroup();
        engine.setId(10L);
        engine.setTemplateId(1L);
        engine.setName("Двигатель");
        engine.setSortOrder(1);
        engine.setItems(List.of(
                item(100L, 10L, "Работа двигателя", "NUMBER", false, false, true, true, 1),
                item(101L, 10L, "Фото моторного отсека", "PHOTO", true, false, false, false, 2)));
        InspectionGroup body = new InspectionGroup();
        body.setId(11L);
        body.setTemplateId(1L);
        body.setName("Кузов");
        body.setSortOrder(2);
        body.setItems(List.of(item(110L, 11L, "Видео кузова", "TEXT", false, true, false, true, 1)));
        source.setGroups(List.of(engine, body));

        lenient().when(repository.findFullTemplateById(1L)).thenReturn(Optional.of(source));
        lenient().when(repository.saveTemplate(any())).thenAnswer(inv -> withId(inv.getArgument(0, InspectionTemplate.class)));
        lenient().when(repository.saveGroup(any())).thenAnswer(inv -> {
            InspectionGroup g = inv.getArgument(0);
            g.setId(ids.incrementAndGet());
            return g;
        });
        lenient().when(repository.saveItem(any())).thenAnswer(inv -> {
            InspectionItem i = inv.getArgument(0);
            i.setId(ids.incrementAndGet());
            return i;
        });
    }

    private InspectionTemplate withId(InspectionTemplate t) {
        t.setId(ids.incrementAndGet());
        lenient().when(repository.findFullTemplateById(t.getId())).thenReturn(Optional.of(t));
        return t;
    }

    @Test
    @DisplayName("the copy carries everything except the name, is inactive, and owns new groups/items")
    void copy_isExactInactiveAndIndependent() {
        service.copyTemplate(1L, "  Внедорожник  ", 7L);

        ArgumentCaptor<InspectionTemplate> template = ArgumentCaptor.forClass(InspectionTemplate.class);
        verify(repository).saveTemplate(template.capture());
        InspectionTemplate copy = template.getValue();
        assertThat(copy.getName()).isEqualTo("Внедорожник");
        assertThat(copy.getVehicleType()).isEqualTo("SEDAN");
        assertThat(copy.getDescription()).isEqualTo("Шаблон для седанов");
        assertThat(copy.isActive()).isFalse();
        assertThat(copy.getCreatedBy()).isEqualTo(7L);
        assertThat(copy.getId()).isNotEqualTo(1L);

        ArgumentCaptor<InspectionGroup> groups = ArgumentCaptor.forClass(InspectionGroup.class);
        verify(repository, times(2)).saveGroup(groups.capture());
        assertThat(groups.getAllValues()).extracting(InspectionGroup::getName, InspectionGroup::getSortOrder, InspectionGroup::getTemplateId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Двигатель", 1, copy.getId()),
                        org.assertj.core.groups.Tuple.tuple("Кузов", 2, copy.getId()));

        ArgumentCaptor<InspectionItem> items = ArgumentCaptor.forClass(InspectionItem.class);
        verify(repository, times(3)).saveItem(items.capture());
        List<InspectionItem> saved = items.getAllValues();
        List<InspectionItem> originals = List.of(source.getGroups().get(0).getItems().get(0),
                source.getGroups().get(0).getItems().get(1), source.getGroups().get(1).getItems().get(0));
        for (int n = 0; n < 3; n++) {
            assertThat(saved.get(n)).usingRecursiveComparison().ignoringFields("id", "groupId", "createdAt")
                    .isEqualTo(originals.get(n));
        }
        Long copiedEngineGroup = groups.getAllValues().get(0).getId();
        Long copiedBodyGroup = groups.getAllValues().get(1).getId();
        assertThat(saved).extracting(InspectionItem::getGroupId).containsExactly(copiedEngineGroup, copiedEngineGroup, copiedBodyGroup);
        assertThat(saved).extracting(InspectionItem::getId).doesNotContain(100L, 101L, 110L);
    }

    @Test
    @DisplayName("the source template is not modified")
    void copy_leavesSourceUntouched() {
        service.copyTemplate(1L, "Внедорожник", 7L);

        assertThat(source.getName()).isEqualTo("Седан");
        assertThat(source.isActive()).isTrue();
        assertThat(source.getGroups()).extracting(InspectionGroup::getId).containsExactly(10L, 11L);
        assertThat(source.getGroups().get(0).getItems()).extracting(InspectionItem::getId).containsExactly(100L, 101L);
        assertThat(source.getGroups().get(0).getItems()).extracting(InspectionItem::getGroupId).containsOnly(10L);
    }

    @ParameterizedTest(name = "name [{0}] is refused")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void blankName_isRefusedAndNothingSaved(String name) {
        assertThatThrownBy(() -> service.copyTemplate(1L, name, 7L)).isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).saveTemplate(any());
    }

    @Test
    @DisplayName("an unknown source template is refused and nothing is saved")
    void unknownSource_isRefusedAndNothingSaved() {
        when(repository.findFullTemplateById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.copyTemplate(404L, "Копия", 7L)).isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).saveTemplate(any());
        verify(repository, never()).saveGroup(any());
    }
}
