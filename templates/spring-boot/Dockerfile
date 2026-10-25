# Test + package with Maven, run the fat jar on a small non-root JRE image
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package

FROM eclipse-temurin:21-jre-alpine
RUN apk upgrade --no-cache && addgroup -S app -g 10001 && adduser -S app -u 10001 -G app
WORKDIR /app
COPY --from=build /src/target/app.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
