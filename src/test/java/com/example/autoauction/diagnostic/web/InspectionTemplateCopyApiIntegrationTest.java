package com.example.autoauction.diagnostic.web;

import com.example.autoauction.auth.domain.TestUserPrincipal;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Scenario 14.4-14.6 over HTTP: the admin copies «Седан» as «Внедорожник» (exact, inactive copy),
 * extends the copy without touching the original, activates it, and both are offered to
 * diagnosticians. Real security chain, controller, service and H2 persistence.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Scenario 14.4: copy inspection template - API behaviour")
class InspectionTemplateCopyApiIntegrationTest {

    private static final String BASE = "/api/v1/admin/diagnostic";

    @Autowired private MockMvc mockMvc;

    private static RequestPostProcessor admin() {
        return SecurityMockMvcRequestPostProcessors.user(TestUserPrincipal.createWithRoles(9001L, "admin", "ADMIN"));
    }

    private static RequestPostProcessor plainUser() {
        return SecurityMockMvcRequestPostProcessors.user(TestUserPrincipal.createWithRoles(9002L, "user", "USER"));
    }

    private Integer postForId(String url, String body) throws Exception {
        String json = mockMvc.perform(post(url).with(admin()).contentType("application/json").content(body))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JsonPath.read(json, "$.id");
    }

    private String getTemplate(Integer id) throws Exception {
        return mockMvc.perform(get(BASE + "/templates/" + id).with(admin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** «Седан» with two groups and three items of different kinds. */
    private Integer createSedan(String name) throws Exception {
        Integer id = postForId(BASE + "/templates", "{\"name\":\"" + name + "\",\"vehicleType\":\"SEDAN\",\"description\":\"Седаны\",\"isActive\":true}");
        Integer engine = postForId(BASE + "/templates/" + id + "/groups", "{\"name\":\"Двигатель\",\"sortOrder\":1}");
        postForId(BASE + "/groups/" + engine + "/items",
                "{\"title\":\"Работа двигателя\",\"fieldType\":\"NUMBER\",\"isRequired\":true,\"sortOrder\":1}");
        postForId(BASE + "/groups/" + engine + "/items",
                "{\"title\":\"Фото моторного отсека\",\"fieldType\":\"PHOTO\",\"isRequired\":false,\"sortOrder\":2}");
        Integer body = postForId(BASE + "/templates/" + id + "/groups", "{\"name\":\"Кузов\",\"sortOrder\":2}");
        postForId(BASE + "/groups/" + body + "/items",
                "{\"title\":\"Видео кузова\",\"fieldType\":\"TEXT\",\"hasVideo\":true,\"isRequired\":true,\"sortOrder\":1}");
        return id;
    }

    @Test
    @DisplayName("14.4-14.6: exact inactive copy, independent edits, both active after activation")
    void copyExtendAndActivate() throws Exception {
        String sedanName = "Седан-" + System.nanoTime();
        String suvName = "Внедорожник-" + System.nanoTime();
        Integer sedan = createSedan(sedanName);
        String originalBefore = getTemplate(sedan);

        // 14.4: copy -> 201, exact structure, new ids, inactive
        String copyJson = mockMvc.perform(post(BASE + "/templates/" + sedan + "/copy").with(admin())
                        .contentType("application/json").content("{\"name\":\"" + suvName + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(suvName))
                .andExpect(jsonPath("$.vehicleType").value("SEDAN"))
                .andExpect(jsonPath("$.description").value("Седаны"))
                .andExpect(jsonPath("$.isActive").value(false))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Integer suv = JsonPath.read(copyJson, "$.id");
        assertThat(suv).isNotEqualTo(sedan);

        String copy = getTemplate(suv);
        for (String field : List.of("name", "sortOrder")) {
            List<Object> a = JsonPath.read(originalBefore, "$.groups[*]." + field);
            List<Object> b = JsonPath.read(copy, "$.groups[*]." + field);
            assertThat(b).isEqualTo(a);
        }
        for (String field : List.of("title", "description", "fieldType", "hasPhoto", "hasVideo", "hasRating", "isRequired", "sortOrder")) {
            List<Object> a = JsonPath.read(originalBefore, "$.groups[*].items[*]." + field);
            List<Object> b = JsonPath.read(copy, "$.groups[*].items[*]." + field);
            assertThat(b).as(field).isEqualTo(a);
        }
        List<Integer> originalGroupIds = JsonPath.read(originalBefore, "$.groups[*].id");
        List<Integer> copyGroupIds = JsonPath.read(copy, "$.groups[*].id");
        List<Integer> originalItemIds = JsonPath.read(originalBefore, "$.groups[*].items[*].id");
        List<Integer> copyItemIds = JsonPath.read(copy, "$.groups[*].items[*].id");
        assertThat(copyGroupIds).hasSize(2).doesNotContainAnyElementsOf(originalGroupIds);
        assertThat(copyItemIds).hasSize(3).doesNotContainAnyElementsOf(originalItemIds);
        assertThat(getTemplate(sedan)).isEqualTo(originalBefore);

        // 14.5: extending the copy does not touch the original
        postForId(BASE + "/groups/" + copyGroupIds.get(1) + "/items",
                "{\"title\":\"Состояние лебёдки\",\"fieldType\":\"TEXT\",\"isRequired\":false,\"sortOrder\":2}");
        assertThat(JsonPath.<List<Object>>read(getTemplate(suv), "$.groups[*].items[*]")).hasSize(4);
        assertThat(getTemplate(sedan)).isEqualTo(originalBefore);

        // 14.6: activate the copy -> both templates are active
        mockMvc.perform(put(BASE + "/templates/" + suv).with(admin()).contentType("application/json")
                        .content("{\"name\":\"" + suvName + "\",\"vehicleType\":\"SEDAN\",\"isActive\":true}"))
                .andExpect(status().isOk());
        String active = mockMvc.perform(get(BASE + "/templates/active?size=200").with(admin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> activeNames = JsonPath.read(active, "$.content[*].name");
        assertThat(activeNames).contains(sedanName, suvName);
    }

    @Test
    @DisplayName("a blank new name is refused with 400")
    void blankName_400() throws Exception {
        Integer sedan = createSedan("Седан-" + System.nanoTime());
        mockMvc.perform(post(BASE + "/templates/" + sedan + "/copy").with(admin())
                        .contentType("application/json").content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("copying a template that does not exist is refused with 404")
    void unknownTemplate_404() throws Exception {
        mockMvc.perform(post(BASE + "/templates/987654321/copy").with(admin())
                        .contentType("application/json").content("{\"name\":\"Копия\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("only an admin can copy templates")
    void nonAdmin_403() throws Exception {
        Integer sedan = createSedan("Седан-" + System.nanoTime());
        mockMvc.perform(post(BASE + "/templates/" + sedan + "/copy").with(plainUser())
                        .contentType("application/json").content("{\"name\":\"Копия\"}"))
                .andExpect(status().isForbidden());
    }
}
