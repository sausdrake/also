package com.example.autoauction.diagnostic.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SEC-15: the admin client reads and writes "isActive" / "isRequired". After the Spring Boot 3.5
 * upgrade (Jackson 2.21) the Lombok getters were published a second time as "active" / "required";
 * the JSON contract is exactly one property each, in both directions.
 */
@JsonTest
@DisplayName("SEC-15: isActive / isRequired JSON contract")
class InspectionBooleanJsonContractTest {

    @Autowired private ObjectMapper objectMapper;

    @Test
    void template_isActive_only() throws Exception {
        InspectionTemplate t = new InspectionTemplate();
        t.setActive(true);
        Map<String, Object> json = objectMapper.readValue(objectMapper.writeValueAsString(t), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        assertThat(json).containsEntry("isActive", true).doesNotContainKey("active");
        assertThat(objectMapper.readValue("{\"isActive\":true}", InspectionTemplate.class).isActive()).isTrue();
    }

    @Test
    void item_isRequired_only() throws Exception {
        InspectionItem i = new InspectionItem();
        i.setRequired(true);
        Map<String, Object> json = objectMapper.readValue(objectMapper.writeValueAsString(i), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        assertThat(json).containsEntry("isRequired", true).doesNotContainKey("required");
        assertThat(objectMapper.readValue("{\"isRequired\":true}", InspectionItem.class).isRequired()).isTrue();
    }
}
