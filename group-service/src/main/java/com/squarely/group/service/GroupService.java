package com.squarely.group.service;

import com.squarely.common.events.AfterCommit;
import com.squarely.common.events.Events;
import com.squarely.common.events.Topics;
import com.squarely.group.api.Dtos.*;
import com.squarely.group.domain.Expense;
import com.squarely.group.domain.ExpenseGroup;
import com.squarely.group.domain.GroupMember;
import com.squarely.group.domain.GroupMember.Role;
import com.squarely.group.repo.Repos.*;
import com.squarely.group.split.SplitCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class GroupService {

    private static final Logger log = LoggerFactory.getLogger(GroupService.class);

    private final GroupRepository groups;
    private final MemberRepository members;
    private final ExpenseRepository expenses;
    private final KafkaTemplate<String, Object> kafka;

    public GroupService(GroupRepository groups, MemberRepository members,
                        ExpenseRepository expenses, KafkaTemplate<String, Object> kafka) {
        this.groups = groups;
        this.members = members;
        this.expenses = expenses;
        this.kafka = kafka;
    }

    @Transactional
    public GroupView createGroup(long userId, CreateGroupRequest req) {
        ExpenseGroup group = groups.save(new ExpenseGroup(req.name(), userId));
        members.save(new GroupMember(group.getId(), userId, Role.OWNER));
        return new GroupView(group.getId(), group.getName(), group.getCreatedBy(), 1);
    }

    @Transactional(readOnly = true)
    public List<GroupView> listMyGroups(long userId) {
        return members.findByUserId(userId).stream()
                .map(m -> groups.findById(m.getGroupId()).orElseThrow())
                .map(g -> new GroupView(g.getId(), g.getName(), g.getCreatedBy(),
                        members.findByGroupId(g.getId()).size()))
                .toList();
    }

    @Transactional
    public MemberView addMember(long requesterId, long groupId, AddMemberRequest req) {
        requireMember(groupId, requesterId);
        Role role = req.role() == null ? Role.MEMBER : req.role();
        try {
            GroupMember m = members.save(new GroupMember(groupId, req.userId(), role));
            return new MemberView(m.getUserId(), m.getRole(), m.getJoinedAt());
        } catch (DataIntegrityViolationException e) {
            // unique(group_id, user_id) — already a member
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User is already a member");
        }
    }

    @Transactional(readOnly = true)
    public List<MemberView> listMembers(long requesterId, long groupId) {
        requireMember(groupId, requesterId);
        return members.findByGroupId(groupId).stream()
                .map(m -> new MemberView(m.getUserId(), m.getRole(), m.getJoinedAt()))
                .toList();
    }

    @Transactional
    public void removeMember(long requesterId, long groupId, long userId) {
        requireMember(groupId, requesterId);
        members.findByGroupIdAndUserId(groupId, userId).ifPresent(members::delete);
    }

    @Transactional
    public ExpenseView addExpense(long userId, long groupId, CreateExpenseRequest req) {
        requireMember(groupId, userId);
        requireMember(groupId, req.paidByUserId());
        req.participants().keySet().forEach(p -> requireMember(groupId, p));

        Map<Long, BigDecimal> owed = SplitCalculator.compute(req.amount(), req.splitType(), req.participants());

        Expense expense = new Expense(groupId, req.description(), req.category(), req.amount(),
                req.currency().toUpperCase(), req.paidByUserId(), req.splitType(), userId);
        owed.forEach(expense::addSplit);
        Expense saved = expenses.save(expense);

        publish(Topics.EXPENSE_ADDED, Long.toString(groupId), new Events.ExpenseAdded(
                saved.getId(), groupId, saved.getDescription(), saved.getCategory(),
                saved.getAmount(), saved.getCurrency(), saved.getPaidByUserId(),
                owed, Instant.now()));

        return toView(saved, owed);
    }

    @Transactional(readOnly = true)
    public List<ExpenseView> listExpenses(long userId, long groupId) {
        requireMember(groupId, userId);
        return expenses.findByGroupIdOrderByCreatedAtDesc(groupId).stream()
                .map(e -> toView(e, splitsOf(e)))
                .toList();
    }

    @Transactional(readOnly = true)
    public ExpenseView getExpense(long userId, long expenseId) {
        Expense e = expenses.findById(expenseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        requireMember(e.getGroupId(), userId);
        return toView(e, splitsOf(e));
    }

    /**
     * Publishes only once the expense is actually committed — an event for a rolled-back expense
     * would have ledger-service crediting money that was never spent. A send that still fails
     * after the producer's own retries is logged loudly: see {@link AfterCommit} for why that is
     * the honest ceiling here, and what fixes it.
     */
    private void publish(String topic, String key, Object event) {
        AfterCommit.run(() -> kafka.send(topic, key, event).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("LOST EVENT {} key={} — ledger/notifications will not see it: {}",
                        topic, key, event, ex);
            }
        }));
    }

    private void requireMember(long groupId, long userId) {
        if (!members.existsByGroupIdAndUserId(groupId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User " + userId + " is not a group member");
        }
    }

    private Map<Long, BigDecimal> splitsOf(Expense e) {
        return e.getSplits().stream().collect(Collectors.toMap(
                s -> s.getUserId(), s -> s.getOwedAmount()));
    }

    private ExpenseView toView(Expense e, Map<Long, BigDecimal> splits) {
        return new ExpenseView(e.getId(), e.getGroupId(), e.getDescription(), e.getCategory(),
                e.getAmount(), e.getCurrency(), e.getPaidByUserId(), e.getSplitType(),
                e.getCreatedBy(), e.getCreatedAt(), splits);
    }
}
