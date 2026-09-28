#!/usr/bin/env bash
#
# Proves the standalone stack actually works, end to end, the way a stranger would use it.
#
# Unlike the version this replaces, it AUTHENTICATES. The standalone profile no longer
# has a no-auth mode, so a smoke test that skipped login would prove nothing about the
# thing a new user does first. It signs in as the seeded owner, mints an API token, and
# drives the rest with that token — which also exercises the programmatic path the SDK
# and JDBC use.
#
# One step looks past the API: whether freyja profiled the upload is visible only in
# aran's column_profile table (no endpoint returns a dataset's profiles), so that step reads
# Postgres through `docker compose exec`, and the script must run on the compose host.
set -euo pipefail

BASE="${ARAN_BASE_URL:-http://localhost:8080}"
KEYCLOAK="${ARAN_KEYCLOAK_BASE:-http://localhost:8080/auth}"
ADMIN_USER="${ARAN_ADMIN_USERNAME:-alice}"
ADMIN_PASSWORD="${ARAN_ADMIN_PASSWORD:-}"
DEMO_CSV="${ARAN_DEMO_CSV:-demo/seminar/acm_papers.csv}"
COMPOSE=(docker compose -f "${ARAN_COMPOSE_FILE:-compose.standalone.yml}")

fail() { echo "FAILED: $*" >&2; exit 1; }
step() { printf '\n▸ %s\n' "$1"; }

step "Waiting for Aran to become ready"
for _ in $(seq 1 90); do
    if curl -sf "$BASE/api/auth/policy" >/dev/null 2>&1; then break; fi
    sleep 2
done
curl -sf "$BASE/api/auth/policy" >/dev/null \
    || fail "Aran did not become ready at $BASE. Try: docker compose -f compose.standalone.yml logs aran-app"

step "Reading the deployment's auth policy"
POLICY="$(curl -sf "$BASE/api/auth/policy")"
echo "  $POLICY"
grep -q 'api-token-bearer' <<<"$POLICY" \
    || fail "this deployment does not accept api-token-bearer, so the SDK path is off"

if [[ -z "$ADMIN_PASSWORD" ]]; then
    cat >&2 <<EOF

  ARAN_ADMIN_PASSWORD is not set.

  The first-run password is generated and printed ONCE to the app's log. Find it with:

      docker compose -f compose.standalone.yml logs aran-app | grep -A3 'first-run administrator'

  then re-run with ARAN_ADMIN_PASSWORD=<that value>.

EOF
    exit 2
fi

step "Signing in as '$ADMIN_USER'"
# Direct grant against Keycloak, for the test only. The browser uses the authorization-code
# flow; scripting that would mean driving a login page, which Playwright already does.
# aran-cli, not aran-web: the SPA client has direct access grants OFF on purpose
# (PKCE posture), and the realm ships a separate public client for scripted logins.
direct_grant() {
    curl -s -X POST \
        "$KEYCLOAK/realms/aran/protocol/openid-connect/token" \
        -d "client_id=aran-cli" \
        -d "grant_type=password" \
        -d "username=$ADMIN_USER" \
        -d "password=$ADMIN_PASSWORD"
}
TOKEN_RESPONSE="$(direct_grant)"

if grep -q 'not fully set up' <<<"$TOKEN_RESPONSE"; then
    # The seeded owner's password is temporary — the browser would force a change at first
    # login (docs-site/docs/administration/standalone.md says so). A script cannot drive that
    # form, so it completes
    # the same change over the Admin API with the aran-backend service account — which is
    # also the live proof that service account can manage users at all (V6's risk item).
    step "First login: completing the forced password change via the Admin API"
    ADMIN_CLIENT_SECRET="${ARAN_KEYCLOAK_ADMIN_CLIENT_SECRET:-dev-backend-secret-change-in-prod}"
    KC_ADMIN_TOKEN="$(curl -sf -X POST \
        "$KEYCLOAK/realms/aran/protocol/openid-connect/token" \
        -d "client_id=aran-backend" -d "client_secret=$ADMIN_CLIENT_SECRET" \
        -d "grant_type=client_credentials" \
        | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')" \
        || fail "the aran-backend service account could not sign in"
    USER_ID="$(curl -sf "$KEYCLOAK/admin/realms/aran/users?username=$ADMIN_USER&exact=true" \
        -H "Authorization: Bearer $KC_ADMIN_TOKEN" \
        | python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["id"])')" \
        || fail "could not resolve $ADMIN_USER via the Admin API"
    PASSWORD_JSON="$(ADMIN_PASSWORD="$ADMIN_PASSWORD" python3 -c \
        'import json,os; print(json.dumps({"type":"password","value":os.environ["ADMIN_PASSWORD"],"temporary":False}))')"
    curl -sf -X PUT "$KEYCLOAK/admin/realms/aran/users/$USER_ID/reset-password" \
        -H "Authorization: Bearer $KC_ADMIN_TOKEN" -H 'Content-Type: application/json' \
        -d "$PASSWORD_JSON" \
        || fail "could not complete the password change"
    TOKEN_RESPONSE="$(direct_grant)"
fi
grep -q 'access_token' <<<"$TOKEN_RESPONSE" || fail "could not sign in as $ADMIN_USER: $TOKEN_RESPONSE"

ACCESS_TOKEN="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])' \
    <<<"$TOKEN_RESPONSE")" || fail "no access_token in Keycloak's response"

