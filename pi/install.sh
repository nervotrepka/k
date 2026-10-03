#!/bin/bash
# Installs the PiTouch server (only needed for the "Сервер на Pi" mode).
set -euo pipefail
cd "$(dirname "$0")"

if [ "$(id -u)" -ne 0 ]; then
    exec sudo "$0" "$@"
fi

apt-get update
apt-get install -y python3-evdev python3-dbus python3-gi

install -D -m 755 pitouch_server.py /usr/local/lib/pitouch/pitouch_server.py
install -m 644 pitouch.service /etc/systemd/system/pitouch.service
grep -qx uinput /etc/modules 2>/dev/null || echo uinput >> /etc/modules

systemctl daemon-reload
systemctl enable pitouch
systemctl restart pitouch

echo
echo "PiTouch server installed. Status: sudo systemctl status pitouch"
echo "Logs: journalctl -u pitouch -f"
