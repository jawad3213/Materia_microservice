# ===================================================================
# Dockerfile for the Materia identity service (Java 21)
# Uses pre-built JAR from local target/ directory
# ===================================================================
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Run as non-root user for security
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# Copy pre-built executable jar from local target/
ARG JAR_FILE=target/*.jar
COPY --chown=spring:spring ${JAR_FILE} app.jar

# 8082: the REST API (sign-in, accounts, employees, JWK set); 9091: gRPC (identity.v1) for the other services
EXPOSE 8082 9091

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
