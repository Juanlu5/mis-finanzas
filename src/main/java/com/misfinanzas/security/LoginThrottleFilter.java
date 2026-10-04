package com.misfinanzas.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Runs before HTTP Basic authentication (see SecurityConfig): refuses an address {@link LoginAttemptLimiter}
 * has locked out, with a 429 saying how long to wait, and otherwise tells the limiter how the request's
 * credentials went — a request that sent a password and got a 401 back failed, one that got anything else
 * succeeded. Requests with no credentials at all (the browser's first try, before it asks) don't count.
 * Not a Spring bean on purpose: as one, Spring Boot would also register it as a plain servlet filter.
 */
class LoginThrottleFilter extends OncePerRequestFilter {

    private final LoginAttemptLimiter limiter;

    LoginThrottleFilter(LoginAttemptLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String address = request.getRemoteAddr();
        Duration wait = limiter.lockedFor(address);
        if (!wait.isZero()) {
            // rounded up: telling someone to wait 14 minutes when 14 and a half are left would just fail again
            long seconds = (wait.toMillis() + 999) / 1000;
            long minutes = (seconds + 59) / 60;
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(seconds));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            // same {"detail": ...} shape as every other error (GlobalExceptionHandler)
            response.getWriter().write("{\"detail\":\"Demasiados intentos con una contraseña incorrecta desde este dispositivo. "
                    + "Espera " + minutes + (minutes == 1 ? " minuto" : " minutos") + " y vuelve a intentarlo.\"}");
            return;
        }
        chain.doFilter(request, response);
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) return;
        if (response.getStatus() == HttpStatus.UNAUTHORIZED.value()) limiter.failed(address);
        else limiter.succeeded(address);
    }
}
