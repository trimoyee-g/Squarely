package com.squarely.ledger.api;

import com.squarely.common.security.AuthContext;
import com.squarely.ledger.api.Dtos.*;
import com.squarely.ledger.service.BalanceService;
import com.squarely.ledger.service.LedgerService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class LedgerController {

    private final LedgerService ledger;
    private final BalanceService balances;

    public LedgerController(LedgerService ledger, BalanceService balances) {
        this.ledger = ledger;
        this.balances = balances;
    }

    // --- balances ---
    @GetMapping("/balances/me")
    public UserBalances myBalances() {
        return balances.forUser(AuthContext.userId());
    }

    @GetMapping("/balances/group/{groupId}")
    public GroupBalances groupBalances(@PathVariable long groupId) {
        return balances.forGroup(groupId);
    }

    // --- personal debts ---
    @PostMapping("/personal-debts")
    @ResponseStatus(HttpStatus.CREATED)
    public LedgerEntryView createPersonalDebt(@Valid @RequestBody CreatePersonalDebtRequest req) {
        return ledger.createPersonalDebt(req);
    }

    // --- ledger / audit ---
    @GetMapping("/ledger/group/{groupId}")
    public List<LedgerEntryView> groupLedger(@PathVariable long groupId) {
        return ledger.forGroup(groupId);
    }

    @PostMapping("/ledger/{entryId}/reverse")
    @ResponseStatus(HttpStatus.CREATED)
    public LedgerEntryView reverse(@PathVariable long entryId) {
        return ledger.reverse(entryId);
    }
}
