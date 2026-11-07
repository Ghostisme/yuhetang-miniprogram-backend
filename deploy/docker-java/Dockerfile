FROM eclipse-temurin:17-jre-jammy

LABEL com.yuwatang.component="java-api"

WORKDIR /app
COPY reservation.jar entrypoint.sh healthcheck.sh check-db-target.sh /app/
RUN chmod 0444 /app/reservation.jar && chmod 0555 /app/*.sh

USER 10001:10001
EXPOSE 8790 8791
STOPSIGNAL SIGTERM
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 CMD ["/bin/bash", "/app/healthcheck.sh"]
ENTRYPOINT ["/bin/bash", "/app/entrypoint.sh"]
