package com.squarely.group.domain;

import com.squarely.group.split.SplitType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "expenses")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Expense {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "paid_by_user_id", nullable = false)
    private Long paidByUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "split_type", nullable = false)
    private SplitType splitType;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Optimistic lock — concurrent edits to the same expense fail the stale writer. */
    @Version
    private Long version;

    @OneToMany(mappedBy = "expense", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ExpenseSplit> splits = new ArrayList<>();

    public Expense(Long groupId, String description, String category, BigDecimal amount,
                   String currency, Long paidByUserId, SplitType splitType, Long createdBy) {
        this.groupId = groupId;
        this.description = description;
        this.category = category;
        this.amount = amount;
        this.currency = currency;
        this.paidByUserId = paidByUserId;
        this.splitType = splitType;
        this.createdBy = createdBy;
    }

    public void addSplit(Long userId, BigDecimal owedAmount) {
        splits.add(new ExpenseSplit(this, userId, owedAmount));
    }
}
