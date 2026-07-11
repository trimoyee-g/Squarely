package com.squarely.ledger.api;

import com.squarely.common.security.AuthContext;
import com.squarely.ledger.api.Dtos.*;
import com.squarely.ledger.service.SettlementService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/settlements")
public class SettlementController {

    private final SettlementService service;

    public SettlementController(SettlementService service) {
        this.service = service;
    }

    /** Idempotency-Key header makes repeated clicks / retries return the same settlement. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SettlementView create(@Valid @RequestBody CreateSettlementRequest req,
                                 @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return service.create(req, idempotencyKey);
    }

    @PostMapping("/{id}/claim")
    public SettlementView claim(@PathVariable long id, @RequestBody(required = false) ClaimRequest req) {
        return service.claim(id, AuthContext.userId(), req == null ? null : req.utr());
    }

    @PostMapping("/{id}/acknowledge")
    public SettlementView acknowledge(@PathVariable long id) {
        return service.acknowledge(id, AuthContext.userId());
    }

    @PostMapping("/{id}/dispute")
    public SettlementView dispute(@PathVariable long id, @Valid @RequestBody DisputeRequest req) {
        return service.dispute(id, AuthContext.userId(), req.reason());
    }

    @GetMapping("/{id}")
    public SettlementView get(@PathVariable long id) {
        return service.get(id, AuthContext.userId());
    }

    @GetMapping
    public List<SettlementView> mine() {
        return service.listForUser(AuthContext.userId());
    }
}
