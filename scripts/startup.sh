#!/usr/bin/env bash
# ===========================================================================
#  Aran Stack — Startup Script
#  Provides a clean, beautiful, and interactive Qwen-style startup console.
# ===========================================================================
set -euo pipefail

# Sourcing the shared library
# shellcheck source=scripts/_lib.sh
source "$(dirname "$0")/_lib.sh"

# Define TUI config
TUI_HEADER="[WORKBENCH - STARTING SERVICES]"
TUI_HIGHLIGHT=7

# Define script-specific step descriptions
STEP_DESC=(
  ""
  "Bootstrapping data lake Docker"
  "Checking Python virtual env"
  "Running database migrations"
  "Starting aran-lake FastAPI"
  "Starting FREYJA sidecar"
  "Starting Vite dev server"
  "Bootstrapping infra deps"
  "Starting Spring Boot backend"
)

# Individual global arrays for script-specific steps
STEP_STATUS=( "" "pending" "pending" "pending" "pending" "pending" "pending" "pending" "pending" )
STEP_ELAPSED=( "" "" "" "" "" "" "" "" "" )

render_final_dashboard() {
    if [ "$TUI_MODE" -eq 0 ]; then
        log_success "Aran Stack is fully initialized and ready!"
        echo "   • Frontend UI   : http://localhost:5173"
        echo "   • Backend API   : http://localhost:8081"
        echo "   • Data Lake API : http://localhost:8085 (FREYJA: :8086)"
        return 0
    fi
    
    echo -ne "\033[12A"
    echo -e "${BORDER}┌──────────────────────┬──────────────────────────────────────────────────────────────────┐${RESET}"
    echo -e "${BORDER}│${RESET}  ${BG_PRIMARY}${BOLD} A ${RESET}  ${BOLD}Aran${RESET}           ${BORDER}│${RESET}  ${BOLD}[WORKBENCH]${RESET}                                                     ${BORDER}│${RESET}"
    echo -e "${BORDER}├──────────────────────┼──────────────────────────────────────────────────────────────────┤${RESET}"
    echo -e "${BORDER}│${RESET}  ${MUTED}Home                ${BORDER}│${RESET}  🎨 ${SUCCESS}${BOLD}Aran Stack is fully initialized and ready!${RESET}                   ${BORDER}│${RESET}"
    echo -e "${BORDER}│${RESET}  ${MUTED}Projects            ${BORDER}│${RESET}                                                                  ${BORDER}│${RESET}"
    echo -e "${BORDER}│${RESET}  ${MUTED}Repositories        ${BORDER}│${RESET}   • Frontend UI   : ${PRIMARY}http://localhost:5173${RESET}                         ${BORDER}│${RESET}"
    echo -e "${BORDER}│${RESET}  ${MUTED}Datasets            ${BORDER}│${RESET}   • Backend API   : ${PRIMARY}http://localhost:8081${RESET}                         ${BORDER}│${RESET}"
    echo -e "${BORDER}│${RESET}  ${MUTED}Alignments          ${BORDER}│${RESET}   • Data Lake API : ${PRIMARY}http://localhost:8085${RESET} ${MUTED}(FREYJA: :8086)${RESET}        ${BORDER}│${RESET}"
    echo -e "${BORDER}│${RESET}  ${MUTED}Schema Versions     ${BORDER}│${RESET}                                                                  ${BORDER}│${RESET}"
    echo -e "${BORDER}│${RESET}  ${PRIMARY}${BOLD}Query Workbench     ${BORDER}│${RESET}  💡 ${ACCENT}Logs folder: Check *.log in the project root${RESET}                  ${BORDER}│${RESET}"
    echo -e "${BORDER}│${RESET}  ${MUTED}Organizations       ${BORDER}│${RESET}  🛑 ${BOLD}To stop the stack: Run ./aran stop${RESET}                            ${BORDER}│${RESET}"
    echo -e "${BORDER}└──────────────────────┴──────────────────────────────────────────────────────────────────┘${RESET}"
    echo ""
}

# ----------------- Main Startup TUI Flow -----------------

# Clear screen and hide cursor for TUI app feel
if [ "$TUI_MODE" -eq 1 ]; then
    printf "\033[H\033[2J"
    echo -ne "\033[?25l"
fi

cleanup() {
    if [ "$TUI_MODE" -eq 1 ]; then
        echo -ne "\033[?25h" # Restore cursor
    fi
}
trap cleanup EXIT INT TERM

