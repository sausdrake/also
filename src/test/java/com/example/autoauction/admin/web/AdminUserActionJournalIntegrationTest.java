package com.example.autoauction.admin.web;

import com.example.autoauction.auth.domain.TestUserPrincipal;
import com.example.autoauction.user.domain.AdminUserAction;
import com.example.autoauction.user.domain.Role;
import com.example.autoauction.user.domain.User;
import com.example.autoauction.user.domain.port.AdminUserActionRepository;
import com.example.autoauction.user.domain.port.RoleRepository;
import com.example.autoauction.user.domain.port.UserRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SEC-06: every administrative action on a user account is journaled with the acting admin,
 * atomically with the action itself, and only administrators can read the journal.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("SEC-06: admin actions on users are journaled with their actor")
class AdminUserActionJournalIntegrationTest {

    private static final Long ADMIN_A = 9701L;
    private static final Long ADMIN_B = 9702L;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @SpyBean private AdminUserActionRepository journal;

    private static RequestPostProcessor as(Long id, String role) {
        return SecurityMockMvcRequestPostProcessors.user(TestUserPrincipal.createWithRoles(id, "u" + id, role));
    }

    @BeforeEach
    void roles() {
        for (String r : List.of("ROLE_USER", "ROLE_DIAGNOSTIC", "ROLE_ADMIN")) {
            roleRepository.findByName(r).orElseGet(() -> roleRepository.save(new Role(null, r, r)));
        }
    }

    private Long user() {
        Role role = roleRepository.findByName("ROLE_USER").orElseThrow();
        String name = "sec06u" + System.nanoTime();
        return userRepository.save(new User(null, name, name + "@test.com", "x", true, Set.of(role))).getId();
    }

    private List<AdminUserAction> journalOf(Long userId) {
        return journal.findByTargetUserIdNewestFirst(userId);
    }

    @Test
    void everyAction_journaledWithActor_newestFirst() throws Exception {
        Long id = user();
        mockMvc.perform(patch("/api/v1/admin/users/" + id + "/block").with(as(ADMIN_A, "ADMIN"))).andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/admin/users/" + id + "/unblock").with(as(ADMIN_B, "ADMIN"))).andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/admin/users/" + id + "/trust").with(as(ADMIN_A, "ADMIN"))).andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/admin/users/" + id + "/untrust").with(as(ADMIN_B, "ADMIN"))).andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/admin/users/" + id + "/role").with(as(ADMIN_A, "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ROLE_DIAGNOSTIC\"}")).andExpect(status().isOk());
        mockMvc.perform(post("/api/users/" + id + "/roles").with(as(ADMIN_B, "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"roleName\":\"ROLE_USER\"}")).andExpect(status().isOk());

        List<AdminUserAction> entries = journalOf(id);
        assertThat(entries).extracting(a -> a.action().name())
                .containsExactly("ROLE_ADD", "ROLE_SET", "UNTRUST", "TRUST", "UNBLOCK", "BLOCK");
        assertThat(entries).extracting(AdminUserAction::adminId)
                .containsExactly(ADMIN_B, ADMIN_A, ADMIN_B, ADMIN_A, ADMIN_B, ADMIN_A);
        assertThat(entries.get(1).details()).isEqualTo("[ROLE_USER] -> [ROLE_DIAGNOSTIC]");
        assertThat(entries.get(0).details()).isEqualTo("[ROLE_DIAGNOSTIC] -> [ROLE_DIAGNOSTIC,ROLE_USER]");
        assertThat(entries).allSatisfy(a -> assertThat(a.createdAt()).isNotNull());

        String body = mockMvc.perform(get("/api/v1/admin/users/" + id + "/actions").with(as(ADMIN_A, "ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> actions = JsonPath.read(body, "$[*].action");
        assertThat(actions).hasSize(6).first().isEqualTo("ROLE_ADD");
    }

    @Test
    void journal_readableByAdminsOnly() throws Exception {
        Long id = user();
        mockMvc.perform(get("/api/v1/admin/users/" + id + "/actions").with(as(id, "USER"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/users/" + id + "/actions")).andExpect(status().isUnauthorized());
    }

    @Test
    void failedJournalWrite_rollsTheActionBack() throws Exception {
        Long id = user();
        doThrow(new IllegalStateException("simulated journal failure")).when(journal).append(any());

        int status = mockMvc.perform(patch("/api/v1/admin/users/" + id + "/block").with(as(ADMIN_A, "ADMIN")))
                .andReturn().getResponse().getStatus();

        assertThat(status).isGreaterThanOrEqualTo(400);
        assertThat(userRepository.findById(id).orElseThrow().isActive()).as("block rolled back").isTrue();
        org.mockito.Mockito.reset(journal);
        assertThat(journalOf(id)).isEmpty();
    }

    @Test
    void actionOnMissingUser_journalsNothing() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/users/" + Long.MAX_VALUE + "/block").with(as(ADMIN_A, "ADMIN")))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isGreaterThanOrEqualTo(400));
        assertThat(journalOf(Long.MAX_VALUE)).isEmpty();
    }
}
