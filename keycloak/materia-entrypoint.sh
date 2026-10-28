#!/bin/bash
# ===================================================================
# Starts Keycloak once the values the materia realm's placeholders read are there (keycloak/materia-realm.json).
# Keycloak would otherwise keep a missing one as its placeholder text: a client secret "${SERVICE_CLIENT_..._SECRET}",
# which anyone can read in the repository. The realm is imported at the first start only, but the check runs at
# every start: the variables stay part of the deployment.
# ===================================================================
set -euo pipefail

# A secret: set, not empty, and not a placeholder left unfilled
secrets=(SERVICE_CLIENT_PROCUREMENT_SECRET SERVICE_CLIENT_MASTERDATA_SECRET SERVICE_CLIENT_FINANCE_SECRET
         SERVICE_CLIENT_ANALYTICS_SECRET SERVICE_CLIENT_IDENTITY_SECRET KEYCLOAK_TEST_CLIENT_SECRET)
# A setting: set and not empty
settings=(MATERIA_APP_URL MATERIA_DEV_APP_URL KEYCLOAK_SSL_REQUIRED KEYCLOAK_PKCE_METHOD KEYCLOAK_TEST_CLIENT_ENABLED)
# May be empty (no mail server), but must be set: an unset one would stay "${...}" in the realm
optional=(KEYCLOAK_SMTP_HOST KEYCLOAK_SMTP_PORT KEYCLOAK_SMTP_FROM KEYCLOAK_SMTP_AUTH KEYCLOAK_SMTP_USER
          KEYCLOAK_SMTP_PASSWORD KEYCLOAK_SMTP_STARTTLS)

missing=()
for name in "${secrets[@]}"; do
  value="${!name:-}"
  if [ -z "$value" ] || [[ "$value" == *'${'* ]] || [ "${#value}" -lt 16 ]; then
    missing+=("$name (a secret of at least 16 characters)")
  fi
done
for name in "${settings[@]}"; do
  [ -n "${!name:-}" ] || missing+=("$name")
done
for name in "${optional[@]}"; do
  [ -n "${!name+set}" ] || missing+=("$name (may be empty)")
done
case "${KEYCLOAK_TEST_CLIENT_ENABLED:-}" in
  true|false|"") ;;
  *) missing+=("KEYCLOAK_TEST_CLIENT_ENABLED (true or false)") ;;
esac

if [ "${#missing[@]}" -gt 0 ]; then
  echo "Keycloak not started: the materia realm needs these environment variables:" >&2
  printf '  - %s\n' "${missing[@]}" >&2
  exit 1
fi

exec /opt/keycloak/bin/kc.sh "$@"
