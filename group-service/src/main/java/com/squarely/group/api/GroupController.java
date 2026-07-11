package com.squarely.group.api;

import com.squarely.common.security.AuthContext;
import com.squarely.group.api.Dtos.*;
import com.squarely.group.service.GroupService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class GroupController {

    private final GroupService service;

    public GroupController(GroupService service) {
        this.service = service;
    }

    @PostMapping("/groups")
    @ResponseStatus(HttpStatus.CREATED)
    public GroupView create(@Valid @RequestBody CreateGroupRequest req) {
        return service.createGroup(AuthContext.userId(), req);
    }

    @GetMapping("/groups")
    public List<GroupView> myGroups() {
        return service.listMyGroups(AuthContext.userId());
    }

    @PostMapping("/groups/{groupId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public MemberView addMember(@PathVariable long groupId, @Valid @RequestBody AddMemberRequest req) {
        return service.addMember(AuthContext.userId(), groupId, req);
    }

    @GetMapping("/groups/{groupId}/members")
    public List<MemberView> members(@PathVariable long groupId) {
        return service.listMembers(AuthContext.userId(), groupId);
    }

    @DeleteMapping("/groups/{groupId}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@PathVariable long groupId, @PathVariable long userId) {
        service.removeMember(AuthContext.userId(), groupId, userId);
    }

    @PostMapping("/groups/{groupId}/expenses")
    @ResponseStatus(HttpStatus.CREATED)
    public ExpenseView addExpense(@PathVariable long groupId, @Valid @RequestBody CreateExpenseRequest req) {
        return service.addExpense(AuthContext.userId(), groupId, req);
    }

    @GetMapping("/groups/{groupId}/expenses")
    public List<ExpenseView> expenses(@PathVariable long groupId) {
        return service.listExpenses(AuthContext.userId(), groupId);
    }

    @GetMapping("/expenses/{expenseId}")
    public ExpenseView expense(@PathVariable long expenseId) {
        return service.getExpense(AuthContext.userId(), expenseId);
    }
}
