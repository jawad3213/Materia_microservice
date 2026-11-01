package com.materia.backend.infrastructure.grpc;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** A service's own token comes from Keycloak's token endpoint, by the client credentials grant. */
class KeycloakServiceTokensTest {

    private static final String REALM = "http://keycloak:8080/realms/materia";
    private static final String TOKEN_ENDPOINT = REALM + "/protocol/openid-connect/token";

    private final RestClient.Builder http = RestClient.builder();
    private final MockRestServiceServer keycloak = MockRestServiceServer.bindTo(http).build();
    private final KeycloakServiceTokens tokens = new KeycloakServiceTokens(http, REALM, "procurement", "s3cret");

    @Test
    @DisplayName("keycloak: the client credentials grant, with this service's id and secret, gives its token")
    void clientCredentials_giveTheToken() {
        keycloak.expect(requestTo(TOKEN_ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formDataContains(Map.of(
                        "grant_type", "client_credentials", "client_id", "procurement", "client_secret", "s3cret")))
                .andRespond(withSuccess("{\"access_token\":\"the-token\",\"expires_in\":300,\"token_type\":\"Bearer\"}",
                        MediaType.APPLICATION_JSON));

        ServiceTokenSource.IssuedServiceToken issued = tokens.issue();

        assertThat(issued.token()).isEqualTo("the-token");
        assertThat(issued.expiresInSeconds()).isEqualTo(300);
        keycloak.verify();
    }

    @Test
    @DisplayName("keycloak: wrong credentials fail the call as UNAUTHENTICATED, Keycloak down as UNAVAILABLE")
    void failures_areGrpcStatuses() {
        keycloak.expect(requestTo(TOKEN_ENDPOINT)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        keycloak.expect(requestTo(TOKEN_ENDPOINT)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(catchThrowableOfType(StatusRuntimeException.class, tokens::issue).getStatus().getCode())
                .isEqualTo(Status.Code.UNAUTHENTICATED);
        assertThat(catchThrowableOfType(StatusRuntimeException.class, tokens::issue).getStatus().getCode())
                .isEqualTo(Status.Code.UNAVAILABLE);
    }

    @Test
    @DisplayName("keycloak: without a realm configured, no service token (UNAVAILABLE)")
    void unconfigured_isUnavailable() {
        KeycloakServiceTokens unconfigured = new KeycloakServiceTokens(RestClient.builder(), "", "procurement", "x");

        assertThat(catchThrowableOfType(StatusRuntimeException.class, unconfigured::issue).getStatus().getCode())
                .isEqualTo(Status.Code.UNAVAILABLE);
    }
}
