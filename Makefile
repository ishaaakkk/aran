# ===========================================================================
#  Aran — Makefile
#  Shorthand for common development operations.
#  Prerequisites: Docker, JDK 25, Node.js 20+, make.
# ===========================================================================

# Every target here is a command, not a file. `db`, `health` and `observability`
# were missing until 2026-08-27 (audit CLIENTS-4) — a stray file of that name in
# the repo root would have silently made them no-ops.
.PHONY: help all stop dev bootstrap web-deps aran-lake aran-lake-venv freyja-sidecar freyja-deps aran-client aran-mcp mcp-demo build test e2e perf perf-federated docker docker-push db observability health clean clean-all seed frontend lint

# Dev-only credentials for the local lake stack — one definition, used by every
# aran-lake invocation below. Override via the environment in non-dev setups.
define ARAN_LAKE_DEV_ENV
ARAN_LAKE_DATABASE_URL="postgresql+asyncpg://aran_lake:aran_lake_dev@localhost:5433/aran_lake" \
ARAN_LAKE_S3_ENDPOINT_URL="http://localhost:3900" \
ARAN_LAKE_S3_REGION="garage" \
ARAN_LAKE_S3_BUCKET="aran-lake" \
ARAN_LAKE_S3_ACCESS_KEY="GK000000000000000000000000" \
ARAN_LAKE_S3_SECRET_KEY="0000000000000000000000000000000000000000000000000000000000000000" \
ARAN_LAKE_SHARED_SECRET="dev-shared-secret-change-in-prod" \
ARAN_LAKE_PROFILE="dev" \
ARAN_LAKE_LOG_LEVEL="INFO"
endef

# Default target: show help
help:
	@echo ""
	@echo "  Aran — available targets"
	@echo ""
	@echo "  make all          Start the entire stack (infra, aran-lake, freyja, frontend, backend) in one go"
	@echo "  make stop         Stop all background services (Uvicorn, Vite) and Docker containers"
	@echo "  make dev          Start Postgres + Keycloak, run Spring Boot (dev profile)"
	@echo "  make bootstrap    Create every environment validate.sh needs (Python venvs, node_modules)"
	@echo "  make aran-lake    Start data lake dependencies (Docker) and local FastAPI app (Uvicorn)"
	@echo "  make aran-client  Bootstrap the Python SDK venv"
	@echo "  make aran-mcp     Bootstrap the MCP sidecar venv (serves data products to an agent)"
	@echo "  make frontend     Start the Vite dev server (http://localhost:5173)"
	@echo "  make build        Build the backend (skip tests)"
	@echo "  make test         Run all backend tests (starts Testcontainers Postgres)"
	@echo "  make e2e          Run the browser end-to-end suite (isolated stack, ~10 min)"
	@echo "  make perf         Run the query-engine performance benchmark (no Docker, few min)"
	@echo "  make perf-federated  Snapshot vs live postgres_scan benchmark (Docker; λ calibration)"
	@echo "  make lint         Type-check the frontend TypeScript"
	@echo "  make docker       Build the Docker image (tag: aran:dev)"
	@echo "  make docker-push  Push the Docker image to the registry"
	@echo "  make seed         (Deprecated) seeding is now fully automated on startup"
	@echo "  make clean        Stop Docker, kill background servers, and remove build outputs"
	@echo "  make clean-all    Same as clean but also removes the Python venv (~640 MB)"
	@echo ""

# --------------------------------------------------------------------------
#  Development
# --------------------------------------------------------------------------

## Start the entire ecosystem (infra, aran-lake FastAPI, freyja, frontend, and backend)
all:
	@./scripts/startup.sh

## Stop all running servers (uvicorn, vite, backend) and docker compose services
stop:
	@./scripts/shutdown.sh

## Start infrastructure + Spring Boot with the dev profile
dev:
	docker compose up -d
	@if [ -d aran-lake/.venv ]; then \
	  echo "Starting FREYJA uvicorn sidecar service in the background (logging to freyja-sidecar.log)..."; \
	  $(MAKE) freyja-sidecar > freyja-sidecar.log 2>&1 & \
	else \
	  echo "WARNING: aran-lake/.venv not found. FREYJA sidecar was not started. Please run 'make aran-lake' first to bootstrap the environment."; \
	fi
	SPRING_PROFILES_ACTIVE=dev ./gradlew :aran-application:bootRun

## Create every environment scripts/validate.sh needs, so a fresh clone runs the gate first time
# instead of failing on one missing venv at a time. No Docker, no servers. Playwright
# browsers are left to `make e2e`, which installs them itself.
bootstrap: aran-lake-venv freyja-deps aran-client aran-mcp web-deps

