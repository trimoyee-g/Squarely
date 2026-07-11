package com.squarely.ledger.repo;

import com.squarely.ledger.domain.LedgerEntry;
import com.squarely.ledger.domain.Settlement;
import com.squarely.ledger.settlement.SettlementStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public final class Repos {
    private Repos() {}

    public interface LedgerRepository extends JpaRepository<LedgerEntry, Long> {
        List<LedgerEntry> findByGroupId(Long groupId);
        List<LedgerEntry> findByDebtorIdOrCreditorId(Long debtorId, Long creditorId);
    }

    public interface SettlementRepository extends JpaRepository<Settlement, Long> {
        Optional<Settlement> findByIdempotencyKey(String idempotencyKey);
        List<Settlement> findByFromUserIdOrToUserIdOrderByCreatedAtDesc(Long fromUserId, Long toUserId);
        List<Settlement> findByGroupIdOrderByCreatedAtDesc(Long groupId);
        List<Settlement> findByStatusAndFromUserIdOrStatusAndToUserId(
                SettlementStatus s1, Long fromUserId, SettlementStatus s2, Long toUserId);
    }
}
