package com.squarely.common.security;

import org.springframework.security.core.context.SecurityContextHolder;

/** Convenience accessor for the authenticated userId set by {@link JwtAuthFilter}. */
public final class AuthContext {
    private AuthContext() {}

    /** The authenticated userId, or throws if the request is anonymous. */
    public static long userId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return (Long) principal;
    }
}
