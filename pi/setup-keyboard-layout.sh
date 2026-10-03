#!/bin/bash
# Enables English + Russian layouts on the Pi, switched by the same hotkey as in the app.
# Needed for Cyrillic in both modes. Usage: setup-keyboard-layout.sh [alt_shift|ctrl_shift|win_space|caps]
set -euo pipefail

case "${1:-alt_shift}" in
    alt_shift) OPTION=grp:alt_shift_toggle ;;
    ctrl_shift) OPTION=grp:ctrl_shift_toggle ;;
    win_space) OPTION=grp:win_space_toggle ;;
    caps) OPTION=grp:caps_toggle ;;
    *) echo "Unknown hotkey: $1 (alt_shift|ctrl_shift|win_space|caps)"; exit 1 ;;
esac

if [ "$(id -u)" -ne 0 ]; then
    exec sudo "$0" "$@"
fi
LAYOUT=us,ru

# Console and X11 sessions.
set_kv() {
    if grep -q "^$1=" /etc/default/keyboard; then
        sed -i "s|^$1=.*|$1=\"$2\"|" /etc/default/keyboard
    else
        echo "$1=\"$2\"" >> /etc/default/keyboard
    fi
}
touch /etc/default/keyboard
set_kv XKBLAYOUT "$LAYOUT"
set_kv XKBVARIANT ","
set_kv XKBOPTIONS "$OPTION"

# Wayland (labwc) sessions: per-user environment file.
for home in /home/*; do
    user=$(basename "$home")
    id "$user" >/dev/null 2>&1 || continue
    env_file="$home/.config/labwc/environment"
    [ -d "$home/.config/labwc" ] || [ -d /etc/xdg/labwc ] || continue
    install -d -o "$user" -g "$user" "$home/.config/labwc"
    touch "$env_file"
    sed -i '/^XKB_DEFAULT_LAYOUT=/d;/^XKB_DEFAULT_VARIANT=/d;/^XKB_DEFAULT_OPTIONS=/d' "$env_file"
    printf 'XKB_DEFAULT_LAYOUT=%s\nXKB_DEFAULT_VARIANT=,\nXKB_DEFAULT_OPTIONS=%s\n' "$LAYOUT" "$OPTION" >> "$env_file"
    chown "$user:$user" "$env_file"
done

setupcon -k 2>/dev/null || true
echo "Layouts $LAYOUT with $OPTION configured. Reboot the Pi to apply: sudo reboot"
