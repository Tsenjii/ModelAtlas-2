#!/bin/sh
set -eu
# Run locally on the VPS as root: sudo sh vps/deploy/install.sh PATH_TO_BINARY
# Raw credentials are never accepted as script arguments.
[ "$(id -u)" -eq 0 ] || { echo "run as root" >&2; exit 1; }
[ "$#" -eq 1 ] || { echo "usage: install.sh ./modelatlas-vps-linux-amd64" >&2; exit 1; }
[ -f "$1" ] || { echo "binary missing" >&2; exit 1; }
if ! id modelatlas >/dev/null 2>&1; then
  useradd --system --home /var/lib/modelatlas --shell /usr/sbin/nologin modelatlas
fi
install -d -m 0700 -o modelatlas -g modelatlas /var/lib/modelatlas
install -d -m 0755 /opt/modelatlas
install -d -m 0700 /etc/modelatlas
install -m 0755 "$1" /opt/modelatlas/modelatlas-vps
if [ ! -f /etc/modelatlas/modelatlas.env ]; then
  cat >&2 <<'NOTICE'
Before starting, create /etc/modelatlas/modelatlas.env (chmod 600, root owner):
 MODELATLAS_ADMIN_TOKEN=<random long secret>
 MODELATLAS_MASTER_KEY=<base64 of 32 random bytes>
 Do not publish either value or put it in shell history / Git.
NOTICE
  exit 2
fi
chmod 600 /etc/modelatlas/modelatlas.env
install -m 0644 "$(dirname "$0")/modelatlas-vps.service" /etc/systemd/system/modelatlas-vps.service
systemctl daemon-reload
systemctl enable modelatlas-vps.service
systemctl restart modelatlas-vps.service
systemctl --no-pager status modelatlas-vps.service
