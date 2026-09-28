#!/usr/bin/env bash
#
# Reset an Aran user's password when nobody can log in to do it from the admin UI.
#
# WHY THIS DOES NOT TALK TO Aran'S API
# -----------------------------------
# This exists for the case where the administrator is the one locked out. They cannot
# authenticate to Aran, so a route through Aran's API would need an unauthenticated
# administrative endpoint — a backdoor with a justification attached. Instead it talks to
# Keycloak directly using the bootstrap admin credential, which the operator possesses by
# virtue of running the machine. Controlling the host IS the out-of-band proof, which is
# the same reason `grafana-cli admin reset-admin-password`, `gitea admin user
# change-password` and Nextcloud's `occ user:resetpassword` all work this way.
#
# It also means this still works when Aran itself will not start, which is exactly when
# it is needed.
#
# There is no self-service "forgot password" link: that needs an out-of-band channel, and
# without SMTP there isn't one. The realm sets resetPasswordAllowed: false so the login
# page does not offer a link that cannot work.
set -euo pipefail

USERNAME="${1:-}"
REALM="${ARAN_KEYCLOAK_REALM:-aran}"
KC_ADMIN_USER="${KC_ADMIN_USER:-admin}"
KC_ADMIN_PASSWORD="${KC_ADMIN_PASSWORD:-admin}"
KC_SERVICE="${ARAN_KEYCLOAK_SERVICE:-keycloak}"
KCADM="/opt/keycloak/bin/kcadm.sh"

if [[ -z "$USERNAME" ]]; then
    cat >&2 <<'USAGE'
usage: ./aran admin reset-password <username>

Sets a new temporary password for an Aran user. The user must change it at next login.

Environment:
  KC_ADMIN_PASSWORD        Keycloak bootstrap admin password (default: admin)
  ARAN_KEYCLOAK_REALM      realm name (default: aran)
  ARAN_KEYCLOAK_SERVICE    compose service running Keycloak (default: keycloak)
USAGE
    exit 2
fi

if ! docker compose ps --status running --services 2>/dev/null | grep -qx "$KC_SERVICE"; then
    echo "error: the '$KC_SERVICE' service is not running." >&2
    echo "       Start the stack first: ./aran start" >&2
    exit 1
fi

# Unambiguous alphabet — no I/l/1 or O/0. This gets read off a terminal and typed once.
# A bounded read, not `tr < /dev/urandom | head`: under pipefail, head closing the pipe
# kills tr with SIGPIPE and the whole script exits 141 — which is how this script's first
# real run died silently. 512 raw bytes leave ~110 alphabet characters, ample for 20.
PASSWORD="$(head -c 512 /dev/urandom \
    | LC_ALL=C tr -dc 'abcdefghijkmnpqrstuvwxyzACDEFGHJKLMNPQRSTUVWXYZ23456789' \
    | head -c 20)"
[[ ${#PASSWORD} -eq 20 ]] || { echo "error: could not generate a password" >&2; exit 1; }

in_keycloak() {
    docker compose exec -T "$KC_SERVICE" "$@"
}

# A secret never goes in argv: every process's command line is world-readable in
# /proc/<pid>/cmdline, on the host (both `docker compose exec …` processes) and for the
# kcadm JVM inside the container, which the host's `ps` lists too. kcadm reads a secret
# from KC_CLI_PASSWORD instead — the admin password for `config credentials`, the new
# password for `set-password` — and a bare `-e KC_CLI_PASSWORD` makes compose copy the
# value from this process's environment, which only its owner and root can read.
# tests/scripts/test_admin_reset_password.py pins it.
in_keycloak_with_secret() {
    local secret="$1"
    shift
    KC_CLI_PASSWORD="$secret" docker compose exec -T -e KC_CLI_PASSWORD "$KC_SERVICE" "$@"
}

# /auth first: the standalone topology serves Keycloak under KC_HTTP_RELATIVE_PATH=/auth
# (the same-origin proxy needs it); the dev topology serves it at the root. Trying the
# wrong one is a 404, not a lockout, so probing both costs nothing.
kc_login() {
    in_keycloak_with_secret "$KC_ADMIN_PASSWORD" "$KCADM" config credentials \
        --server "$1" \
        --realm master \
        --user "$KC_ADMIN_USER" >/dev/null 2>&1
}
kc_login http://localhost:8080/auth || kc_login http://localhost:8080 \
    || { echo "error: could not sign in to Keycloak as '$KC_ADMIN_USER' (wrong KC_ADMIN_PASSWORD?)" >&2; exit 1; }

USER_ID="$(in_keycloak "$KCADM" get users -r "$REALM" -q "username=$USERNAME" -q exact=true \
    --fields id --format csv --noquotes 2>/dev/null | head -n 1 | tr -d '\r')"

if [[ -z "$USER_ID" ]]; then
    echo "error: no user '$USERNAME' in realm '$REALM'." >&2
    exit 1
fi

in_keycloak_with_secret "$PASSWORD" "$KCADM" set-password -r "$REALM" --userid "$USER_ID" \
    --temporary >/dev/null

cat <<EOF

  Password reset for '$USERNAME'.

    $PASSWORD

  Shown once. It is temporary — the user must choose a new one at next login.

EOF
