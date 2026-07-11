package com.squarely.group.api;

import com.squarely.group.domain.GroupMember.Role;
import com.squarely.group.split.SplitType;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public final class Dtos {
    private Dtos() {}

    public record CreateGroupRequest(@NotBlank String name) {}

    public record GroupView(long id, String name, long createdBy, int memberCount) {}

    public record AddMemberRequest(@NotNull Long userId, Role role) {}

    public record MemberView(long userId, Role role, Instant joinedAt) {}

    public record CreateExpenseRequest(
            @NotBlank String description,
            @NotBlank String category,
            @NotNull @Positive BigDecimal amount,
            @NotBlank @Size(min = 3, max = 3) String currency,
            @NotNull Long paidByUserId,
            @NotNull SplitType splitType,
            /** userId -> value; meaning depends on splitType (see SplitCalculator). */
            @NotEmpty Map<Long, BigDecimal> participants) {}

    public record ExpenseView(
            long id, long groupId, String description, String category,
            BigDecimal amount, String currency, long paidByUserId,
            SplitType splitType, long createdBy, Instant createdAt,
            Map<Long, BigDecimal> splits) {}
}
