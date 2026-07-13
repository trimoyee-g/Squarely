package com.squarely.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /** Domain validation (e.g. splits that don't sum) is the caller's fault, not a 500. */
    @Test
    void illegalArgumentBecomesA400CarryingTheMessage() {
        ProblemDetail problem = handler.badRequest(new IllegalArgumentException("Percentages must sum to 100"));

        assertEquals(HttpStatus.BAD_REQUEST.value(), problem.getStatus());
        assertEquals("Percentages must sum to 100", problem.getDetail());
    }

    /** A lost row-version race is retryable, so it must be a 409 — and must not leak internals. */
    @Test
    void optimisticLockFailureBecomesA409() {
        ProblemDetail problem = handler.conflict(new OptimisticLockingFailureException("row 7 changed"));

        assertEquals(HttpStatus.CONFLICT.value(), problem.getStatus());
        assertEquals("The resource was modified concurrently; please retry.", problem.getDetail());
    }
}
