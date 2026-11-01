-- ===================================================================
-- Passwords live in Keycloak (docs/architecture/keycloak-migration.md). A new account has no hash here, and an account
-- from before Keycloak loses its hash once KeycloakAccountImport has imported it there.
--
-- refresh_tokens and password_reset_tokens are no longer used (Keycloak keeps sessions and resets passwords); they
-- are dropped, with users.password_hash and users.must_change_password, once every environment has run the import.
-- ===================================================================
alter table identity.users alter column password_hash drop not null;
