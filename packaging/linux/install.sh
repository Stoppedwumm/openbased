#!/usr/bin/env bash
# Installs or upgrades OpenBased as a systemd service.
#
#   sudo packaging/linux/install.sh [--url http://bigbox:8080] [path/to/openbased.jar]
#
# --url is the address you open OpenBased at in the browser; sign-in only works there.
# It defaults to http://<hostname>:8080 on the first install, and is left unchanged on
# upgrades unless given.
#
# Re-running the script upgrades the JAR and restarts the service. Configuration in
# /etc/openbased and data in /var/lib/openbased are never overwritten.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

INSTALL_DIR=/opt/openbased
CONFIG_DIR=/etc/openbased
DATA_DIR=/var/lib/openbased
UNIT_FILE=/etc/systemd/system/openbased.service
SERVICE_USER=openbased

die() { echo "error: $*" >&2; exit 1; }
info() { echo "==> $*"; }

[[ $EUID -eq 0 ]] || die "run as root, e.g. sudo $0"
command -v systemctl >/dev/null || die "systemd is required"

URL=""
JAR=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --url) URL="${2:-}"; shift 2 ;;
    --url=*) URL="${1#--url=}"; shift ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    *) JAR="$1"; shift ;;
  esac
done
if [[ -n "$URL" ]]; then
  URL="${URL%/}"
  [[ "$URL" =~ ^https?://[^/]+$ ]] || die "--url must look like http://host:port (no path), got: $URL"
fi
if [[ -z "$JAR" ]]; then
  JAR="$(ls "$REPO_DIR"/target/openbased-*.jar 2>/dev/null | grep -v -- '-plain\.jar$' | head -n1 || true)"
  [[ -n "$JAR" ]] || die "no JAR found; build it first with 'mvn package' or pass its path"
fi
[[ -f "$JAR" ]] || die "JAR not found: $JAR"

if ! command -v java >/dev/null; then
  die "Java 17 or newer is required (e.g. 'apt install openjdk-17-jre-headless' or 'dnf install java-17-openjdk-headless')"
fi
JAVA_MAJOR="$(java -version 2>&1 | awk -F'"' '/version/ {split($2, v, "."); print v[1]; exit}')"
[[ "${JAVA_MAJOR:-0}" -ge 17 ]] || die "Java 17 or newer is required, found ${JAVA_MAJOR:-unknown}"

if ! command -v ffmpeg >/dev/null || ! command -v ffprobe >/dev/null; then
  echo "warning: ffmpeg/ffprobe not found. Media will play only when the browser supports the file as-is." >&2
fi

if ! id "$SERVICE_USER" >/dev/null 2>&1; then
  info "Creating system user $SERVICE_USER"
  useradd --system --home-dir "$DATA_DIR" --no-create-home --shell /usr/sbin/nologin "$SERVICE_USER"
fi

info "Installing $JAR to $INSTALL_DIR"
install -d -m 0755 "$INSTALL_DIR"
install -m 0644 "$JAR" "$INSTALL_DIR/openbased.jar.new"
mv -f "$INSTALL_DIR/openbased.jar.new" "$INSTALL_DIR/openbased.jar"

install -d -m 0750 -o root -g "$SERVICE_USER" "$CONFIG_DIR"
if [[ ! -e "$CONFIG_DIR/application.yml" ]]; then
  info "Creating $CONFIG_DIR/application.yml"
  install -m 0640 -o root -g "$SERVICE_USER" "$SCRIPT_DIR/application.yml" "$CONFIG_DIR/application.yml"
  URL="${URL:-http://$(hostname):8080}"
fi
if [[ -n "$URL" ]]; then
  info "Setting the server address to $URL"
  sed -i -E "s|^(  issuer:).*|\1 $URL|" "$CONFIG_DIR/application.yml"
fi
if [[ ! -e "$CONFIG_DIR/openbased.env" ]]; then
  info "Creating $CONFIG_DIR/openbased.env"
  install -m 0640 -o root -g "$SERVICE_USER" "$SCRIPT_DIR/openbased.env" "$CONFIG_DIR/openbased.env"
fi

install -d -m 0750 -o "$SERVICE_USER" -g "$SERVICE_USER" "$DATA_DIR"

info "Installing systemd unit"
install -m 0644 "$SCRIPT_DIR/openbased.service" "$UNIT_FILE"
systemctl daemon-reload

if systemctl is-active --quiet openbased; then
  info "Restarting openbased"
  systemctl restart openbased
else
  info "Enabling and starting openbased"
  systemctl enable --now openbased
fi

ISSUER="$(awk '/^  issuer:/ {print $2; exit}' "$CONFIG_DIR/application.yml")"
cat <<MSG

OpenBased is installed and running.

  Web UI:   $ISSUER/
            (sign-in only works at this address; change it with --url or the
            "issuer" line in $CONFIG_DIR/application.yml)
  Config:   $CONFIG_DIR/application.yml and $CONFIG_DIR/openbased.env
  Data:     $DATA_DIR
  Logs:     journalctl -u openbased -f
  Control:  systemctl {status|restart|stop} openbased

On the first start an "admin" account is created. Unless you set
OPENBASED_ADMIN_PASSWORD, its password is in the log:
  journalctl -u openbased | grep 'generated password'

The service runs as the "$SERVICE_USER" user, which needs read access to your media
folders (rwX instead of rX if you upload into them), e.g.:
  sudo setfacl -R -m u:$SERVICE_USER:rX /path/to/media      # existing files
  sudo setfacl -R -d -m u:$SERVICE_USER:rX /path/to/media   # files added later
If the folder is inside a home directory, also let it pass through that directory:
  sudo setfacl -m u:$SERVICE_USER:x /home/<you>
Check with:  sudo -u $SERVICE_USER ls /path/to/media
MSG
