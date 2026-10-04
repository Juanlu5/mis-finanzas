package com.misfinanzas.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** SecurityConfig's promise: HTTP Basic on every request, since the app is reachable from the whole home WiFi. */
class SecurityApiTest extends ApiTestBase {

    @ParameterizedTest
    @ValueSource(strings = {
            "/", "/index.html", "/static/app.js", "/api/transactions", "/api/summary", "/api/summary/monthly",
            "/api/categories", "/api/rules", "/api/accounts", "/api/auth/whoami", "/api/backup/download"})
    void everyGetRequiresCredentials(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @Test
    void writesRequireCredentialsToo() throws Exception {
        mockMvc.perform(post("/api/rules").param("keyword", "x").param("category", "Ocio"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/transactions/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(multipart("/api/import/preview")
                        .file(new MockMultipartFile("file", "a.csv", "text/csv", "a;b\n1;2".getBytes())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void challengeAdvertisesTheRealm() throws Exception {
        mockMvc.perform(get("/api/transactions"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", containsString("Mis Finanzas")));
    }

    @Test
    void wrongPasswordOrUnknownUserIsRejected() throws Exception {
        mockMvc.perform(get("/api/transactions").with(httpBasic("admin", "nope"))).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/transactions").with(httpBasic("ghost", PASSWORD))).andExpect(status().isUnauthorized());
    }

    @Test
    void validCredentialsGetThrough() throws Exception {
        mockMvc.perform(get("/api/transactions").with(auth())).andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/whoami").with(auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"));
    }

    @Test
    void frontendIsServedToAuthenticatedUsers() throws Exception {
        mockMvc.perform(get("/static/app.js").with(auth())).andExpect(status().isOk());
        mockMvc.perform(get("/index.html").with(auth())).andExpect(status().isOk());
    }

    @Test
    void noSessionCookieIsIssued() throws Exception {
        mockMvc.perform(get("/api/transactions").with(auth()))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void everyResponseCarriesTheContentSecurityPolicy() throws Exception {
        for (String path : new String[] {"/index.html", "/static/app.js", "/api/transactions"}) {
            mockMvc.perform(get(path).with(auth()))
                    .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'")))
                    .andExpect(header().string("Content-Security-Policy", containsString("connect-src 'self'")))
                    .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")));
        }
    }

    // The policy only runs scripts from files: an inline <script> (or onclick="...") in the page would be
    // silently blocked by the browser, and nothing else would notice.
    @Test
    void thePageHasNoInlineScriptsTheContentSecurityPolicyWouldBlock() throws Exception {
        String html = mockMvc.perform(get("/index.html").with(auth())).andReturn().getResponse().getContentAsString();

        assertThat(html).isNotBlank();
        assertThat(java.util.regex.Pattern.compile("<script(?![^>]*\\bsrc=)[^>]*>").matcher(html).find())
                .as("inline <script>").isFalse();
        assertThat(java.util.regex.Pattern.compile("\\son[a-z]+\\s*=", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html).find())
                .as("inline event handler attribute").isFalse();
    }
}
