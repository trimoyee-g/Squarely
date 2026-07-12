package com.squarely.auth.api;

import com.squarely.auth.api.Dtos.*;
import com.squarely.auth.service.AuthService;
import com.squarely.common.security.AuthContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse signup(@Valid @RequestBody SignupRequest req) {
        return auth.signup(req);
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest req) {
        return auth.login(req);
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest req) {
        return auth.refresh(req.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout() {
        auth.logout(AuthContext.userId());
    }

    @GetMapping("/me")
    public UserView me() {
        return auth.findUser(AuthContext.userId());
    }

    @GetMapping("/internal/users")
    public List<UserView> byIds(@RequestParam List<Long> ids) {
        return auth.findUsers(ids);
    }
}
