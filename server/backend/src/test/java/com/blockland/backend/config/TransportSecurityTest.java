package com.blockland.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.InetAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TransportSecurityTest {
    private static final String STRONG_ADMIN = "aV3ry-l0ng-random-admin-token-xyz";
    private static final String STRONG_SERVER = "aV3ry-l0ng-random-server-token-xyz";

    @Test
    void loopbackMayRunPlainHttpWithDevelopmentTokens() throws Exception {
        assertThat(TransportSecurity.problems(InetAddress.getByName("127.0.0.1"), false, false, "change-me-admin", "change-me-server"))
                .isEmpty();
        assertThat(TransportSecurity.problems(InetAddress.getByName("::1"), false, false, "a", "s")).isEmpty();
    }

    @Test
    void beyondThisMachineNeedsHttpsAndStrongTokens() throws Exception {
        // all interfaces (no server.address), plain HTTP, default tokens: three reasons to refuse
        assertThat(TransportSecurity.problems(null, false, false, "change-me-admin", "change-me-server")).hasSize(3);
        assertThat(TransportSecurity.problems(InetAddress.getByName("0.0.0.0"), false, false, STRONG_ADMIN, STRONG_SERVER))
                .singleElement().asString().contains("HTTPS");
        // HTTPS with strong tokens: fine
        assertThat(TransportSecurity.problems(null, true, false, STRONG_ADMIN, STRONG_SERVER)).isEmpty();
        // a trusted private network may skip HTTPS, never the strong tokens
        assertThat(TransportSecurity.problems(null, false, true, STRONG_ADMIN, STRONG_SERVER)).isEmpty();
        List<String> weak = TransportSecurity.problems(null, true, true, "short-token", STRONG_SERVER);
        assertThat(weak).singleElement().asString().contains("admin-token");
    }

    @Test
    void weakTokensAreDefaultsBlanksAndShortOnes() {
        assertThat(TransportSecurity.weakToken(null)).isTrue();
        assertThat(TransportSecurity.weakToken(" ")).isTrue();
        assertThat(TransportSecurity.weakToken("change-me-admin")).isTrue();
        assertThat(TransportSecurity.weakToken("x".repeat(TransportSecurity.MIN_TOKEN_LENGTH - 1))).isTrue();
        assertThat(TransportSecurity.weakToken(STRONG_ADMIN)).isFalse();
    }

    @Test
    void defaultTokensWorkOnlyForDirectLocalRequests() throws Exception {
        TokenAuthFilter filter = new TokenAuthFilter(properties("change-me-admin", "change-me-server"));
        assertThat(status(filter, admin("127.0.0.1", "change-me-admin"))).isEqualTo(200);
        assertThat(status(filter, admin("0:0:0:0:0:0:0:1", "change-me-admin"))).isEqualTo(200);
        // another machine, or this one through a reverse proxy: refused even with the right token
        assertThat(status(filter, admin("192.168.1.20", "change-me-admin"))).isEqualTo(403);
        MockHttpServletRequest proxied = admin("127.0.0.1", "change-me-admin");
        proxied.addHeader("X-Forwarded-For", "203.0.113.7");
        assertThat(status(filter, proxied)).isEqualTo(403);
        MockHttpServletRequest server = new MockHttpServletRequest("GET", "/api/server/bootstrap");
        server.setRemoteAddr("10.0.0.5");
        server.addHeader("X-Server-Token", "change-me-server");
        assertThat(status(filter, server)).isEqualTo(403);
    }

    @Test
    void strongTokensWorkFromAnywhereAndWrongOnesNowhere() throws Exception {
        TokenAuthFilter filter = new TokenAuthFilter(properties(STRONG_ADMIN, STRONG_SERVER));
        assertThat(status(filter, admin("203.0.113.7", STRONG_ADMIN))).isEqualTo(200);
        assertThat(status(filter, admin("203.0.113.7", STRONG_ADMIN + "x"))).isEqualTo(401);
        assertThat(status(filter, admin("127.0.0.1", "change-me-admin"))).isEqualTo(401);
    }

    @Test
    void responsesCarrySecurityHeadersAndHstsOnlyOverHttps() throws Exception {
        SecurityHeadersFilter filter = new SecurityHeadersFilter();
        MockHttpServletResponse plain = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/"), plain, new MockFilterChain());
        assertThat(plain.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(plain.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(plain.getHeader("Strict-Transport-Security")).isNull();
        MockHttpServletRequest secure = new MockHttpServletRequest("GET", "/");
        secure.setSecure(true);
        MockHttpServletResponse https = new MockHttpServletResponse();
        filter.doFilter(secure, https, new MockFilterChain());
        assertThat(https.getHeader("Strict-Transport-Security")).startsWith("max-age=");
    }

    private static BlockLandProperties properties(String admin, String server) {
        return new BlockLandProperties("data", admin, server, null, false, List.of());
    }

    private static MockHttpServletRequest admin(String remote, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/maps");
        request.setRemoteAddr(remote);
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    private static int status(TokenAuthFilter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }
}
