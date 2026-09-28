#!/usr/bin/env bash
#
# The gate every Phase 0 milestone must pass before it is committed.
#
# Usage:
#   ./scripts/validate.sh [--offline] [--java] [--web] [--python]
#
#   (no flag)     the full gate, every part — requires Docker
#   --offline     the Docker-free subset of whichever parts are selected
#   --java        only the Gradle build and the Java suites (+ RUN_PERF)
#   --web         only aran-web: build, lint, Vitest
#   --python      only ruff and the Python suites (lake, freyja, SDK, MCP, sidecar contract)
#
# Selectors combine (`--java --web`), and naming none means all three, so the bare command is
# the gate it has always been. They exist so CI can run the parts as parallel jobs by calling
# THIS script, rather than keeping a second copy of its command list that drifts (it did, twice).
# A selected part is never silently skipped: its prerequisites are checked up front, and every
# missing one is named at once with the make target that creates it.
#
# Docker-gated Java tests carry @Tag("docker") — the aran-application integration tests (by
# inheritance from BaseIntegrationTest) plus a few Garage/Postgres-backed operator tests —
# and `--offline` excludes that tag. The full gate additionally needs Docker for:
#   * those tagged tests      (Testcontainers)
#   * aran-lake's pytest suite (Testcontainers Postgres)
set -euo pipefail

cd "$(dirname "$0")/.."

usage() {
  echo "usage: $0 [--offline] [--java] [--web] [--python]" >&2
  exit 2
}

OFFLINE=0
JAVA=0
WEB=0
PYTHON=0
for arg in "$@"; do
  case "$arg" in
    --offline) OFFLINE=1 ;;
    --java) JAVA=1 ;;
    --web) WEB=1 ;;
    --python) PYTHON=1 ;;
    *) usage ;;
  esac
done
if (( JAVA + WEB + PYTHON == 0 )); then
  JAVA=1
  WEB=1
  PYTHON=1
fi
ALL_PARTS=$(( JAVA && WEB && PYTHON ))

LAKE_PY="aran-lake/.venv/bin/python"
CLIENT_PY="aran-client/.venv/bin/python"
MCP_PY="aran-mcp/.venv/bin/python"

