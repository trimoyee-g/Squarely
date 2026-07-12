package com.squarely.group.service;

import com.squarely.common.events.Topics;
import com.squarely.group.api.Dtos.*;
import com.squarely.group.domain.Expense;
import com.squarely.group.domain.ExpenseGroup;
import com.squarely.group.domain.GroupMember;
import com.squarely.group.domain.GroupMember.Role;
import com.squarely.group.repo.Repos.*;
import com.squarely.group.split.SplitType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GroupServiceTest {

    @Mock GroupRepository groups;
    @Mock MemberRepository members;
    @Mock ExpenseRepository expenses;
    @Mock KafkaTemplate<String, Object> kafka;

    GroupService service;

    @BeforeEach
    void setUp() {
        service = new GroupService(groups, members, expenses, kafka);
    }

    private static ExpenseGroup groupWithId(long id, String name, long owner) {
        ExpenseGroup g = new ExpenseGroup(name, owner);
        ReflectionTestUtils.setField(g, "id", id);
        return g;
    }

    @Test
    void createGroupPersistsGroupAndOwnerMember() {
        when(groups.save(any())).thenReturn(groupWithId(10L, "Flat", 1L));

        GroupView v = service.createGroup(1L, new CreateGroupRequest("Flat"));

        assertEquals(10L, v.id());
        assertEquals(1, v.memberCount());
        verify(members).save(argThat(m -> m.getRole() == Role.OWNER && m.getUserId() == 1L));
    }

    @Test
    void addMemberRequiresRequesterIsMember() {
        when(members.existsByGroupIdAndUserId(5L, 1L)).thenReturn(false);
        var ex = assertThrows(ResponseStatusException.class,
                () -> service.addMember(1L, 5L, new AddMemberRequest(2L, Role.MEMBER)));
        assertEquals(403, ex.getStatusCode().value());
    }

    @Test
    void addMemberDefaultsRoleToMemberWhenNull() {
        when(members.existsByGroupIdAndUserId(5L, 1L)).thenReturn(true);
        when(members.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MemberView v = service.addMember(1L, 5L, new AddMemberRequest(2L, null));
        assertEquals(Role.MEMBER, v.role());
    }

    @Test
    void addMemberMapsDuplicateToConflict() {
        when(members.existsByGroupIdAndUserId(5L, 1L)).thenReturn(true);
        when(members.save(any())).thenThrow(new DataIntegrityViolationException("dup"));
        var ex = assertThrows(ResponseStatusException.class,
                () -> service.addMember(1L, 5L, new AddMemberRequest(2L, Role.MEMBER)));
        assertEquals(409, ex.getStatusCode().value());
    }

    @Test
    void addExpenseRejectsWhenAParticipantIsNotAMember() {
        when(members.existsByGroupIdAndUserId(5L, 1L)).thenReturn(true);   // requester + payer ok
        when(members.existsByGroupIdAndUserId(5L, 99L)).thenReturn(false); // participant not a member

        var req = new CreateExpenseRequest("Lunch", "food", new BigDecimal("10.00"),
                "inr", 1L, SplitType.EQUAL, Map.of(1L, BigDecimal.ZERO, 99L, BigDecimal.ZERO));
        assertThrows(ResponseStatusException.class, () -> service.addExpense(1L, 5L, req));
    }

    @Test
    void addExpenseComputesSplitsSavesAndEmitsEvent() {
        when(members.existsByGroupIdAndUserId(eq(5L), anyLong())).thenReturn(true);
        when(expenses.save(any())).thenAnswer(inv -> {
            Expense e = inv.getArgument(0);
            ReflectionTestUtils.setField(e, "id", 77L);
            return e;
        });

        var req = new CreateExpenseRequest("Lunch", "food", new BigDecimal("10.00"),
                "inr", 1L, SplitType.EQUAL, Map.of(1L, BigDecimal.ZERO, 2L, BigDecimal.ZERO));
        ExpenseView v = service.addExpense(1L, 5L, req);

        assertEquals(77L, v.id());
        assertEquals("INR", v.currency());   // normalized to upper-case
        // splits sum back to the total
        BigDecimal sum = v.splits().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sum.compareTo(new BigDecimal("10.00")));
        verify(kafka).send(eq(Topics.EXPENSE_ADDED), eq("5"), any());
    }

    @Test
    void getExpenseNotFoundThrows404() {
        when(expenses.findById(1L)).thenReturn(java.util.Optional.empty());
        var ex = assertThrows(ResponseStatusException.class, () -> service.getExpense(1L, 1L));
        assertEquals(404, ex.getStatusCode().value());
    }

    @Test
    void removeMemberRequiresMembership() {
        when(members.existsByGroupIdAndUserId(5L, 1L)).thenReturn(false);
        assertThrows(ResponseStatusException.class, () -> service.removeMember(1L, 5L, 2L));
        verify(members, never()).delete(any());
    }
}