step "Minting an API token"
# Unique per run: a re-run must not fail on a duplicate token name.
TOKEN_JSON="$(curl -sf -X POST "$BASE/api/tokens" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H 'Content-Type: application/json' \
    -d "{\"name\":\"standalone-smoke-$(date +%s)\",\"scope\":\"FULL\",\"expiryDays\":1}")" \
    || fail "could not create an API token"

API_TOKEN="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["secret"])' <<<"$TOKEN_JSON")"
[[ -n "$API_TOKEN" ]] || fail "the create-token response carried no secret"
auth=(-H "Authorization: Bearer $API_TOKEN")

step "Confirming the seeded owner is a real member of a real tenant"
# With the OIDC token, not the PAT: administrative routes deliberately refuse API
# tokens (TokenScopePolicy), and the first run of this script proved that guard live.
MEMBERS="$(curl -sf -H "Authorization: Bearer $ACCESS_TOKEN" "$BASE/api/tenant/members")" \
    || fail "could not list tenant members — the bootstrap may not have run"
grep -q '"role":"OWNER"' <<<"$MEMBERS" || fail "no OWNER in the members list: $MEMBERS"

step "Creating a project and a repository"
PROJECT_ID="$(curl -sf "${auth[@]}" -H 'Content-Type: application/json' \
    -X POST "$BASE/api/projects" \
    -d "{\"name\":\"Standalone Smoke $(date +%s)\",\"description\":\"created by standalone-smoke.sh\"}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')" \
    || fail "could not create a project"
REPO_ID="$(curl -sf "${auth[@]}" -H 'Content-Type: application/json' \
    -X POST "$BASE/api/projects/$PROJECT_ID/repositories" \
    -d '{"type":"FILE_SYSTEM","name":"smoke-files","description":"smoke uploads"}' \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')" \
    || fail "could not create a repository"
echo "  project $PROJECT_ID · repository $REPO_ID"

step "Uploading the demo dataset"
[[ -f "$DEMO_CSV" ]] || fail "demo file not found: $DEMO_CSV (run from the repo root)"
# The upload goes to Aran, never to the lake: only Aran talks to the lake.
DATASET_ID="$(curl -sf "${auth[@]}" \
    -F 'dataset={"name":"acm_papers","description":"seminar demo file"};type=application/json' \
    -F "file=@$DEMO_CSV;type=text/csv" \
    "$BASE/api/projects/$PROJECT_ID/repositories/$REPO_ID/datasets" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')" \
    || fail "dataset upload failed"
echo "  dataset $DATASET_ID"

step "Waiting for bootstrap"
STATUS=""
for _ in $(seq 1 60); do
    STATUS="$(curl -sf "${auth[@]}" \
        "$BASE/api/projects/$PROJECT_ID/repositories/$REPO_ID/datasets" \
        | python3 -c "import json,sys; ds=[d for d in json.load(sys.stdin) if d['id']=='$DATASET_ID']; print(ds[0]['bootstrapStatus'] if ds else 'GONE')")"
    [[ "$STATUS" == "READY" ]] && break
    [[ "$STATUS" == "FAILED" ]] && fail "bootstrap FAILED — check aran-app and aran-lake logs"
    sleep 3
done
[[ "$STATUS" == "READY" ]] || fail "bootstrap did not reach READY (last: $STATUS)"
echo "  bootstrap READY — the lake, Garage and Fuseki all took real writes"

step "Waiting for freyja to profile the upload"
# Auto-profiling runs in the same job right after READY, and is best-effort: without freyja the
# job still succeeds and nothing else in this script would notice. A SNAPSHOT profile row proves
# the whole chain — aran-app authenticated to freyja, freyja read the snapshot from the lake as
# its own caller, and the numbers came back and were persisted.
PROFILES=0
for _ in $(seq 1 45); do
    PROFILES="$("${COMPOSE[@]}" exec -T postgres psql -U aran -d aran -tAc \
        "SELECT count(*) FROM column_profile WHERE dataset_id = '$DATASET_ID' AND subject = 'SNAPSHOT'" \
        2>/dev/null | tr -d '[:space:]')" || PROFILES=0
    [[ "$PROFILES" =~ ^[0-9]+$ ]] || PROFILES=0
    (( PROFILES > 0 )) && break
    sleep 2
done
(( PROFILES > 0 )) \
    || fail "no column profile for dataset $DATASET_ID — freyja did not profile it. Try: ${COMPOSE[*]} logs freyja aran-app"
echo "  $PROFILES column profiles persisted"

step "Querying"
# One uploaded source is previewable but not yet integrated, so the mediated query
# path legitimately has no active schema — the error must SAY that, as a client error, not 500.
EXPORT="$(curl -s -w '\n%{http_code}' "${auth[@]}" -H 'Content-Type: application/json' \
    -X POST "$BASE/api/query/export" \
    -d '{"oql":"SELECT Paper.title FROM Paper","format":"CSV"}')" \
    || fail "the export request did not complete"
EXPORT_STATUS="${EXPORT##*$'\n'}"
EXPORT="${EXPORT%$'\n'*}"
[[ "$EXPORT_STATUS" =~ ^4[0-9][0-9]$ ]] \
    || fail "export with no active schema answered HTTP $EXPORT_STATUS, expected a 4xx: $(head -c 200 <<<"$EXPORT")"
grep -qiE "no active|promote" <<<"$EXPORT" \
    || fail "export with no active schema did not say so (HTTP $EXPORT_STATUS): $(head -c 200 <<<"$EXPORT")"
echo "  HTTP $EXPORT_STATUS — no active schema yet, said plainly"

echo
echo "STANDALONE SMOKE: OK"
