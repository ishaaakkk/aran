#!/bin/sh
# =============================================================================
#  Garage one-shot bootstrap
#
#  Idempotent: safe to re-run against a garage-meta volume that survived a previous
#  bring-up, which is the normal case — `make all` / `make aran-lake` re-run this
#  container on every `up`, and shutdown.sh does not remove volumes.
#
#  1. Assigns the single node into a "dev" layout (applying only what is staged)
#  2. Creates the dev access key with known credentials (if missing)
#  3. Creates the "aran-lake" bucket (if missing)
#  4. Grants the access key read+write on the bucket
# =============================================================================

set -e

GARAGE="/garage -c /etc/garage.toml"

echo "[init] waiting for garage to be ready..."
# Bounded, because an unreachable rpc_public_addr is a configuration error, not a
# slow start: an unbounded loop turns it into `docker compose up --wait` hanging
# until its own timeout with no clue as to why.
WAITED=0
until $GARAGE status >/dev/null 2>&1; do
    WAITED=$((WAITED + 1))
    if [ "$WAITED" -ge 60 ]; then
        echo "[init] garage did not become ready in 60s — check rpc_public_addr in /etc/garage.toml" >&2
        exit 1
    fi
    sleep 1
done

NODE_ID=$($GARAGE node id -q | cut -d@ -f1)
echo "[init] node id: $NODE_ID"

# Step 1 — layout
#
# `layout apply` takes the NEW version number and garage refuses anything other than
# current+1 ("Invalid new layout version"). Hardcoding 1 is only correct against a
# virgin garage-meta volume, and this container is re-run against a surviving one
# every time the stack comes back up — scripts/shutdown.sh does `down` without `-v`.
# Garage prints the version it wants at the end of `layout show`, so read it from
# there rather than guessing.
#
# The guard this replaces grepped `layout show` for "Role: storage", a string garage
# v1 does not emit, so the assign+apply path ran unconditionally and `set -e` killed
# the script on the second run — before the key, bucket and permission steps.
$GARAGE layout assign -z dev -c 1G "$NODE_ID" >/dev/null 2>&1 || true
NEXT_VERSION=$($GARAGE layout show 2>&1 \
    | sed -n 's/.*layout apply --version \([0-9][0-9]*\).*/\1/p' | tail -1)
if [ -n "$NEXT_VERSION" ]; then
    echo "[init] applying layout version $NEXT_VERSION..."
    $GARAGE layout apply --version "$NEXT_VERSION"
else
    echo "[init] no staged layout changes; layout already current"
fi

# Step 2 — access key
if ! $GARAGE key info GK000000000000000000000000 >/dev/null 2>&1; then
    echo "[init] creating dev access key..."
    $GARAGE key import \
        --yes \
        GK000000000000000000000000 \
        0000000000000000000000000000000000000000000000000000000000000000
else
    echo "[init] dev access key already exists"
fi

# Step 3 — bucket
if ! $GARAGE bucket info aran-lake >/dev/null 2>&1; then
    echo "[init] creating bucket aran-lake..."
    $GARAGE bucket create aran-lake
else
    echo "[init] bucket aran-lake already exists"
fi

# Step 4 — permissions
echo "[init] granting key read+write on aran-lake bucket..."
$GARAGE bucket allow --read --write --owner aran-lake --key GK000000000000000000000000

echo "[init] done."
