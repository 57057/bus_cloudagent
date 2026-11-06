#!/bin/sh
# Reload only the TLS proxy. The Node room process and in-memory rooms stay alive.
set -eu
if [ "$(docker inspect --format '{{.State.Running}}' video-two-proxy 2>/dev/null)" = true ]; then
    docker restart --timeout 5 video-two-proxy >/dev/null
fi
