# ===================================================================
# Dockerfile for the Materia API gateway (Spring Cloud Gateway, Java 21)
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

# 8080: the API; 8081: actuator (health, metrics), inside the compose network only
EXPOSE 8080

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
