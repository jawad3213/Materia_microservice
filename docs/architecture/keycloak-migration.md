# Authentication and authorization: Keycloak

Keycloak signs users in and issues every access token, users' and the services' own. Identity no longer checks
passwords or signs tokens: it keeps the user directory (names, roles, status) and the employees, and creates, renames
and disables the accounts in Keycloak under the same ids.

| | Who |
|---|---|
| Sign-in pages, password checks, password reset and change, sessions, refresh tokens | Keycloak (realm `materia`) |
| Access tokens (RS256), their keys (JWK set) | Keycloak |
| A service's own token (work no user started) | Keycloak, client credentials of the service's client |
| Roles and permissions in the tokens | Keycloak: realm roles, and the `materia-api` client's roles |
| Verifying tokens | Every service and the gateway, with the realm's keys, no call per request |
| Accounts: create, rename, disable; the user directory; employees | Identity (Keycloak's admin API, as its client `identity`) |

## Tokens

| Claim | Read as |
|---|---|
| `iss` | Must be the realm (`KEYCLOAK_ISSUER_URI`, e.g. `https://app.example.com/auth/realms/materia`) |
| `aud` | Must contain `materia-api` |
| `sub` | The user's id: the same as in identity's directory and every service's records |
| `realm_access.roles` | `ROLE_<role>`: `ROLE_ADMIN`, `ROLE_PURCHASER`, `ROLE_RECEIVER` |
| `resource_access.materia-api.roles` | The permissions: `invoice:read`, ... |
| realm role `SERVICE` | A service's own token, subject `service:<client id>`: accepted between services, refused by the REST API |

`KeycloakTokens` (materia-platform) and the gateway's `jwtDecoder` read them. `@PreAuthorize`, the gRPC policies
(`GrpcPolicies`) and the frontend's `hasPermission` see the same authorities as before Keycloak.

The roles and their permissions are in [`keycloak/materia-realm.json`](../../keycloak/materia-realm.json);
`RealmRolesTest` fails when they differ from `Role.java`.

## What is where

- `keycloak/Dockerfile`: Keycloak 26.7.5 with [`keycloak/bcrypt-provider`](../../keycloak/bcrypt-provider) (Keycloak
  verifies the BCrypt hashes identity stored, then re-hashes with its own algorithm at the first sign-in), built for
  PostgreSQL (schema `keycloak`, user `materia_keycloak` from `service-roles.sql`) and served under `/auth`.
- The realm is imported at Keycloak's **first** start, with placeholders filled from its environment: the app's
  address (`MATERIA_APP_URL`), `KEYCLOAK_SSL_REQUIRED`, `KEYCLOAK_PKCE_METHOD`, `KEYCLOAK_SMTP_*` and the clients'
  secrets (`SERVICE_CLIENT_<SERVICE>_SECRET`, `KEYCLOAK_TEST_CLIENT_SECRET`), `KEYCLOAK_TEST_CLIENT_ENABLED`. Later
  changes to the file reach an existing realm only through the admin console (or a re-import).
- Keycloak does not start while one of those is missing, a secret is under 16 characters or still a `${...}`
  placeholder (`keycloak/materia-entrypoint.sh`): Keycloak would otherwise keep the placeholder text, which the
  repository shows, as the client's secret.
- Clients: `materia-frontend` (the web app: authorization code with PKCE), `materia-api` (holds the permissions),
  `procurement`, `masterdata`, `finance`, `analytics` (client credentials), `identity` (realm-management manage-users;
  manage-realm for the account import), `materia-test-runner` (password grant for the tests in `tests/`; enabled
  where `KEYCLOAK_TEST_CLIENT_ENABLED` is true: locally, in staging and the Fargate test environment, not in
  production unless `keycloak_test_client_enabled` says so).
- The web app uses keycloak-js (`authService`): `/login` and `/forgot-password` send the browser to Keycloak's pages,
  the profile page's "Change password" to its update-password page. nginx proxies `/auth` to Keycloak and limits
  password checks and token requests per address.
- Identity: `KeycloakIdentityProvider` (the admin API), `KeycloakAccountImport` (the accounts from before Keycloak).

keycloak-js needs a secure context: HTTPS, or `localhost`. On plain HTTP elsewhere the web app cannot sign anyone in
(the tests in `tests/` still can, with the password grant).

## Running it locally

```sh
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build
```

Keycloak is on http://localhost:8180/auth (admin console: `KEYCLOAK_ADMIN_*`, `admin`/`admin` by default). With the
dev profile identity creates the three dev accounts there (`APP_SEED_PASSWORD`). A token for curl:

```sh
TOKEN=$(curl -s http://localhost:8180/auth/realms/materia/protocol/openid-connect/token \
  -d client_id=materia-test-runner -d "client_secret=${KEYCLOAK_TEST_CLIENT_SECRET:-materia-test-runner-local}" \
  -d grant_type=password -d username=admin@materia.com -d "password=$APP_SEED_PASSWORD" | jq -r .access_token)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8090/api/v1/purchase-orders
```

The Vite dev server reads `VITE_KEYCLOAK_URL` from `frontend/.env.development` (http://localhost:8180/auth).

## Rolling it out to an environment

Everyone signs in again once: identity's tokens are no longer accepted.

1. **HTTPS first** (see `aws/README.md`, HTTPS): `app_url` must be `https://...` for the web app's sign-in.
2. `terraform apply`: the Keycloak repository, its database user's and admin's passwords, identity's and the test
   runner's client secrets, `KEYCLOAK_ISSUER_URI` and the realm's settings in Parameter Store. Optionally
   `KEYCLOAK_SMTP_HOST` (and the other `KEYCLOAK_SMTP_*`) in `app_config`, for Keycloak's password reset e-mails.
3. Deploy. `deploy.sh` applies the database users (the `keycloak` schema), starts Keycloak, which imports the realm,
   then the services. At its start identity imports its accounts into Keycloak with their passwords:
   `docker compose logs identity | grep KEYCLOAK-IMPORT`. It retries at every start until all are there.
4. Set the GitHub secret `KEYCLOAK_TEST_CLIENT_SECRET` of the environment (`terraform output -raw
   keycloak_test_client_secret`): the CD's smoke, load and security tests sign in with it. Production keeps the
   client off (`keycloak_test_client_enabled = false`): its smoke test then skips the signed-in checks.
5. Once every account is imported, remove `manage-realm` from identity's service account (admin console: Clients,
   `identity`, Service account roles): it only needs `manage-users` from then on.

**Rolling back** past this release needs the database as it was before it (the EBS snapshot): identity forgets the
password hashes once Keycloak has them, so an older identity could no longer sign anyone in.

## Later

- `refresh_tokens` and `password_reset_tokens` are dropped (identity V5). Drop `users.password_hash` and
  `users.must_change_password` once every environment has imported its accounts.
- Roles have two copies: Keycloak's (what tokens carry) and `identity.users.role` (the directory). A role changed in
  Keycloak's admin console is not reflected in identity; change roles through identity only, until they are synced.
- A new account's temporary password is e-mailed by identity. Keycloak can send a link to choose one instead
  (`execute-actions-email`), once every environment has Keycloak's SMTP settings.
- The realm is configured once, at Keycloak's first start: applying later changes on every deploy needs a
  configuration tool (keycloak-config-cli).
- Keycloak's first administrator (`admin`, `KEYCLOAK_ADMIN_PASSWORD`) is temporary: create a permanent one in the
  master realm and remove it.
