package com.misfinanzas.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

/**
 * Every request needs valid HTTP Basic credentials, same as the original
 * app.py's BasicAuthMiddleware (this app is reachable from the whole home
 * WiFi, not just localhost). No session/cookies: stateless, checked fresh
 * on every request, and CSRF doesn't apply without a session cookie.
 * HSTS is off: the certificate is self-signed (see TlsKeystoreEnvironmentPostProcessor), and a browser that
 * cached HSTS for this host would refuse the "continue anyway" button if the certificate were ever
 * regenerated, locking the user out of their own app.
 * Repeated wrong passwords from one address lock it out for a while (LoginAttemptLimiter).
 */
@Configuration
public class SecurityConfig {

    /**
     * A second lock behind escaping (escapeHtml/escapeAttr in app.js): even if some day a description or a
     * column name reached the page as markup, the browser would run no script that isn't one of the app's own
     * files, and send nothing anywhere but this server. Everything the page uses is local (vendored libraries
     * and fonts), so it can be this strict. Styles allow 'unsafe-inline' for the style="" attributes in the
     * markup; styles can't run code. Opening the bank's page is a navigation, which this doesn't restrict.
     */
    static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self'",
            "font-src 'self'",
            "connect-src 'self'",
            "object-src 'none'",
            "base-uri 'none'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, LoginAttemptLimiter limiter) throws Exception {
        http
                .addFilterBefore(new LoginThrottleFilter(limiter), BasicAuthenticationFilter.class)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .httpBasic(basic -> basic.realmName("Mis Finanzas"))
                .headers(headers -> headers
                        .httpStrictTransportSecurity(hsts -> hsts.disable())
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY)));
        return http.build();
    }
}
