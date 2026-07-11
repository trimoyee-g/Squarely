package com.squarely.notification.api;

import com.squarely.common.security.AuthContext;
import com.squarely.notification.api.Dtos.*;
import com.squarely.notification.domain.RecurringRule;
import com.squarely.notification.service.RecurringService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
public class RecurringController {

    private final RecurringService service;

    public RecurringController(RecurringService service) {
        this.service = service;
    }

    @PostMapping("/recurring")
    @ResponseStatus(HttpStatus.CREATED)
    public RuleView create(@Valid @RequestBody CreateRecurringRuleRequest req) {
        RecurringRule rule = new RecurringRule(req.groupId(), AuthContext.userId(), req.description(),
                req.category(), req.amount(), req.currency().toUpperCase(), req.cadence(),
                req.intervalDays(), req.firstDueDate(), req.memberUserIds());
        return RuleView.of(service.createRule(rule));
    }

    @GetMapping("/recurring")
    public List<RuleView> myRules() {
        return service.rulesOf(AuthContext.userId()).stream().map(RuleView::of).toList();
    }

    @PatchMapping("/recurring/{id}")
    public RuleView update(@PathVariable long id, @Valid @RequestBody UpdateRecurringRuleRequest req) {
        RecurringRule updated = service.updateRule(id, AuthContext.userId(), req.description(), req.category(),
                req.amount(), req.currency().toUpperCase(), req.cadence(), req.intervalDays(),
                req.nextDueDate(), req.memberUserIds());
        return RuleView.of(updated);
    }

    @DeleteMapping("/recurring/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.deactivateRule(id, AuthContext.userId());
    }

    @GetMapping("/obligations")
    public List<ObligationView> myObligations() {
        return service.obligationsFor(AuthContext.userId()).stream().map(ObligationView::of).toList();
    }

    @PostMapping("/obligations/{id}/settle")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void settle(@PathVariable long id, @RequestBody(required = false) SettleObligationRequest req) {
        service.settle(id, req == null ? null : req.settlementId());
    }

    /** Manual tick for demo/testing; production runs it daily via {@code RecurringScheduler}. */
    @PostMapping("/recurring/run")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void run() {
        service.tick(LocalDate.now());
    }
}