# 1. Print Startup Header
echo "==========================================="
echo "      Aran Stack Startup"
echo "==========================================="
echo ""

# 2. Print System Info
echo "ℹ️  System: $(uname -s) $(uname -r)"
echo "ℹ️  Shell: $(basename "$SHELL")"
echo ""

# 3. Check Docker Daemon
if command -v docker >/dev/null 2>&1; then
    if docker info >/dev/null 2>&1; then
        log_success "Docker is running"
    else
        log_info "Docker is installed, but the daemon is not running. Attempting to start..."
        
        # Try to start Docker Desktop (or OrbStack) on macOS
        if [[ "$(uname -s)" == "Darwin" ]]; then
            open -a Docker 2>/dev/null || open -a OrbStack 2>/dev/null || true
            
            # Wait for it to boot (up to 60 seconds)
            for i in {1..30}; do
                if docker info >/dev/null 2>&1; then
                    break
                fi
                sleep 2
            done
        fi
        
        if docker info >/dev/null 2>&1; then
            log_success "Docker started successfully!"
        else
            log_error "Failed to automatically start Docker."
            echo "   Please start Docker Desktop manually and try again."
            exit 1
        fi
    fi
else
    log_error "Docker is not installed."
    echo "   You can install it on macOS via: brew install --cask docker"
    echo "   Or download it from: https://www.docker.com/products/docker-desktop/"
    exit 1
fi

# 4. Check Node.js
if command -v node >/dev/null 2>&1; then
    NODE_VERSION=$(node -v)
    NODE_MAJOR=$(echo "$NODE_VERSION" | sed 's/v//' | cut -d. -f1)
    if [ "$NODE_MAJOR" -ge 20 ]; then
        log_success "Node.js $NODE_VERSION is already installed (>= 20)"
    else
        log_info "Node.js $NODE_VERSION is installed, but version >= 20 is recommended."
    fi
else
    log_info "Node.js is not installed. Frontend might not start correctly."
fi
echo ""

# 5. Stop the previous dev stack's host processes, and nothing else (see stop_dev_processes)
stop_dev_processes

# Draw initial box state
if [ "$TUI_MODE" -eq 1 ]; then
    render_box 0 0 ""
fi

# 6. Bootstrap data lake environment (Docker containers)
run_with_spinner "docker compose -f aran-lake/docker-compose.yml up -d garage garage-init postgres > docker-lake.log 2>&1 && docker compose -f aran-lake/docker-compose.yml stop lake >> docker-lake.log 2>&1" "Bootstrapping data lake Docker containers" 1

# 7. Check/Bootstrap Python virtual environment
venv_cmd="cd aran-lake && if [ ! -f .venv/bin/alembic ]; then rm -rf .venv && if command -v python3.12 >/dev/null 2>&1; then PY_EXE=python3.12; else PY_EXE=python3; fi; if command -v uv >/dev/null 2>&1; then uv venv .venv --python \$PY_EXE && uv pip install --python .venv/bin/python -e \".[dev]\"; else \$PY_EXE -m venv .venv && .venv/bin/pip install -e \".[dev]\"; fi; fi > ../venv-bootstrap.log 2>&1"
run_with_spinner "$venv_cmd" "Checking Python virtual environment" 2

# 8. Run alembic migrations
migration_cmd="cd aran-lake && ARAN_LAKE_DATABASE_URL=\"postgresql+asyncpg://aran_lake:aran_lake_dev@localhost:5433/aran_lake\" ARAN_LAKE_S3_ENDPOINT_URL=\"http://localhost:3900\" ARAN_LAKE_S3_REGION=\"garage\" ARAN_LAKE_S3_BUCKET=\"aran-lake\" ARAN_LAKE_S3_ACCESS_KEY=\"GK000000000000000000000000\" ARAN_LAKE_S3_SECRET_KEY=\"0000000000000000000000000000000000000000000000000000000000000000\" ARAN_LAKE_SHARED_SECRET=\"dev-shared-secret-change-in-prod\" ARAN_LAKE_PROFILE=\"dev\" ARAN_LAKE_LOG_LEVEL=\"INFO\" .venv/bin/alembic upgrade head > ../migrations.log 2>&1"
run_with_spinner "$migration_cmd" "Running data lake database migrations" 3

