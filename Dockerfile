FROM maven:3.9-eclipse-temurin-17 AS java
WORKDIR /build/backend
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
RUN mvn -B -ntp -Papi-release verify

FROM eclipse-temurin:17-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
RUN groupadd --gid 10001 yuwatang && useradd --uid 10001 --gid 10001 --no-create-home yuwatang && mkdir -p /app/data/uploads && chown -R 10001:10001 /app/data
WORKDIR /app
COPY --from=java /build/backend/target-api/reservation-api-1.0.0.jar app.jar
USER 10001:10001
EXPOSE 8790 9001
HEALTHCHECK --interval=20s --timeout=4s --start-period=60s --retries=3 CMD curl --fail --silent --max-time 3 http://127.0.0.1:8790/api/health || exit 1
ENTRYPOINT ["java","-XX:MaxRAMPercentage=65","-XX:+ExitOnOutOfMemoryError","-Dfile.encoding=UTF-8","-jar","/app/app.jar"]
