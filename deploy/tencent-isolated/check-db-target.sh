#!/usr/bin/env bash
set -euo pipefail

# Run by systemd before Java. Do not accept a host MySQL URL or another schema.
expected='jdbc:mariadb://127.0.0.1:3307/yuwatang_reservation?useUnicode=true&characterEncoding=utf8&connectionTimeZone=Asia/Shanghai&allowPublicKeyRetrieval=true&sslMode=disable&connectTimeout=3000&socketTimeout=10000'
for override in SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD SPRING_DATASOURCE_JNDI_NAME SPRING_FLYWAY_URL SPRING_CONFIG_LOCATION SPRING_CONFIG_ADDITIONAL_LOCATION SPRING_APPLICATION_JSON JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS; do
    [[ ! -v $override ]] || {
        printf 'STOP: remove the alternative Spring/JVM configuration variable: %s\n' "$override" >&2; exit 1;
    }
done
[[ ${SPRING_PROFILES_ACTIVE:-} == production && ${APP_MODE:-} == production ]] || {
    printf 'STOP: the isolated deployment requires the production profile.\n' >&2; exit 1;
}
[[ ${DB_URL:-} == "$expected" && ${DB_USERNAME:-} == yuwatang_app ]] || {
    printf 'STOP: database target must be the dedicated loopback container on port 3307 and the yuwatang_reservation schema.\n' >&2
    exit 1
}
db_password=${DB_PASSWORD:-}
jwt_secret=${JWT_SECRET:-}
admin_password=${ADMIN_PASSWORD:-}
[[ ${#db_password} -ge 32 && ${#jwt_secret} -ge 32 ]] || {
    printf 'STOP: dedicated DB_PASSWORD and JWT_SECRET have not been generated.\n' >&2; exit 1;
}
[[ ${#admin_password} -ge 12 ]] || {
    printf 'STOP: set an independent ADMIN_PASSWORD of at least 12 characters.\n' >&2; exit 1;
}
printf 'Isolated database target check passed.\n'
