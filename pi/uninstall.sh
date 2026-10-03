#!/bin/bash
set -euo pipefail
if [ "$(id -u)" -ne 0 ]; then
    exec sudo "$0" "$@"
fi
systemctl disable --now pitouch 2>/dev/null || true
rm -f /etc/systemd/system/pitouch.service
rm -rf /usr/local/lib/pitouch
systemctl daemon-reload
echo "PiTouch server removed."
