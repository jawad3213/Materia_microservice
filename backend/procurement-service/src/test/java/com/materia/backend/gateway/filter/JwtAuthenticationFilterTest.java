package com.materia.backend.gateway.filter;

import com.materia.backend.gateway.security.BearerTokenAuthentication;
import com.materia.backend.gateway.security.JwtTokenProvider;
import com.materia.backend.support.fixtures.TestJwtKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.security.KeyPair;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The REST API accepts user tokens and keeps them for the calls made on the user's behalf; never service tokens. */
class JwtAuthenticationFilterTest {

    private static final KeyPair KEYS = TestJwtKeys.generate();

    private final JwtTokenProvider jwt = new JwtTokenProvider(TestJwtKeys.privatePem(KEYS), TestJwtKeys.publicPem(KEYS),
            900_000L);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwt);

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private Authentication authenticate(String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/invoices");
        request.addHeader("Authorization", "Bearer " + token);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test
    @DisplayName("security: a user's token signs them in and is kept for the calls made on their behalf")
    void userToken_isKept() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = jwt.generateAccessToken(userId);

        Authentication authentication = authenticate(token);

        assertThat(authentication).isInstanceOf(BearerTokenAuthentication.class);
        assertThat(authentication.getPrincipal()).isEqualTo(userId);
        assertThat(((BearerTokenAuthentication) authentication).getToken()).isEqualTo(token);
        assertThat(authentication.getCredentials()).isNull();
    }

    @Test
    @DisplayName("security: a service token, valid between services, signs no one in over the REST API")
    void serviceToken_isRefused() throws Exception {
        assertThat(authenticate(jwt.generateServiceToken("procurement"))).isNull();
    }
}
