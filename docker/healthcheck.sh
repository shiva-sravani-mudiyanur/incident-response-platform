#!/bin/sh
set -eu

# SERVER_PORT is the application's container port, never its published host port.
port=${SERVER_PORT:?SERVER_PORT must be set}
case "$port" in
    *[!0-9]*|'') echo 'SERVER_PORT must be a TCP port number' >&2; exit 1 ;;
esac
[ "$port" -ge 1 ] && [ "$port" -le 65535 ] || exit 1

# Bypass proxy variables for this local probe. Do not follow redirects or retry.
# Actuator reports DOWN with HTTP 503; only a direct HTTP 200 is healthy.
status=$(curl --fail --silent --show-error --noproxy '*' \
    --connect-timeout 2 --max-time 5 --output /dev/null --write-out '%{http_code}' \
    "http://127.0.0.1:${port}/actuator/health") || exit 1
[ "$status" = 200 ]
