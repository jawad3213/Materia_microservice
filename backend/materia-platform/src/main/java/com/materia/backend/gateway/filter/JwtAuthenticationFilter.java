package com.materia.backend.gateway.filter;

import com.materia.backend.gateway.security.AccessTokens;
import com.materia.backend.gateway.security.VerifiedToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.materia.backend.gateway.security.BearerTokenAuthentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * 🔹 JWT AUTHENTICATION FILTER — STATELESS (API GATEWAY)
 *
 * Intercepts every incoming request at the API Gateway level to:
 * 1. Extract the Bearer token from the Authorization header.
 * 2. Validate token integrity and expiration via AccessTokens (Keycloak's realm keys).
 * 3. Extract User ID (UUID) and Roles directly from the JWT claims.
 * 4. Populate the SecurityContext — ZERO database call.
 *
 * In a microservice architecture, the Gateway never queries the auth database.
 * All user identity information needed for authorization is embedded in the
 * self-contained JWT token Keycloak signed, verified with its public key (RS256).
 *
 * ┌────────────────────────────────────────────────────────────┐
 * │  JWT Payload (claims)                                       │
 * │  { sub: "uuid", realm_access.roles, resource_access, exp }  │
 * └────────────────────────────────────────────────────────────┘
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final AccessTokens accessTokens;

    public JwtAuthenticationFilter(AccessTokens accessTokens) {
        this.accessTokens = accessTokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // Skip if already authenticated (e.g. from a previous filter)
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = extractTokenFromRequest(request);

        if (StringUtils.hasText(token)) {
            Optional<VerifiedToken> verified = accessTokens.verify(token);
            if (verified.isPresent() && verified.get().service()) {
                // Service tokens travel between services only; a client never acts as one.
                log.warn("Gateway: refused a service token on [{}]", request.getRequestURI());
            } else if (verified.isPresent()) {
                try {
                    // Extract identity directly from JWT claims — NO database call
                    UUID userId = UUID.fromString(verified.get().subject());

                    // The token is kept, so calls to other services carry the user's own token.
                    BearerTokenAuthentication authentication =
                            new BearerTokenAuthentication(userId, token, verified.get().authorities());
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                    SecurityContextHolder.getContext().setAuthentication(authentication);

                    log.debug("Gateway: authenticated user [{}] with roles {} for path [{}]",
                            userId, verified.get().authorities(), request.getRequestURI());

                } catch (Exception e) {
                    log.error("Gateway: failed to authenticate user from JWT token: {}", e.getMessage());
                }
            } else {
                log.debug("Gateway: invalid or expired JWT token for path [{}]", request.getRequestURI());
            }
        }

        filterChain.doFilter(request, response);
    }

    private String extractTokenFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
