package com.squarely.ledger.service;

import com.squarely.common.events.Events;
import com.squarely.ledger.api.Dtos.*;
import com.squarely.ledger.domain.LedgerEntry;
import com.squarely.ledger.domain.LedgerEntry.EntryType;
import com.squarely.ledger.repo.Repos.LedgerRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Append-only writes into the ledger. Nothing here mutates an existing row. */
@Service
public class LedgerService {

    private final LedgerRepository ledger;

    public LedgerService(LedgerRepository ledger) {
        this.ledger = ledger;
    }

    /**
     * Turn an expense into one debt per participant (each owes the payer their share).
     * Idempotent: the unique (ref_type, ref_id, debtor, creditor) constraint means a
     * redelivered Kafka event can't double-insert.
     */
    @Transactional
    public void recordExpense(Events.ExpenseAdded e) {
        e.splits().forEach((userId, owed) -> {
            if (userId.equals(e.paidByUserId()) || owed.signum() == 0) return;
            // Idempotent on redelivery: skip splits already recorded, so we never flush a
            // duplicate INSERT (which would poison this @Transactional's Hibernate session).
            // uq_ledger_ref stays as the concurrency backstop — on a rare race the loser's
            // tx fails and Kafka redelivery then finds the row via this check.
            if (ledger.existsByRefTypeAndRefIdAndDebtorIdAndCreditorId(
                    "EXPENSE", e.expenseId(), userId, e.paidByUserId())) return;
            ledger.save(new LedgerEntry(e.groupId(), userId, e.paidByUserId(),
                    owed, e.currency(), EntryType.EXPENSE, "EXPENSE", e.expenseId()));
        });
    }

    @Transactional
    public LedgerEntryView createPersonalDebt(CreatePersonalDebtRequest req) {
        if (req.debtorId().equals(req.creditorId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A user cannot owe themselves");
        }
        LedgerEntry saved = ledger.save(new LedgerEntry(null, req.debtorId(), req.creditorId(),
                req.amount(), req.currency().toUpperCase(), EntryType.PERSONAL_DEBT, "MANUAL", null));
        return toView(saved);
    }

    /** Correction as a reversal row (swapped debtor/creditor) — never a delete. */
    @Transactional
    public LedgerEntryView reverse(long entryId) {
        LedgerEntry orig = ledger.findById(entryId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (orig.getType() == EntryType.REVERSAL) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot reverse a reversal");
        }
        try {
            LedgerEntry rev = ledger.saveAndFlush(new LedgerEntry(
                    orig.getGroupId(), orig.getCreditorId(), orig.getDebtorId(), orig.getAmount(),
                    orig.getCurrency(), EntryType.REVERSAL, "REVERSAL", orig.getId()));
            return toView(rev);
        } catch (DataIntegrityViolationException dup) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Entry already reversed");
        }
    }

    @Transactional(readOnly = true)
    public List<LedgerEntryView> forGroup(long groupId) {
        return ledger.findByGroupId(groupId).stream().map(LedgerService::toView).toList();
    }

    static LedgerEntryView toView(LedgerEntry e) {
        return new LedgerEntryView(e.getId(), e.getGroupId(), e.getDebtorId(), e.getCreditorId(),
                e.getAmount(), e.getCurrency(), e.getType(), e.getRefType(), e.getRefId(), e.getCreatedAt());
    }
}
