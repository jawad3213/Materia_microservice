# ===================================================================
# Dockerfile for the Materia master data service (Java 21)
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

# 8083: the REST API (materials, categories, suppliers); 9092: gRPC (masterdata.v1) for the other services
EXPOSE 8083 9092

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
