package com.squarely.ledger.service;

import com.squarely.common.events.AfterCommit;
import com.squarely.common.events.Events;
import com.squarely.common.events.Topics;
import com.squarely.ledger.api.Dtos.*;
import com.squarely.ledger.domain.LedgerEntry;
import com.squarely.ledger.domain.LedgerEntry.EntryType;
import com.squarely.ledger.domain.Settlement;
import com.squarely.ledger.repo.Repos.LedgerRepository;
import com.squarely.ledger.repo.Repos.SettlementRepository;
import com.squarely.ledger.settlement.SettlementStateMachine;
import com.squarely.ledger.settlement.SettlementStateMachine.Action;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * The settlement workflow. Every transition goes through the state machine, runs in one
 * DB transaction, and is guarded by @Version (optimistic lock) plus unique constraints:
 *   - idempotency_key           -> retries don't create duplicate settlements
 *   - @Version on Settlement    -> two concurrent acks can't both win
 *   - unique ledger ref         -> settling can't write two ledger entries
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final SettlementRepository settlements;
    private final LedgerRepository ledger;
    private final KafkaTemplate<String, Object> kafka;

    public SettlementService(SettlementRepository settlements, LedgerRepository ledger,
                             KafkaTemplate<String, Object> kafka) {
        this.settlements = settlements;
        this.ledger = ledger;
        this.kafka = kafka;
    }

    @Transactional
    public SettlementView create(CreateSettlementRequest req, String idempotencyKey) {
        if (req.fromUserId().equals(req.toUserId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payer and receiver must differ");
        }
        if (idempotencyKey != null) {
            var existing = settlements.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) return toView(existing.get());   // replay → same settlement
        }
        try {
            Settlement s = settlements.saveAndFlush(new Settlement(
                    req.groupId(), req.fromUserId(), req.toUserId(),
                    req.amount(), req.currency().toUpperCase(), idempotencyKey));
            return toView(s);
        } catch (DataIntegrityViolationException race) {
            // Concurrent create with the same key — return the one that won.
            return settlements.findByIdempotencyKey(idempotencyKey).map(this::toView)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Duplicate settlement"));
        }
    }

    @Transactional
    public SettlementView claim(long id, long actingUserId, String utr) {
        Settlement s = load(id);
        requireUser(actingUserId, s.getFromUserId(), "Only the payer can claim payment");
        s.setStatus(SettlementStateMachine.next(s.getStatus(), Action.CLAIM));
        s.setClaimedByUserId(actingUserId);
        s.setClaimedAt(Instant.now());
        if (utr != null && !utr.isBlank()) s.setUtr(utr);
        settlements.save(s);
        publish(Topics.PAYMENT_CLAIMED, Long.toString(id), new Events.PaymentClaimed(
                s.getId(), s.getIdempotencyKey(), s.getFromUserId(), s.getToUserId(),
                s.getAmount(), s.getCurrency(), s.getUtr(), s.getClaimedAt()));
        return toView(s);
    }

    @Transactional
    public SettlementView acknowledge(long id, long actingUserId) {
        Settlement s = load(id);
        requireUser(actingUserId, s.getToUserId(), "Only the receiver can acknowledge payment");
        s.setStatus(SettlementStateMachine.next(s.getStatus(), Action.ACKNOWLEDGE));
        s.setAcknowledgedByUserId(actingUserId);
        s.setAcknowledgedAt(Instant.now());
        settlements.save(s);

        // Money is now confirmed → append the settling ledger entry (reduces the debt).
        // Unique ref constraint makes this write idempotent even under retry.
        try {
            ledger.saveAndFlush(new LedgerEntry(s.getGroupId(), s.getToUserId(), s.getFromUserId(),
                    s.getAmount(), s.getCurrency(), EntryType.SETTLEMENT, "SETTLEMENT", s.getId()));
        } catch (DataIntegrityViolationException dup) {
            // Ledger entry for this settlement already exists — no double credit.
        }

        publish(Topics.PAYMENT_ACKNOWLEDGED, Long.toString(id), new Events.PaymentAcknowledged(
                s.getId(), s.getFromUserId(), s.getToUserId(), actingUserId, s.getAcknowledgedAt()));
        return toView(s);
    }

    @Transactional
    public SettlementView dispute(long id, long actingUserId, String reason) {
        Settlement s = load(id);
        requireUser(actingUserId, s.getToUserId(), "Only the receiver can dispute payment");
        s.setStatus(SettlementStateMachine.next(s.getStatus(), Action.DISPUTE));
        s.setDisputeReason(reason);
        s.setDisputedAt(Instant.now());
        settlements.save(s);
        publish(Topics.PAYMENT_DISPUTED, Long.toString(id), new Events.PaymentDisputed(
                s.getId(), s.getFromUserId(), s.getToUserId(), actingUserId, reason, s.getDisputedAt()));
        return toView(s);
    }

    @Transactional(readOnly = true)
    public SettlementView get(long id, long actingUserId) {
        Settlement s = load(id);
        if (actingUserId != s.getFromUserId() && actingUserId != s.getToUserId()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a party to this settlement");
        }
        return toView(s);
    }

    @Transactional(readOnly = true)
    public List<SettlementView> listForUser(long userId) {
        return settlements.findByFromUserIdOrToUserIdOrderByCreatedAtDesc(userId, userId)
                .stream().map(this::toView).toList();
    }

    /**
     * Publishes only once the settlement transition is actually committed — an event for a
     * rolled-back or optimistically-locked-out transition would tell notification-service a
     * payment was acknowledged when it was not. A send that still fails after the producer's own
     * retries is logged loudly: see {@link AfterCommit} for the ceiling and the fix.
     */
    private void publish(String topic, String key, Object event) {
        AfterCommit.run(() -> kafka.send(topic, key, event).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("LOST EVENT {} key={} — downstream will not see it: {}",
                        topic, key, event, ex);
            }
        }));
    }

    private Settlement load(long id) {
        return settlements.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Settlement not found"));
    }

    private void requireUser(long actingUserId, long allowedUserId, String message) {
        if (actingUserId != allowedUserId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, message);
        }
    }

    private SettlementView toView(Settlement s) {
        return new SettlementView(s.getId(), s.getGroupId(), s.getFromUserId(), s.getToUserId(),
                s.getAmount(), s.getCurrency(), s.getStatus(), s.getUtr(),
                s.getClaimedByUserId(), s.getClaimedAt(),
                s.getAcknowledgedByUserId(), s.getAcknowledgedAt(),
                s.getDisputeReason(), s.getDisputedAt(), s.getCreatedAt());
    }
}
