#!/usr/bin/env bash
#
# One-shot installer for SportPredict on a clean Ubuntu 22.04 / 24.04 server.
# Run as root (or with sudo) from the repository root:
#
#   sudo bash deploy/install-ubuntu.sh
#
# Idempotent: re-running it rebuilds and restarts the service without touching data.

set -euo pipefail

APP_USER=sportpredict
APP_DIR=/opt/sportpredict
ENV_DIR=/etc/sportpredict
DB_NAME=sportpredict
DB_USER=sportpredict
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ $EUID -ne 0 ]]; then
    echo "run this as root: sudo bash deploy/install-ubuntu.sh" >&2
    exit 1
fi

echo "==> installing packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq \
    openjdk-21-jdk-headless maven postgresql postgresql-contrib nginx ca-certificates curl

echo "==> creating service user"
id -u "$APP_USER" >/dev/null 2>&1 || useradd --system --create-home --shell /usr/sbin/nologin "$APP_USER"
mkdir -p "$APP_DIR" "$ENV_DIR" "$APP_DIR/logs"

echo "==> configuring PostgreSQL"
DB_PASSWORD_FILE="$ENV_DIR/db-password"
if [[ ! -f "$DB_PASSWORD_FILE" ]]; then
    head -c 24 /dev/urandom | base64 | tr -d '/+=' > "$DB_PASSWORD_FILE"
    chmod 600 "$DB_PASSWORD_FILE"
fi
DB_PASSWORD="$(cat "$DB_PASSWORD_FILE")"

sudo -u postgres psql -tAc "select 1 from pg_roles where rolname='$DB_USER'" | grep -q 1 \
    || sudo -u postgres psql -c "create role $DB_USER login password '$DB_PASSWORD';"
sudo -u postgres psql -c "alter role $DB_USER password '$DB_PASSWORD';"
sudo -u postgres psql -tAc "select 1 from pg_database where datname='$DB_NAME'" | grep -q 1 \
    || sudo -u postgres createdb -O "$DB_USER" "$DB_NAME"

echo "==> building the application"
cd "$REPO_DIR"
mvn -q -DskipTests package

echo "==> installing headless Chromium for the Winner scraper"
# Playwright ships its own browser build; --with-deps pulls the shared libraries it needs.
mvn -q exec:java -e \
    -Dexec.mainClass=com.microsoft.playwright.CLI \
    -Dexec.classpathScope=compile \
    -Dexec.args="install --with-deps chromium" || \
    echo "!! Chromium install failed - set PLAYWRIGHT_ENABLED=false and use manual paste"

# Browsers land in root's cache during install; move them somewhere the service can read.
PLAYWRIGHT_CACHE=/opt/sportpredict/playwright
mkdir -p "$PLAYWRIGHT_CACHE"
if [[ -d /root/.cache/ms-playwright ]]; then
    cp -rn /root/.cache/ms-playwright/. "$PLAYWRIGHT_CACHE"/ || true
fi

echo "==> installing files"
install -m 640 -o "$APP_USER" -g "$APP_USER" target/sportpredict.jar "$APP_DIR/sportpredict.jar"
chown -R "$APP_USER:$APP_USER" "$APP_DIR"

if [[ ! -f "$ENV_DIR/sportpredict.env" ]]; then
    ADMIN_TOKEN="$(head -c 24 /dev/urandom | base64 | tr -d '/+=')"
    cat > "$ENV_DIR/sportpredict.env" <<EOF
# --- SportPredict runtime configuration ---
DB_URL=jdbc:postgresql://localhost:5432/$DB_NAME
DB_USER=$DB_USER
DB_PASSWORD=$DB_PASSWORD

# Get keys at https://api-sports.io/ and https://allsportsapi.com/
APISPORTS_KEY=
ALLSPORTS_KEY=

# Free api-sports tier is 10 req/min and 100 req/day. Raise after upgrading.
APISPORTS_RPM=10
APISPORTS_DAILY=100
ALLSPORTS_RPM=30
ALLSPORTS_DAILY=1000

ADMIN_TOKEN=$ADMIN_TOKEN
PLAYWRIGHT_ENABLED=true
PLAYWRIGHT_BROWSERS_PATH=$PLAYWRIGHT_CACHE
PORT=8090
EOF
    chmod 640 "$ENV_DIR/sportpredict.env"
    chown root:"$APP_USER" "$ENV_DIR/sportpredict.env"
    echo "    !! API keys are empty - edit $ENV_DIR/sportpredict.env, then: systemctl restart sportpredict"
    echo "    admin token: $ADMIN_TOKEN"
fi

echo "==> installing systemd unit"
install -m 644 deploy/sportpredict.service /etc/systemd/system/sportpredict.service
systemctl daemon-reload
systemctl enable sportpredict
systemctl restart sportpredict

echo "==> configuring nginx"
install -m 644 deploy/nginx-sportpredict.conf /etc/nginx/sites-available/sportpredict
ln -sf /etc/nginx/sites-available/sportpredict /etc/nginx/sites-enabled/sportpredict
rm -f /etc/nginx/sites-enabled/default
nginx -t
systemctl reload nginx

echo
echo "done. status:"
systemctl --no-pager --lines=5 status sportpredict || true
echo
echo "UI:   http://<server-ip>/"
echo "logs: journalctl -u sportpredict -f"
