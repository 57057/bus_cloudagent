#!/bin/sh
# Run as root only on the dedicated application host, with approved images already loaded.
set -eu
[ "$(id -u)" = 0 ] || { echo 'Run as root'; exit 1; }
[ "$(uname -s)" = Linux ] && [ "$(uname -m)" = x86_64 ] || exit 1
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
[ -s bootstrap.json ] && [ -s runner.py ] && [ -s runner.service ]
python3 - <<'PY'
import json,re,subprocess
from urllib.parse import urlsplit
c=json.load(open('bootstrap.json'))
u=urlsplit(c['base_url'])
local_demo=(u.scheme=='http' and u.hostname=='127.0.0.1' and u.port==8080 and c.get('allow_local_http') is True and c.get('shared_control_plane_demo') is True)
assert u.hostname and not u.username and not u.password and not u.path
assert (u.scheme=='https' and not c.get('allow_local_http')) or local_demo, 'HTTPS required except explicit same-host loopback demo'
assert re.fullmatch(r'[a-f0-9]{64}',c['registration_token'])
assert re.fullmatch(r'[A-Za-z0-9.-]+',c['public_host'])
assert 1024<=int(c['application_port'])<=65535
for k in ('backend_image','frontend_image'):
    assert re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9./:_@-]{0,200}',c[k])
    subprocess.run(['docker','image','inspect',c[k]],check=True,stdout=subprocess.DEVNULL)
PY
# Refuse to replace journals/credentials or adopt a host already running another executor.
[ ! -e /var/lib/cloudagent-runner/config.json ] && [ ! -e /var/lib/cloudagent-runner/bootstrap.json ] || { echo 'Existing runner detected; preserve its identity/journal'; exit 1; }
if ! id cloudagent-runner >/dev/null 2>&1; then useradd --system --user-group --home-dir /var/lib/cloudagent-runner --shell /usr/sbin/nologin cloudagent-runner; fi
install -d -m 700 -o cloudagent-runner -g cloudagent-runner /var/lib/cloudagent-runner
install -d -m 755 /opt/cloudagent-runner
install -m 644 runner.py /opt/cloudagent-runner/runner.py
install -m 600 -o cloudagent-runner -g cloudagent-runner bootstrap.json /var/lib/cloudagent-runner/bootstrap.json
install -m 644 runner.service /etc/systemd/system/cloudagent-runner.service
systemctl daemon-reload
systemctl enable --now cloudagent-runner
echo 'Runner started. Verify registration and IDLE in the platform before listing inventory.'
