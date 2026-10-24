# Build stage - tests are skipped here because CI runs them and Testcontainers needs Docker
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Dependencies first, so a code-only change reuses this cached layer
COPY pom.xml .
RUN mvn -B --no-transfer-progress dependency:go-offline

COPY src ./src
RUN mvn -B --no-transfer-progress package -DskipTests

# Runtime stage - only the jar; all configuration comes from environment variables
FROM eclipse-temurin:17-jre
WORKDIR /app

RUN groupadd --system spring && useradd --system --gid spring spring
COPY --from=build /app/target/*.jar app.jar
USER spring

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]