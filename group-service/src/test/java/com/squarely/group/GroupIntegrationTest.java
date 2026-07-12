package com.squarely.group;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.squarely.common.events.Topics;
import com.squarely.common.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HTTP → service → JPA → Postgres slice for groups. Proves the real security filter,
 * the DB-enforced unique(group_id, user_id) member constraint, and that the expense
 * path actually publishes to Kafka (broker mocked — the wire is covered by e2e).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class GroupIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;

    @MockBean KafkaTemplate<String, Object> kafka;

    /** A request authenticated as the given user id. */
    private MockHttpServletRequestBuilder as(long userId, MockHttpServletRequestBuilder b) {
        return b.header("Authorization", "Bearer " + jwt.generateAccessToken(userId, "u" + userId + "@x.com"));
    }

    private String body(Object o) throws Exception { return json.writeValueAsString(o); }

    private long createGroupAs(long userId, String name) throws Exception {
        String res = mvc.perform(as(userId, post("/groups")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", name))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("id").asLong();
    }

    @Test
    void endpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/groups")).andExpect(status().isUnauthorized());
    }

    @Test
    void createGroupMakesCreatorTheSoleOwnerMember() throws Exception {
        long groupId = createGroupAs(1L, "Flatmates");
        mvc.perform(as(1L, get("/groups/" + groupId + "/members")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].role").value("OWNER"));
    }

    @Test
    void duplicateMemberIsRejectedByDbConstraint() throws Exception {
        long groupId = createGroupAs(1L, "Trip");
        mvc.perform(as(1L, post("/groups/" + groupId + "/members")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("userId", 2, "role", "MEMBER"))))
                .andExpect(status().isCreated());
        // second add of the same user -> unique(group_id,user_id) -> 409
        mvc.perform(as(1L, post("/groups/" + groupId + "/members")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("userId", 2, "role", "MEMBER"))))
                .andExpect(status().isConflict());
    }

    @Test
    void addExpensePersistsSplitsAndPublishesEvent() throws Exception {
        long groupId = createGroupAs(1L, "Dinner");
        mvc.perform(as(1L, post("/groups/" + groupId + "/members")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("userId", 2, "role", "MEMBER"))))
                .andExpect(status().isCreated());

        var expense = Map.of("description", "Pizza", "category", "food", "amount", "10.00",
                "currency", "inr", "paidByUserId", 1, "splitType", "EQUAL",
                "participants", Map.of("1", 0, "2", 0));
        mvc.perform(as(1L, post("/groups/" + groupId + "/expenses")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(expense)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.splits.1").value(5.00))
                .andExpect(jsonPath("$.splits.2").value(5.00));

        verify(kafka).send(eq(Topics.EXPENSE_ADDED), eq(Long.toString(groupId)), any());
    }

    @Test
    void expenseWithNonMemberParticipantIsForbidden() throws Exception {
        long groupId = createGroupAs(1L, "Solo");
        var expense = Map.of("description", "X", "category", "food", "amount", "10.00",
                "currency", "inr", "paidByUserId", 1, "splitType", "EQUAL",
                "participants", Map.of("1", 0, "99", 0));   // 99 is not a member
        mvc.perform(as(1L, post("/groups/" + groupId + "/expenses")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(expense)))
                .andExpect(status().isForbidden());
    }
}
