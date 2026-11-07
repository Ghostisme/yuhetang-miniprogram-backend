"""Export the current systemd-decoded application settings to a Docker env file.

Use systemd-run with EnvironmentFile; never source or eval the environment file.
The exporter prints paths/status only, never configuration values.
"""
import argparse
import os
from pathlib import Path
import re
import sys

EXPECTED_URL = (
    "jdbc:mariadb://127.0.0.1:3307/yuwatang_reservation?useUnicode=true&"
    "characterEncoding=utf8&connectionTimeZone=Asia/Shanghai&allowPublicKeyRetrieval=true&"
    "sslMode=disable&connectTimeout=3000&socketTimeout=10000"
)
KEYS = frozenset("""
SPRING_PROFILES_ACTIVE APP_MODE PORT BIND_ADDRESS MANAGEMENT_PORT MANAGEMENT_BIND_ADDRESS
APP_DATA_DIR PUBLIC_URL DB_URL DB_USERNAME DB_PASSWORD JWT_SECRET ADMIN_PASSWORD
DB_POOL_SIZE DB_POOL_IDLE HTTP_THREADS HTTP_CONNECTIONS MAX_ACTIVE_REQUESTS
MAX_ACTIVE_WRITES MAX_ACTIVE_LOGINS MAX_ACTIVE_SESSIONS MAX_ACTIVE_IMPORTS
WECHAT_APP_ID WECHAT_APP_SECRET WECHAT_MESSAGE_TOKEN WECHAT_MESSAGE_AES_KEY
WECHAT_MESSAGE_ALLOW_PLAINTEXT CUSTOMER_TEMPLATE_ID CUSTOMER_REMINDER_TEMPLATE_ID
CUSTOMER_TEMPLATE_MAPPING CUSTOMER_REMINDER_TEMPLATE_MAPPING SERVICE_APP_ID
SERVICE_APP_SECRET SERVICE_TEMPLATE_ID SERVICE_OAUTH_URL SMS_SECRET_ID SMS_SECRET_KEY
SMS_APP_ID SMS_SIGN_NAME SMS_STAFF_TEMPLATE_ID SMS_CUSTOMER_TEMPLATE_ID SMS_REGION
SMS_STAFF_PARAMS SMS_CUSTOMER_PARAMS PMS_ENABLED PMS_API_KEY TRUSTED_PROXY_REGEX TZ
""".split())
OVERRIDES = frozenset("""
SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD
SPRING_DATASOURCE_JNDI_NAME SPRING_FLYWAY_URL SPRING_CONFIG_LOCATION
SPRING_CONFIG_ADDITIONAL_LOCATION SPRING_APPLICATION_JSON JAVA_TOOL_OPTIONS
JDK_JAVA_OPTIONS _JAVA_OPTIONS
""".split())
EXPECTED = {
    "SPRING_PROFILES_ACTIVE": "production", "APP_MODE": "production",
    "DB_URL": EXPECTED_URL, "DB_USERNAME": "yuwatang_app",
    "BIND_ADDRESS": "127.0.0.1", "PORT": "8790",
    "MANAGEMENT_BIND_ADDRESS": "127.0.0.1", "MANAGEMENT_PORT": "8791",
    "APP_DATA_DIR": "/var/lib/yuwatang-reservation",
}


class ExportError(ValueError):
    pass


def render(environment):
    for key in sorted(OVERRIDES & environment.keys()):
        raise ExportError("Remove the alternative Spring/JVM configuration variable: " + key)
    # Refuse to silently drop a newly introduced Spring/application setting.
    for key in environment:
        if key not in KEYS and re.match(
                r"^(?:SPRING_|APP_|DB_|JWT_|ADMIN_|WECHAT_|CUSTOMER_|SERVICE_|SMS_|PMS_|TRUSTED_PROXY_)", key):
            raise ExportError("Unsupported application configuration key: " + key)
    for key, expected in EXPECTED.items():
        if environment.get(key) != expected:
            raise ExportError("Existing dedicated database/loopback configuration is required: " + key)
    for key, minimum in (("DB_PASSWORD", 32), ("JWT_SECRET", 32), ("ADMIN_PASSWORD", 12)):
        if len(environment.get(key, "")) < minimum:
            raise ExportError("Required credential is missing or too short: " + key)
    values = {key: value for key, value in environment.items() if key in KEYS}
    values.setdefault("TZ", "Asia/Shanghai")
    for key, value in values.items():
        if any(character in value for character in ("\r", "\n", "\0")):
            raise ExportError("Docker env files require a single-line value: " + key)
        if value != value.strip():
            raise ExportError("Leading/trailing whitespace requires explicit operator review: " + key)
    return "".join(key + "=" + value + "\n" for key, value in sorted(values.items()))


def export(environment, output):
    content = render(environment)
    output = Path(output)
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(output, flags, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
        stream.write(content)
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    try:
        if not hasattr(os, "geteuid") or os.geteuid() != 0 or not os.environ.get("INVOCATION_ID"):
            raise ExportError("Run as root using the documented systemd-run EnvironmentFile command.")
        output = export(os.environ, arguments.output)
    except (ExportError, OSError) as error:
        # Do not include operating-system exception text that might contain values.
        message = str(error) if isinstance(error, ExportError) else type(error).__name__
        print("STOP: " + message, file=sys.stderr)
        return 1
    print("Created root-only Docker environment file: " + str(output))
    print("No database connection, application start or original configuration change was made.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
