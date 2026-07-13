package com.squarely.ledger.service;

import com.squarely.ledger.api.Dtos.*;
import com.squarely.ledger.domain.LedgerEntry;
import com.squarely.ledger.domain.Settlement;
import com.squarely.ledger.repo.Repos.LedgerRepository;
import com.squarely.ledger.repo.Repos.SettlementRepository;
import com.squarely.ledger.settlement.SettlementStatus;
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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SettlementServiceTest {

    @Mock SettlementRepository settlements;
    @Mock LedgerRepository ledger;
    @Mock KafkaTemplate<String, Object> kafka;

    SettlementService service;

    @BeforeEach
    void setUp() {
        service = new SettlementService(settlements, ledger, kafka);
        // A failed publish is now inspected and logged, so send() must return a future. lenient:
        // the read-only and rejection paths correctly publish nothing.
        lenient().when(kafka.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    /** Settlement from user 1 -> user 2, with a given status and id set. */
    private static Settlement settlement(long id, SettlementStatus status) {
        Settlement s = new Settlement(5L, 1L, 2L, new BigDecimal("30.00"), "INR", "key-1");
        ReflectionTestUtils.setField(s, "id", id);
        s.setStatus(status);
        return s;
    }

    private static CreateSettlementRequest req(long from, long to) {
        return new CreateSettlementRequest(5L, from, to, new BigDecimal("30.00"), "inr");
    }

    // --- create ---

    @Test
    void createRejectsPayerEqualsReceiver() {
        var ex = assertThrows(ResponseStatusException.class, () -> service.create(req(1L, 1L), null));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void createReplaysExistingForSameIdempotencyKey() {
        when(settlements.findByIdempotencyKey("key-1"))
                .thenReturn(Optional.of(settlement(50L, SettlementStatus.PENDING)));

        SettlementView v = service.create(req(1L, 2L), "key-1");
        assertEquals(50L, v.id());
        verify(settlements, never()).saveAndFlush(any());  // no duplicate insert
    }

    @Test
    void createPersistsNewSettlement() {
        when(settlements.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());
        when(settlements.saveAndFlush(any())).thenAnswer(inv -> {
            Settlement s = inv.getArgument(0);
            ReflectionTestUtils.setField(s, "id", 60L);
            return s;
        });

        SettlementView v = service.create(req(1L, 2L), "key-1");
        assertEquals(60L, v.id());
        assertEquals(SettlementStatus.PENDING, v.status());
    }

    @Test
    void createRaceReturnsTheWinner() {
        when(settlements.findByIdempotencyKey("key-1"))
                .thenReturn(Optional.empty())                                   // first check: none
                .thenReturn(Optional.of(settlement(70L, SettlementStatus.PENDING))); // after race
        when(settlements.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup"));

        SettlementView v = service.create(req(1L, 2L), "key-1");
        assertEquals(70L, v.id());
    }

    // --- claim ---

    @Test
    void claimNotFoundThrows404() {
        when(settlements.findById(1L)).thenReturn(Optional.empty());
        var ex = assertThrows(ResponseStatusException.class, () -> service.claim(1L, 1L, null));
        assertEquals(404, ex.getStatusCode().value());
    }

    @Test
    void claimRejectsNonPayer() {
        when(settlements.findById(1L)).thenReturn(Optional.of(settlement(1L, SettlementStatus.PENDING)));
        var ex = assertThrows(ResponseStatusException.class, () -> service.claim(1L, 2L, null)); // 2 is receiver
        assertEquals(403, ex.getStatusCode().value());
    }

    @Test
    void claimByPayerMovesToClaimedAndEmits() {
        when(settlements.findById(1L)).thenReturn(Optional.of(settlement(1L, SettlementStatus.PENDING)));

        SettlementView v = service.claim(1L, 1L, "UTR123");
        assertEquals(SettlementStatus.PAYMENT_CLAIMED, v.status());
        assertEquals("UTR123", v.utr());
        assertEquals(1L, v.claimedByUserId());
        verify(kafka).send(eq("payment.claimed"), eq("1"), any());
    }

    // --- acknowledge ---

    @Test
    void acknowledgeRejectsNonReceiver() {
        when(settlements.findById(1L)).thenReturn(Optional.of(settlement(1L, SettlementStatus.PAYMENT_CLAIMED)));
        var ex = assertThrows(ResponseStatusException.class, () -> service.acknowledge(1L, 1L)); // 1 is payer
        assertEquals(403, ex.getStatusCode().value());
    }

    @Test
    void acknowledgeByReceiverSettlesAndWritesLedger() {
        when(settlements.findById(1L)).thenReturn(Optional.of(settlement(1L, SettlementStatus.PAYMENT_CLAIMED)));

        SettlementView v = service.acknowledge(1L, 2L);
        assertEquals(SettlementStatus.SETTLED, v.status());
        verify(ledger).saveAndFlush(any(LedgerEntry.class));   // settling ledger entry appended
        verify(kafka).send(eq("payment.acknowledged"), eq("1"), any());
    }

    @Test
    void acknowledgeSwallowsDuplicateLedgerEntry() {
        when(settlements.findById(1L)).thenReturn(Optional.of(settlement(1L, SettlementStatus.PAYMENT_CLAIMED)));
        when(ledger.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("dup"));
        // duplicate ledger write must not fail the acknowledge
        assertDoesNotThrow(() -> service.acknowledge(1L, 2L));
    }

    // --- dispute ---

    @Test
    void disputeByReceiverMovesToDisputed() {
        when(settlements.findById(1L)).thenReturn(Optional.of(settlement(1L, SettlementStatus.PAYMENT_CLAIMED)));
        SettlementView v = service.dispute(1L, 2L, "wrong amount");
        assertEquals(SettlementStatus.DISPUTED, v.status());
        assertEquals("wrong amount", v.disputeReason());
    }

    // --- get ---

    @Test
    void getRejectsNonParty() {
        when(settlements.findById(1L)).thenReturn(Optional.of(settlement(1L, SettlementStatus.PENDING)));
        var ex = assertThrows(ResponseStatusException.class, () -> service.get(1L, 999L));
        assertEquals(403, ex.getStatusCode().value());
    }

    @Test
    void getAllowsAParty() {
        when(settlements.findById(1L)).thenReturn(Optional.of(settlement(1L, SettlementStatus.PENDING)));
        assertEquals(1L, service.get(1L, 2L).id());
    }
}
