#!/usr/bin/env bash
# Removes the OpenBased service.
#
#   sudo packaging/linux/uninstall.sh           # keeps configuration and data
#   sudo packaging/linux/uninstall.sh --purge   # also deletes /etc/openbased, /var/lib/openbased and the user
set -euo pipefail

[[ $EUID -eq 0 ]] || { echo "error: run as root" >&2; exit 1; }

PURGE=false
[[ "${1:-}" == "--purge" ]] && PURGE=true

if systemctl list-unit-files openbased.service >/dev/null 2>&1; then
  systemctl disable --now openbased 2>/dev/null || true
fi
rm -f /etc/systemd/system/openbased.service
systemctl daemon-reload
rm -rf /opt/openbased
echo "==> Removed the service and /opt/openbased"

if $PURGE; then
  rm -rf /etc/openbased /var/lib/openbased
  if id openbased >/dev/null 2>&1; then
    userdel openbased || echo "warning: could not delete the openbased user; is OpenBased still running?" >&2
  fi
  echo "==> Deleted configuration, data and the openbased user (media files were not touched)"
else
  echo "==> Kept /etc/openbased and /var/lib/openbased; run with --purge to delete them"
fi
