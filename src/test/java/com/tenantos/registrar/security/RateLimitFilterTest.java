package com.tenantos.registrar.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintWriter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    @Mock
    private FilterChain filterChain;
    @Mock
    private HttpServletResponse response;

    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter(3, 60, "/onboarding");
    }

    private HttpServletRequest requestFor(String ip, String path) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn(ip);
        when(request.getRequestURI()).thenReturn(path);
        return request;
    }

    @Test
    void requestsUnderTheCap_allPassThroughToTheChain() throws Exception {
        for (int i = 0; i < 3; i++) {
            filter.doFilterInternal(requestFor("1.1.1.1", "/onboarding/token"), response, filterChain);
        }

        verify(filterChain, times(3)).doFilter(any(), any());
        verify(response, never()).setStatus(429);
    }

    @Test
    void theRequestThatExceedsTheCap_getsA429_andNeverReachesTheChain() throws Exception {
        PrintWriter writer = mock(PrintWriter.class);
        when(response.getWriter()).thenReturn(writer);

        for (int i = 0; i < 3; i++) {
            filter.doFilterInternal(requestFor("1.1.1.1", "/onboarding/token"), response, filterChain);
        }
        filter.doFilterInternal(requestFor("1.1.1.1", "/onboarding/token"), response, filterChain);

        verify(filterChain, times(3)).doFilter(any(), any());
        verify(response).setStatus(429);
        verify(writer).write("Too many requests");
    }

    @Test
    void requestsOutsideTheLimitedPrefix_areNeverCounted() throws Exception {
        for (int i = 0; i < 10; i++) {
            filter.doFilterInternal(requestFor("1.1.1.1", "/auth/token"), response, filterChain);
        }

        verify(filterChain, times(10)).doFilter(any(), any());
        verify(response, never()).setStatus(429);
    }

    @Test
    void differentIps_haveIndependentLimits() throws Exception {
        for (int i = 0; i < 3; i++) {
            filter.doFilterInternal(requestFor("1.1.1.1", "/onboarding/token"), response, filterChain);
        }
        // A different IP should still get through even though 1.1.1.1 just hit its cap.
        filter.doFilterInternal(requestFor("2.2.2.2", "/onboarding/token"), response, filterChain);

        verify(filterChain, times(4)).doFilter(any(), any());
        verify(response, never()).setStatus(429);
    }
}
