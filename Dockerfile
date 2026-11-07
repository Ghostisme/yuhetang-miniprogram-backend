FROM node:22-bookworm-slim AS web
WORKDIR /build
COPY yuhetang-miniprogram-admin/package.json yuhetang-miniprogram-admin/package-lock.json ./
RUN npm ci
COPY yuhetang-miniprogram-admin/index.html yuhetang-miniprogram-admin/tsconfig.json yuhetang-miniprogram-admin/vite.config.ts ./
COPY yuhetang-miniprogram-admin/src ./src
COPY yuhetang-miniprogram-admin/public ./public
RUN npm run build

FROM maven:3.9-eclipse-temurin-17 AS java
WORKDIR /build/backend
COPY yuhetang-miniprogram-backend/pom.xml ./
RUN mvn -B -ntp dependency:go-offline
COPY yuhetang-miniprogram-backend/src ./src
COPY --from=web /build/dist /build/dist
COPY --from=web /build/public/media /build/public/media
RUN mvn -B -ntp -Prelease -Dfrontend.dist=/build/dist -Dfrontend.media=/build/public/media -DskipTests package

FROM eclipse-temurin:17-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
RUN groupadd --gid 10001 yuwatang && useradd --uid 10001 --gid 10001 --no-create-home yuwatang && mkdir -p /app/data/uploads && chown -R 10001:10001 /app/data
WORKDIR /app
COPY --from=java /build/backend/target/reservation-1.0.0.jar app.jar
USER 10001:10001
EXPOSE 8790 9001
HEALTHCHECK --interval=20s --timeout=4s --start-period=60s --retries=3 CMD curl --fail --silent --max-time 3 http://127.0.0.1:8790/api/health || exit 1
ENTRYPOINT ["java","-XX:MaxRAMPercentage=65","-XX:+ExitOnOutOfMemoryError","-Dfile.encoding=UTF-8","-jar","/app/app.jar"]
