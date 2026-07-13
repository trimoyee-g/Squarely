package com.squarely.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtAuthFilterTest {

    @Mock JwtService jwt;
    @Mock FilterChain chain;

    JwtAuthFilter filter;
    MockHttpServletRequest req;
    MockHttpServletResponse res;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthFilter(jwt);
        req = new MockHttpServletRequest();
        res = new MockHttpServletResponse();
    }

    @AfterEach
    void tearDown() { SecurityContextHolder.clearContext(); }

    private Authentication currentAuth() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test
    void validBearerTokenSetsUserIdPrincipal() throws Exception {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("42");
        when(jwt.parse("good-token")).thenReturn(claims);
        req.addHeader("Authorization", "Bearer good-token");

        filter.doFilterInternal(req, res, chain);

        assertNotNull(currentAuth());
        assertEquals(42L, currentAuth().getPrincipal());
        verify(chain).doFilter(req, res);
    }

    @Test
    void missingHeaderStaysAnonymous() throws Exception {
        filter.doFilterInternal(req, res, chain);
        assertNull(currentAuth());
        verify(chain).doFilter(req, res);   // request still proceeds
    }

    @Test
    void invalidTokenStaysAnonymousButContinues() throws Exception {
        when(jwt.parse("bad")).thenThrow(new JwtException("bad signature"));
        req.addHeader("Authorization", "Bearer bad");

        filter.doFilterInternal(req, res, chain);

        assertNull(currentAuth());
        verify(chain).doFilter(req, res);
    }

    /** A non-Bearer scheme is not our token — don't try to parse it, just stay anonymous. */
    @Test
    void nonBearerAuthorizationHeaderStaysAnonymous() throws Exception {
        req.addHeader("Authorization", "Basic dXNlcjpwYXNz");

        filter.doFilterInternal(req, res, chain);

        assertNull(currentAuth());
        verifyNoInteractions(jwt);
        verify(chain).doFilter(req, res);
    }

    @Test
    void nonNumericSubjectStaysAnonymous() throws Exception {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("not-a-number");
        when(jwt.parse("weird")).thenReturn(claims);
        req.addHeader("Authorization", "Bearer weird");

        filter.doFilterInternal(req, res, chain);
        assertNull(currentAuth());
        verify(chain).doFilter(req, res);
    }
}
