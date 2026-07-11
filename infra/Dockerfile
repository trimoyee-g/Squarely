# One Dockerfile for all services, parametrized by SERVICE build arg.
# ponytail: builds the whole reactor each time (simple, cache-friendly enough for
# a 4-module project). Split into per-service build contexts if image builds get slow.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
COPY common/pom.xml common/pom.xml
COPY auth-service/pom.xml auth-service/pom.xml
COPY group-service/pom.xml group-service/pom.xml
COPY ledger-service/pom.xml ledger-service/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml
RUN mvn -q -e -B dependency:go-offline || true
COPY . .
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:17-jre AS run
ARG SERVICE
WORKDIR /app
COPY --from=build /src/${SERVICE}/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
