package com.example.autoauction.auction.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AUC-DOC-01: the OpenAPI document (dev only, disabled in prod) describes the current auction contract - the
 * participation deposit is a BD-01 snapshot, the bid step a server-side BD-02 rule, minimumNextBid server-computed,
 * creation carries neither step nor deposit, and no business maximum exists (BD-05).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("AUC-DOC-01: OpenAPI matches the auction contract")
class AuctionOpenApiContractTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void auctionSchemas() throws Exception {
        JsonNode schemas = objectMapper.readTree(mockMvc.perform(get("/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("components").path("schemas");

        JsonNode response = schemas.path("AuctionResponse").path("properties");
        assertThat(response.path("requiredDepositAmount").path("description").asText()).contains("снимок", "BD-01")
                .doesNotContain("индивидуально");
        assertThat(response.path("minBidStep").path("description").asText()).contains("0.01", "BD-02");
        assertThat(response.path("minimumNextBid").path("description").asText()).contains("сервером");

        JsonNode create = schemas.path("AuctionCreateRequest").path("properties");
        assertThat(create.has("minBidStep")).isFalse();
        assertThat(create.has("requiredDepositAmount")).isFalse();
        assertThat(create.path("startingPrice").path("description").asText()).contains("NO BUSINESS MAXIMUM DEFINED");
    }
}
