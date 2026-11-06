#!/bin/sh
# Only a dedicated Linux control-plane host. Does not install Docker or buy resources.
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
[ "$(uname -s)" = Linux ] && [ "$(uname -m)" = x86_64 ] || { echo 'Linux x86_64 required'; exit 1; }
memory=$(awk '/MemTotal/{print $2}' /proc/meminfo)
[ "$memory" -ge 15000000 ] || { echo 'This combined demo requires at least 16GB RAM; use separate services on smaller hosts'; exit 1; }
docker info >/dev/null
python3 preflight.py
docker compose --env-file .env config --quiet
[ -s secrets/application.key ] && [ -s business.jar ] && [ -s site/index.html ]
# Secrets are read only by container UID 10001; never make them world-readable.
chmod 700 secrets
chmod 600 .env secrets/*
chown -R 10001:10001 secrets
docker compose --env-file .env up -d --build
echo 'Services requested. Verify HTTPS, health, model connectivity and the delivery flow before declaring success.'
