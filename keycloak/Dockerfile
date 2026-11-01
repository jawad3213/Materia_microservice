# ===================================================================
# Keycloak for Materia: the official image with the BCrypt password hash provider (identity's stored hashes keep
# working after the accounts are imported), built for PostgreSQL and served under /auth (behind the frontend's
# nginx in AWS, directly on KEYCLOAK_PORT locally). Imports the materia realm on first start.
# Build context: this directory.
# ===================================================================

# -------------------------------------------------------------------
# Stage 1: the BCrypt provider
# -------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS provider
WORKDIR /build
COPY bcrypt-provider/pom.xml .
RUN mvn -B -q dependency:go-offline
COPY bcrypt-provider/src src
RUN mvn -B -q package

# -------------------------------------------------------------------
# Stage 2: Keycloak, optimised once for these build options (start --optimized then skips it)
# -------------------------------------------------------------------
FROM quay.io/keycloak/keycloak:26.7.5 AS builder
ENV KC_DB=postgres \
    KC_HEALTH_ENABLED=true \
    KC_METRICS_ENABLED=true \
    KC_HTTP_RELATIVE_PATH=/auth
COPY --from=provider /build/target/keycloak-bcrypt-*.jar /opt/keycloak/providers/
RUN /opt/keycloak/bin/kc.sh build

# -------------------------------------------------------------------
# Stage 3: the image
# -------------------------------------------------------------------
FROM quay.io/keycloak/keycloak:26.7.5
COPY --from=builder /opt/keycloak/ /opt/keycloak/
COPY materia-realm.json /opt/keycloak/data/import/materia-realm.json
# Refuses to start when a value the realm needs is missing (a client secret would stay its placeholder text)
COPY --chmod=755 materia-entrypoint.sh /opt/keycloak/bin/materia-entrypoint.sh

# 8080: sign-in pages, tokens, admin console (/auth); 9000: health (/auth/health/ready) and metrics
EXPOSE 8080 9000

ENTRYPOINT ["/opt/keycloak/bin/materia-entrypoint.sh"]
CMD ["start", "--optimized", "--import-realm"]
