# Authentication and authorization: moving to Keycloak

Identity signs every token today: it checks passwords, issues RS256 access tokens and opaque refresh tokens, resets
passwords, and issues service tokens to the other services (`identity.v1.ServiceTokens`). Authentication and the
role → permission mapping move to Keycloak, one step at a time, with Identity's tokens accepted until the last step.

What stays the same throughout: every service verifies tokens itself (no call per request), authorities are
`ROLE_<role>` plus permission strings (`invoice:read`), so `@PreAuthorize`, the gRPC policies (`GrpcPolicies`) and the
frontend's permission checks keep working unchanged; the token's subject is the user's UUID.

## How Keycloak's tokens map onto today's

| Today (Identity) | Keycloak (realm `materia`) |
|---|---|
| `Role` enum: ADMIN, PURCHASER, RECEIVER | Realm roles of the same names → `ROLE_ADMIN`, … |
| `Role.getPermissions()` | Roles of the client `materia-api`, made composites of the realm roles → `invoice:read`, … |
| `roles` claim (comma-separated) | `realm_access.roles` and `resource_access.materia-api.roles` |
| Service token: subject `service:<name>`, `svc: true` | Service account of client `<name>` (client credentials), realm role `SERVICE` → subject `service:<client id>` |
| Accepted by every service | Issuer must be the realm, `aud` must contain `materia-api` |

`KeycloakTokens` (materia-platform) and the gateway's `jwtDecoder` do this mapping. A token is routed by its issuer:
one naming the realm is verified with the realm's keys only, any other with Identity's.

The realm is [`keycloak/materia-realm.json`](../../keycloak/materia-realm.json). Its permissions mirror
`Role.java`: change both together until step 3 makes the realm the only source.

## Steps

1. **Both issuers accepted (done on `feat/keycloak-auth`).** Keycloak runs in the dev compose stack with the realm
   imported. The gateway, the REST APIs and gRPC accept Keycloak's tokens beside Identity's when
   `KEYCLOAK_ISSUER_URI` is set (off by default, so staging and production are unchanged). Nobody signs in through
   Keycloak yet.
2. **Service tokens from Keycloak.** `OutgoingCallerToken` gets its token by client credentials from Keycloak's token
   endpoint instead of `identity.v1.ServiceTokens`, with the same client ids and secrets. Then remove the
   `ServiceTokens` RPC, `ServiceTokenService` and `JwtTokenProvider.generateServiceToken`.
3. **Accounts in Keycloak.** Keycloak becomes the account store; Identity keeps the user directory (names, e-mails,
   roles for `UserDirectory`, `UserDisplayNames`) and employees, keyed by the Keycloak user id.
   - Existing users are imported with their current UUID as Keycloak id, so every `created_by` and every reference to a
     user stays valid.
   - Passwords: Identity stores BCrypt hashes, which Keycloak does not verify out of the box. Either install a BCrypt
     password-hash provider and import the hashes (nobody notices), or import without passwords and send everyone a
     reset e-mail. **To decide.**
   - Account administration (`AccountProvisioningService`: create, change role, disable) calls Keycloak's admin API;
     role changes and lockouts happen there.
4. **Frontend signs in with Keycloak.** Authorization code with PKCE (client `materia-frontend`). Keycloak's pages
   replace the login, forgot-password, reset-password and forced-change forms (the forced change becomes the
   `UPDATE_PASSWORD` required action). Tokens are refreshed by the OIDC library, not `/api/v1/auth/refresh`.
5. **Cut-over and clean-up.** Keycloak in staging and production (Terraform: container, its own database, realm
   configuration, secrets in SSM), then Identity's tokens are no longer accepted, and go: the sign-in, refresh,
   logout and password endpoints, `refresh_tokens` and `password_reset_tokens`, `JwtTokenProvider`'s signing, the
   JWK set endpoint and `JWT_PRIVATE_KEY`/`JWT_PUBLIC_KEY`.

## Trying step 1 locally

```sh
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d keycloak
# then the rest of the stack as usual; the admin console is at http://localhost:8180 (KEYCLOAK_ADMIN_*)

# A dev account's token, through the dev-only password-grant client
TOKEN=$(curl -s http://localhost:8180/realms/materia/protocol/openid-connect/token \
  -d client_id=materia-dev-tools -d grant_type=password \
  -d username=admin@materia.com -d "password=$APP_SEED_PASSWORD" | jq -r .access_token)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8090/api/v1/purchase-orders

# A service account's token (refused by the gateway, accepted between services)
curl -s http://localhost:8180/realms/materia/protocol/openid-connect/token \
  -d client_id=procurement -d "client_secret=$SERVICE_CLIENT_PROCUREMENT_SECRET" -d grant_type=client_credentials
```

Known gap until step 3: Keycloak's dev accounts have their own ids, not those Identity seeded, so user names from the
directory do not resolve for records created with a Keycloak token.

`materia-dev-tools` (password grant) exists only for local testing: the realm for staging and production must not
have it.
