package com.squarely.group.repo;

import com.squarely.group.domain.Expense;
import com.squarely.group.domain.ExpenseGroup;
import com.squarely.group.domain.GroupMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Repositories for group-service, grouped in one file. */
public final class Repos {
    private Repos() {}

    public interface GroupRepository extends JpaRepository<ExpenseGroup, Long> {}

    public interface MemberRepository extends JpaRepository<GroupMember, Long> {
        List<GroupMember> findByGroupId(Long groupId);
        List<GroupMember> findByUserId(Long userId);
        Optional<GroupMember> findByGroupIdAndUserId(Long groupId, Long userId);
        boolean existsByGroupIdAndUserId(Long groupId, Long userId);
    }

    public interface ExpenseRepository extends JpaRepository<Expense, Long> {
        List<Expense> findByGroupIdOrderByCreatedAtDesc(Long groupId);
    }
}
