package com.squarely.group.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "expense_splits")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExpenseSplit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "expense_id", nullable = false)
    private Expense expense;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "owed_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal owedAmount;

    public ExpenseSplit(Expense expense, Long userId, BigDecimal owedAmount) {
        this.expense = expense;
        this.userId = userId;
        this.owedAmount = owedAmount;
    }
}
