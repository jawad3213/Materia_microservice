-- ===================================================================
-- Keycloak signs users in, keeps their sessions and resets their passwords (docs/architecture/keycloak-migration.md):
-- identity's own refresh tokens and password reset tokens have had no reader since. Dropped with their indexes and
-- foreign keys.
--
-- users.password_hash and users.must_change_password stay until every environment has imported its accounts into
-- Keycloak (KeycloakAccountImport reads them).
-- ===================================================================
drop table if exists identity.refresh_tokens;
drop table if exists identity.password_reset_tokens;
