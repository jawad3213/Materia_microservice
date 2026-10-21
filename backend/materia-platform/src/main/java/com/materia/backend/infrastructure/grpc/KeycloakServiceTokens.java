package com.materia.backend.infrastructure.grpc;

import io.grpc.Status;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

/**
 * A service's own tokens from Keycloak: the client credentials grant, for this service's client ({@code
 * app.service-client.id} and {@code .secret}, a confidential client of the realm with the realm role {@code SERVICE}).
 *
 * <p>Keycloak unreachable or refusing the credentials fails the call that needed the token as {@code UNAVAILABLE}
 * or {@code UNAUTHENTICATED}, as an unreachable service would.
 */
public class KeycloakServiceTokens implements ServiceTokenSource {

    private final RestClient http;
    private final String tokenUri;
    private final String clientId;
    private final String clientSecret;

    /**
     * @param realmUri where this service reaches the realm ({@code http://keycloak:8080/realms/materia}); blank when
     *                 Keycloak is not configured, and every request for a token then fails
     */
    public KeycloakServiceTokens(RestClient.Builder http, String realmUri, String clientId, String clientSecret) {
        this.http = http.build();
        this.tokenUri = StringUtils.hasText(realmUri) ? realmUri + "/protocol/openid-connect/token" : null;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    public IssuedServiceToken issue() {
        if (tokenUri == null) {
            throw Status.UNAVAILABLE.withDescription("app.keycloak.issuer-uri is not set: no service token")
                    .asRuntimeException();
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        Map<?, ?> response;
        try {
            response = http.post().uri(tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientResponseException e) {
            Status status = e.getStatusCode().is4xxClientError() ? Status.UNAUTHENTICATED : Status.UNAVAILABLE;
            throw status.withDescription("Keycloak refused a service token to client " + clientId + ": "
                    + e.getStatusCode()).asRuntimeException();
        } catch (RestClientException e) {
            throw Status.UNAVAILABLE.withDescription("Keycloak unreachable for a service token: " + e.getMessage())
                    .withCause(e).asRuntimeException();
        }
        if (response == null || !(response.get("access_token") instanceof String token)) {
            throw Status.UNAVAILABLE.withDescription("Keycloak answered without an access token").asRuntimeException();
        }
        long expiresIn = response.get("expires_in") instanceof Number seconds ? seconds.longValue() : 60;
        return new IssuedServiceToken(token, expiresIn);
    }
}