# 9. Start aran-lake FastAPI
launch_service aran-lake aran-lake ../aran-lake.log env \
  ARAN_LAKE_DATABASE_URL="postgresql+asyncpg://aran_lake:aran_lake_dev@localhost:5433/aran_lake" \
  ARAN_LAKE_S3_ENDPOINT_URL="http://localhost:3900" \
  ARAN_LAKE_S3_REGION="garage" \
  ARAN_LAKE_S3_BUCKET="aran-lake" \
  ARAN_LAKE_S3_ACCESS_KEY="GK000000000000000000000000" \
  ARAN_LAKE_S3_SECRET_KEY="0000000000000000000000000000000000000000000000000000000000000000" \
  ARAN_LAKE_SHARED_SECRET="dev-shared-secret-change-in-prod" \
  ARAN_LAKE_PROFILE="dev" \
  ARAN_LAKE_LOG_LEVEL="INFO" \
  .venv/bin/uvicorn aran_lake.main:app --port 8085
if ! poll_endpoint "http://127.0.0.1:8085/health" "aran-lake FastAPI service" 4 30; then
    log_error "aran-lake failed to start. Check aran-lake.log for details."
    exit 1
fi

# 10. Start FREYJA sidecar service
(
  if [ -d aran-lake/.venv ]; then
    aran-lake/.venv/bin/python -c "import jellyfish, numpy, pandas, sklearn, joblib, Levenshtein" >/dev/null 2>&1 || { \
      if command -v uv >/dev/null 2>&1; then \
        uv pip install --python aran-lake/.venv/bin/python -r freyja/requirements.txt > /dev/null 2>&1; \
      else \
        aran-lake/.venv/bin/python -m pip install -r freyja/requirements.txt > /dev/null 2>&1; \
      fi \
    }
    # FREYJA_SHARED_SECRET must match aran.freyja.secret in application.yml, or
    # Aran's calls get a 401. It is also mandatory: the sidecar refuses to start
    # without it (freyja/api/security.py).
    #
    # Keep every comment ABOVE this block. A comment between two backslash-continued
    # lines silently swallows the assignments before it — the shell joins the lines,
    # then treats everything from `#` onward as a comment, so the child process never
    # receives them. That is what used to happen here: uvicorn started with
    # FREYJA_SHARED_SECRET but WITHOUT ARAN_LAKE_URL or ARAN_LAKE_FREYJA_SECRET, so
    # LakeConfig.from_env() raised KeyError and the sidecar died during startup.
    launch_service freyja . freyja-sidecar.log env \
      ARAN_LAKE_URL="http://localhost:8085" \
      ARAN_LAKE_FREYJA_SECRET="dev-shared-secret-change-in-prod" \
      FREYJA_SHARED_SECRET="${ARAN_FREYJA_SECRET:-dev-freyja-secret-change-in-prod}" \
      aran-lake/.venv/bin/uvicorn freyja.api.profile_api:app --port 8086
  fi
)
if ! poll_endpoint "http://127.0.0.1:8086/healthz" "FREYJA sidecar service" 5 30; then
    log_error "FREYJA sidecar failed to start. Check freyja-sidecar.log for details."
    exit 1
fi

# 11. Start Frontend Vite Dev Server
(
  cd aran-web
  if [ ! -d node_modules ]; then
    npm install > npm-install.log 2>&1
  fi
)
launch_service frontend aran-web ../frontend.log npm run dev
if ! poll_endpoint "http://localhost:5173" "Vite dev server" 6 20; then
    log_error "Vite dev server failed to start. Check frontend.log for details."
    exit 1
fi

# 12. Bootstrapping dev infrastructure (Docker compose)
run_with_spinner "docker compose up -d > docker-infra.log 2>&1" "Bootstrapping infrastructure dependencies (Postgres + Keycloak + Fuseki)" 7

# 13. Start Spring Boot dev backend
launch_service backend . backend.log env SPRING_PROFILES_ACTIVE=dev ./gradlew :aran-application:bootRun
if ! poll_endpoint "http://127.0.0.1:8082/actuator/health" "Spring Boot backend" 8 120; then
    log_error "Spring Boot backend failed to start. Check backend.log for details."
    exit 1
fi

# 14. Final UI Screen transition
render_final_dashboard

# 15. Open frontend in browser
if command -v open >/dev/null 2>&1; then
    open http://localhost:5173
elif command -v xdg-open >/dev/null 2>&1; then
    xdg-open http://localhost:5173 &>/dev/null &
elif command -v start >/dev/null 2>&1; then
    start http://localhost:5173
fi