# Check every venv up front, so a fresh clone reports all the missing ones at once instead of one
# per run. Each probe below imports what the suite imports, so a venv
# whose install failed half-way is caught here rather than as a collection error later.
preflight() {
  local missing=()
  local freyja_error=""
  if (( WEB )) && [[ ! -d aran-web/node_modules ]]; then
    missing+=("aran-web/node_modules                    -> make web-deps")
  fi
  if (( PYTHON )); then
    if [[ ! -x aran-lake/.venv/bin/alembic ]]; then
      missing+=("aran-lake/.venv                          -> make aran-lake-venv")
      missing+=("freyja's dependencies in aran-lake/.venv -> make freyja-deps")
    elif ! freyja_error=$("$LAKE_PY" -c "import freyja.api.profile_api" 2>&1 >/dev/null); then
      # The venv exists, so this is not a fresh clone: the import failed on a dependency, or on
      # freyja itself. Only the traceback says which — and `make freyja-deps` probes its own list
      # of packages, so for the second kind it reports nothing to do and the loop never ends.
      missing+=("freyja does not import from aran-lake/.venv (error below) -> make freyja-deps if it names a missing package")
      [[ -n "$freyja_error" ]] || freyja_error="(the import exited non-zero and printed nothing)"
    fi
    if ! { [[ -x "$CLIENT_PY" ]] && "$CLIENT_PY" -c "import aran_client, pytest" >/dev/null 2>&1; }; then
      missing+=("aran-client/.venv                        -> make aran-client")
    fi
    if ! { [[ -x "$MCP_PY" ]] && "$MCP_PY" -c "import aran_mcp, pytest" >/dev/null 2>&1; }; then
      missing+=("aran-mcp/.venv                           -> make aran-mcp")
    fi
  fi
  if (( ${#missing[@]} )); then
    {
      echo "validate.sh: the selected parts need prerequisites that are not in place:"
      printf '  * %s\n' "${missing[@]}"
      if [[ -n "$freyja_error" ]]; then
        echo
        echo "  'import freyja.api.profile_api' failed; the last lines of its output:"
        tail -n 8 <<<"$freyja_error" | sed 's/^/    /'
      fi
      echo
      echo "  'make bootstrap' creates all of them (no Docker needed); or run the targets above."
      echo "  To validate only what is already set up, narrow the run with --java / --web / --python."
    } >&2
    exit 1
  fi
}

# Lint every Python tree against the repo-root ruff.toml. Prefers the lake venv's ruff so
# the version matches the pinned dev dependency; falls back to one on PATH. A missing ruff
# is reported rather than silently skipped — a lint step that vanishes when a tool is absent
# is how aran-lake's own config went unenforced outside CI for months.
run_ruff() {
  local ruff=""
  if [[ -x aran-lake/.venv/bin/ruff ]]; then
    ruff="aran-lake/.venv/bin/ruff"
  elif command -v ruff >/dev/null 2>&1; then
    ruff="ruff"
  else
    echo "ruff not found — run 'make aran-lake-venv' (installs the pinned dev extras)." >&2
    return 1
  fi
  "$ruff" check .
}

# The query-engine performance benchmark. It needs no Docker — it drives the engine in-process
# over synthetic Parquet it generates itself — but it takes a few minutes, so it is opt-in:
#
#   RUN_PERF=1 ./scripts/validate.sh [--offline] [--java]
#
# Offered in BOTH modes on purpose. RUN_E2E lives only in the full gate because the browser
# suite genuinely needs Docker; this one does not, so gating it behind the Docker path would
# withhold it from exactly the machines that can still run it. The gate-tier assertions
# (PerfGateTest, PerfGoldenTest) are not opt-in and run in the module suite either way.
run_perf_if_requested() {
  if [[ "${RUN_PERF:-0}" == "1" ]]; then
    echo "── query-engine performance benchmark (RUN_PERF=1) ──"
    ./gradlew :aran-operators:query-engine:test --tests '*PerfBenchmarkTest' -Daran.perf=true
    echo "Perf report: aran-operators/query-engine/build/perf/report.md"
  else
    echo "SKIPPED: query-engine perf benchmark (set RUN_PERF=1 to include it)"
  fi
}

# Modules whose tests need neither Docker nor a live database.
OFFLINE_MODULES=(
  :aran-core:test
  :aran-adapters:aran-adapter-persistence:test
  :aran-adapters:aran-adapter-license-token:test
  :aran-adapters:aran-adapter-iceberg:test
  :aran-operators:bootstrapping:test
  :aran-operators:profiling:test
  :aran-operators:join-discovery:test
  :aran-operators:schema-integration:test
  :aran-operators:constraint-discovery:test
  :aran-operators:query-engine:test
)

run_java() {
  echo "── java ──"
  make build
  if (( OFFLINE )); then
    # -PexcludeTags=docker drops the Garage/Postgres-backed tests in :bootstrapping and
    # :query-engine; for the modules that declare no docker tag the flag is a harmless no-op.
    ./gradlew "${OFFLINE_MODULES[@]}" -PexcludeTags=docker
    # aran-application's suite is mostly Testcontainers-backed, but not all of it: the security
    # and service unit tests there need no daemon. They are tagged rather than listed, so a new
    # Docker-free test in that module is covered here the day it is written.
    ./gradlew :aran-application:test -PexcludeTags=docker
  else
    make test
  fi
  run_perf_if_requested
}

run_web() {
  echo "── web ──"
  (cd aran-web && npm run build && npm run lint && npm test)
}

run_python() {
  echo "── python ──"
  # This gate ran NO Python linter until 2026-08-16 — it was weaker than CI, which at least ran
  # ruff over aran-lake. One repo-root ruff.toml covers aran-lake, freyja and aran-client, so
  # `ruff check .` from here is the whole Python surface.
  run_ruff

  # The lake's dependencies live in its own venv (see aran-lake/README.md); there is no
  # project-wide `python` with pytest on it. Its suite needs Testcontainers.
  if (( ! OFFLINE )); then
    (cd aran-lake && .venv/bin/python -m pytest -q)
  fi

  # freyja shares the lake's venv on purpose — `make freyja-deps` installs
  # freyja/requirements.txt into it rather than creating a second one. Offline drops the
  # `docker`-marked live-scan tests — the same exclusion -PexcludeTags=docker makes on the Java side.
  if (( OFFLINE )); then
    "$LAKE_PY" -m pytest freyja/tests -q -m "not docker"
  else
    "$LAKE_PY" -m pytest freyja/tests -q
  fi

  # aran-client has its own venv, deliberately: it is a package users pip install into their
  # own notebook environment, so it must not inherit the lake's pins. Its tests run against a
  # loopback stub — no Docker, no running Aran.
  (cd aran-client && .venv/bin/python -m pytest -q)

  # Same shape for the MCP sidecar: the MCP protocol runs in memory and Aran is a loopback stub.
  (cd aran-mcp && .venv/bin/python -m pytest -q)

  # The sidecar security contract — one suite run against every Python service Aran calls over
  # HTTP. It imports each service's config and bearer check directly, so it needs no Docker and
  # no database. A new sidecar is added to tests/sidecar_contract/registry.py and is held to the
  # same rules from then on.
  PYTHONPATH=".:aran-lake/src" "$LAKE_PY" -m pytest tests/sidecar_contract -q

  # The dev stack's stop path (scripts/_lib.sh), run for real with docker stubbed and scratch
  # ports: it must stop what startup.sh launched and leave an e2e run beside it alone.
  "$LAKE_PY" -m pytest tests/dev_scripts -q
  # The shell scripts that handle secrets. `docker` is replaced on PATH by a recorder, so no
  # daemon is needed and what is pinned is exactly what reaches a process's argv.
  "$LAKE_PY" -m pytest tests/scripts -q
}

selected_parts() {
  local parts=()
  (( JAVA )) && parts+=(java)
  (( WEB )) && parts+=(web)
  (( PYTHON )) && parts+=(python)
  local IFS=,
  echo "${parts[*]}"
}

preflight
(( JAVA )) && run_java
(( WEB )) && run_web
(( PYTHON )) && run_python

if (( OFFLINE )); then
  echo
  if (( ALL_PARTS )); then
    echo "VALIDATION (offline subset): ALL GREEN"
  else
    echo "VALIDATION (offline subset; parts: $(selected_parts)): GREEN — unselected parts were NOT run"
  fi
  echo "NOT covered — re-run without --offline once Docker is available:"
  (( JAVA )) && echo "  * the @Tag(\"docker\") Java tests (aran-application ITs, Garage/Postgres operator tests)"
  (( PYTHON )) && echo "  * aran-lake pytest suite   (Testcontainers)"
  (( PYTHON )) && echo "  * freyja docker-marked tests (live postgres_scan, Testcontainers)"
  echo "  * aran-web/e2e Playwright  (npx playwright test)"
  exit 0
fi

# The browser end-to-end suite owns its own stack and takes ~10 minutes, so it is opt-in rather
# than part of every milestone gate. It drives the backend, the lake, freyja and aran-mcp, so it
# runs only when every part was selected:
#   RUN_E2E=1 ./scripts/validate.sh
if [[ "${RUN_E2E:-0}" == "1" ]] && (( ALL_PARTS )); then
  make e2e
elif [[ "${RUN_E2E:-0}" == "1" ]]; then
  echo "SKIPPED: browser e2e suite — RUN_E2E needs every part selected (drop the selectors)"
else
  echo "SKIPPED: browser e2e suite (set RUN_E2E=1 to include it)"
fi

if (( ALL_PARTS )); then
  echo "VALIDATION: ALL GREEN"
else
  echo "VALIDATION (parts: $(selected_parts)): GREEN — unselected parts were NOT run"
fi