## Install aran-web's dependencies from the lockfile if they are missing
# Only when absent, like `frontend` and `e2e` below: `npm ci` deletes an existing node_modules
# first, which would throw away a working install on every bootstrap.
web-deps:
	cd aran-web && { [ -d node_modules ] || npm ci; }

## Create aran-lake's venv if it is missing — no containers, no server, no migrations.
# CI and the Playwright suite need the venv on its own: the e2e globalSetup runs its
# own compose stack on offset ports and starts uvicorn itself, so invoking `aran-lake`
# there would both fight over ports and block forever on the dev server. Kept as a
# separate target rather than copied into a workflow so there is one definition.
aran-lake-venv:
	cd aran-lake && { [ -f .venv/bin/alembic ] || { \
	  rm -rf .venv && \
	  echo "Virtual environment not found or incomplete! Bootstrapping..." && \
	  if command -v python3.12 >/dev/null 2>&1; then \
	    PY_EXE="python3.12"; \
	  else \
	    PY_EXE="python3"; \
	  fi; \
	  { command -v uv >/dev/null 2>&1 && uv venv .venv --python $$PY_EXE && uv pip install --python .venv/bin/python -e ".[dev]"; } || \
	  { $$PY_EXE -m venv .venv && .venv/bin/pip install -e ".[dev]"; }; \
	}; }

## Start data lake dependencies (Docker) and the FastAPI app locally (Uvicorn)
aran-lake: aran-lake-venv
	docker compose -f aran-lake/docker-compose.yml up -d garage garage-init postgres
	docker compose -f aran-lake/docker-compose.yml stop lake
	cd aran-lake && $(ARAN_LAKE_DEV_ENV) .venv/bin/alembic upgrade head
	cd aran-lake && $(ARAN_LAKE_DEV_ENV) exec .venv/bin/uvicorn aran_lake.main:app --reload --port 8085

## Install FREYJA's dependencies into the lake venv (shared on purpose) without starting it
# The e2e globalSetup launches freyja itself from aran-lake/.venv, so CI needs the
# dependencies present without the sidecar running.
freyja-deps: aran-lake-venv
	@aran-lake/.venv/bin/python -c "import jellyfish, numpy, pandas, sklearn, joblib, Levenshtein" >/dev/null 2>&1 || { \
	  echo "Installing FREYJA dependencies from freyja/requirements.txt..."; \
	  if command -v uv >/dev/null 2>&1; then \
	    uv pip install --python aran-lake/.venv/bin/python -r freyja/requirements.txt; \
	  else \
	    aran-lake/.venv/bin/python -m pip install -r freyja/requirements.txt; \
	  fi; \
	}
## Start the FREYJA uvicorn sidecar service locally (port 8086)
# FREYJA_SHARED_SECRET must match aran.freyja.secret in application.yml, or Aran's
# calls get a 401. Keep this note here as a make comment: a `@#` line placed between
# the backslash-continued assignments below reaches the shell as a command word, and
# the apostrophe in "Aran's" then unterminates the quoting.
freyja-sidecar: freyja-deps
	ARAN_LAKE_URL="http://localhost:8085" \
	ARAN_LAKE_FREYJA_SECRET="dev-shared-secret-change-in-prod" \
	FREYJA_SHARED_SECRET="$${ARAN_FREYJA_SECRET:-dev-freyja-secret-change-in-prod}" \
	  exec aran-lake/.venv/bin/uvicorn freyja.api.profile_api:app --reload --port 8086

# The Python client (Track C). Its own venv, not the lake's: it is a package users
# `pip install` into whatever environment their notebook runs in, so it must not
# inherit the lake's pins to prove it stands alone.
aran-client:
	@[ -x aran-client/.venv/bin/python ] || { \
	  echo "Bootstrapping aran-client venv..."; \
	  python3 -m venv aran-client/.venv; \
	}
	aran-client/.venv/bin/python -m pip install -q --upgrade pip
	aran-client/.venv/bin/python -m pip install -q -e "aran-client[dev]"

# The MCP sidecar. Its own venv again, and for a sharper reason than aran-client's:
# a user installs this one next to their MCP client, not next to Aran, so it must
# resolve from nothing but its own declared dependencies. It installs aran-client
# from the working tree (-e) so a change there is exercised here immediately.
aran-mcp:
	@[ -x aran-mcp/.venv/bin/python ] || { \
	  echo "Bootstrapping aran-mcp venv..."; \
	  python3 -m venv aran-mcp/.venv; \
	}
	aran-mcp/.venv/bin/python -m pip install -q --upgrade pip
	aran-mcp/.venv/bin/python -m pip install -q -e ./aran-client -e "./aran-mcp[dev]"

