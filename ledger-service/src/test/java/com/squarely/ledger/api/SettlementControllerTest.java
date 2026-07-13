package com.squarely.ledger.api;

import com.squarely.ledger.api.Dtos.*;
import com.squarely.ledger.service.SettlementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.verify;

/**
 * The acting user on every transition comes from the token, never the body — otherwise anyone
 * could acknowledge their own claim. That, and the optional claim body, are the paths here.
 */
@ExtendWith(MockitoExtension.class)
class SettlementControllerTest {

    private static final long ACTING_USER = 1L;

    @Mock SettlementService service;
    SettlementController controller;

    @BeforeEach
    void setUp() {
        controller = new SettlementController(service);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(ACTING_USER, null, List.of()));
    }

    @AfterEach
    void tearDown() { SecurityContextHolder.clearContext(); }

    @Test
    void createForwardsTheIdempotencyKey() {
        var req = new CreateSettlementRequest(5L, 1L, 2L, new BigDecimal("30.00"), "inr");
        controller.create(req, "key-1");
        verify(service).create(req, "key-1");
    }

    @Test
    void claimForwardsTheUtrFromTheBody() {
        controller.claim(9L, new ClaimRequest("UTR123"));
        verify(service).claim(9L, ACTING_USER, "UTR123");
    }

    /** Claiming a cash payment sends no body — that must not NPE. */
    @Test
    void claimWithNoBodyHasNoUtr() {
        controller.claim(9L, null);
        verify(service).claim(9L, ACTING_USER, null);
    }

    @Test
    void acknowledgeActsAsTheAuthenticatedUser() {
        controller.acknowledge(9L);
        verify(service).acknowledge(9L, ACTING_USER);
    }

    @Test
    void disputeActsAsTheAuthenticatedUser() {
        controller.dispute(9L, new DisputeRequest("wrong amount"));
        verify(service).dispute(9L, ACTING_USER, "wrong amount");
    }

    @Test
    void getActsAsTheAuthenticatedUser() {
        controller.get(9L);
        verify(service).get(9L, ACTING_USER);
    }

    @Test
    void mineListsTheAuthenticatedUsersSettlements() {
        controller.mine();
        verify(service).listForUser(ACTING_USER);
    }
}