# A narrated, scripted walk-through of the real MCP protocol against a real Aran —
# for anyone who wants to watch aran-mcp work without installing an agent client
# first. See demo/seminar/MCP_DEMO.md. Needs ARAN_URL and ARAN_TOKEN in the environment.
mcp-demo:
	@[ -n "$$ARAN_URL" ] && [ -n "$$ARAN_TOKEN" ] || { \
	  echo "Set ARAN_URL and ARAN_TOKEN first (see aran-mcp/README.md 'Get a token')."; \
	  exit 1; \
	}
	aran-mcp/.venv/bin/python aran-mcp/examples/demo.py



## Start the Vite dev server only (assumes infra + backend already running)
frontend:
	cd aran-web && { [ -d node_modules ] || npm install; } && npm run dev

# --------------------------------------------------------------------------
#  Build + test
# --------------------------------------------------------------------------

## Build the backend jar (skips tests)
build:
	./gradlew :aran-application:bootJar --no-daemon

## Run all backend tests (Testcontainers starts a Postgres automatically)
test:
	./gradlew test --no-daemon

## Run the Playwright browser end-to-end suite (owns its own isolated stack)
# Depends on aran-mcp: p1-mcp-live-client.spec.ts execs aran-mcp/.venv/bin/python
# directly (execFileSync, no existence check) to drive a real MCP client subprocess.
# Without this prerequisite, a fresh clone fails that one spec with ENOENT instead of
# a legible bootstrap error — the same failure class the aran-lake/freyja-sidecar
# prerequisite pattern already exists to avoid.
e2e: aran-mcp
	cd aran-web && { [ -d node_modules ] || npm install; } && npx playwright install --with-deps chromium && npm run test:e2e

## Lint + type-check the frontend (same checks CI and validate.sh run)
perf:
	@echo "==> Query-engine performance benchmark (in-process, no Docker)"
	@echo "    Takes a few minutes. Add -Daran.perf.scale=large for the top settings."
	./gradlew :aran-operators:query-engine:test --tests '*PerfBenchmarkTest' \
	  -Daran.perf=true --no-daemon
	@echo ""
	@echo "==> Report: aran-operators/query-engine/build/perf/report.md"

perf-federated:
	@echo "==> Federated execution benchmark (needs Docker + DuckDB extension download)"
	@echo "    Snapshot vs live postgres_scan over the same rows — the λ-calibration instrument."
	./gradlew :aran-operators:query-engine:test --tests '*PerfFederatedBenchmarkTest' \
	  -Daran.perf=true --no-daemon --rerun
	@echo ""
	@echo "==> Report: aran-operators/query-engine/build/perf/federated-report.md"

lint:
	cd aran-web && npx tsc -b && npm run lint

# --------------------------------------------------------------------------
#  Docker
# --------------------------------------------------------------------------
DOCKER_IMAGE ?= aran:dev

## Build the multi-stage Docker image
docker:
	docker build -t $(DOCKER_IMAGE) .

## Push the image (set DOCKER_IMAGE=your-registry/aran:tag)
docker-push:
	docker push $(DOCKER_IMAGE)

# --------------------------------------------------------------------------
#  Database helpers
# --------------------------------------------------------------------------

## Seeding helper (deprecated: seeding is now fully automated on startup)
seed:
	@echo "Local setup is now fully automated via dev migrations! No manual seeding is necessary."

## Open the Postgres CLI directly
db:
	docker exec -it aran-postgres psql -U aran -d aran

## Start the observability stack (OTel collector + Prometheus)
observability:
	docker compose --profile observability up -d
	@echo "Prometheus: http://localhost:9090"

## Check the application health endpoint (actuator lives on the management port, not 8081)
health:
	@curl -sf http://127.0.0.1:8082/actuator/health | python3 -m json.tool \
		|| echo "App not running or not healthy."

# --------------------------------------------------------------------------
#  Cleanup
# --------------------------------------------------------------------------

## Stop all Docker services and clean build outputs (keeps Python venv for fast restart)
clean: stop
	./gradlew clean
	rm -rf aran-web/dist aran-web/node_modules
	rm -rf aran-lake/.pytest_cache aran-lake/.ruff_cache aran-lake/__pycache__
	rm -f aran-lake.log freyja-sidecar.log frontend.log

## Full cleanup including the Python venv (~640 MB). Requires `make aran-lake` to rebuild.
clean-all: clean
	@echo "Removing Python virtual environment (aran-lake/.venv)..."
	rm -rf aran-lake/.venv
	@echo "Done. Run 'make all' or 'make aran-lake' to re-bootstrap the venv."
