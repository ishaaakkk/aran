#!/usr/bin/env bash
# ===========================================================================
#  Aran Stack — Shutdown Script
#  Provides a clean, beautiful, and interactive TUI shutdown console.
# ===========================================================================
set -euo pipefail

# Sourcing the shared library
# shellcheck source=scripts/_lib.sh
source "$(dirname "$0")/_lib.sh"

# Define TUI config
TUI_HEADER="[WORKBENCH - SHUTTING DOWN]"
TUI_HIGHLIGHT=0

# Define script-specific step descriptions
STEP_DESC=(
  ""
  "Stopping background processes"
  "Stopping infrastructure Docker"
  "Stopping data lake Docker"
)

# Individual global arrays for script-specific steps
STEP_STATUS=( "" "pending" "pending" "pending" )
STEP_ELAPSED=( "" "" "" "" )

render_final_dashboard() {
    if [ "$TUI_MODE" -eq 0 ]; then
        log_success "Aran Stack has been cleanly shut down!"
        return 0
    fi
    
    echo -ne "\033[12A"
    echo -e "${BORDER}┌──────────────────────┬──────────────────────────────────────────────────────────────────┐${RESET}"
    echo -e "${BORDER}│${RESET}  ${BG_PRIMARY}${BOLD} A ${RESET}  ${BOLD}Aran${RESET}           ${BORDER}│${RESET}  ${BOLD}[WORKBENCH]${RESET}                                                     ${BORDER}│${RESET}"
    echo -e "${BORDER}├──────────────────────┼──────────────────────────────────────────────────────────────────┤${RESET}"
    echo -e "${BORDER}│${RESET}  ${MUTED}Home                ${BORDER}│${RESET}  🛑 ${ACCENT}${BOLD}Aran Stack has been cleanly shut down!${RESET}                      ${BORDER}│${RESET}"
    echo -e "${BORDER}│${RESET}  ${MUTED}Projects            ${BORDER}│${RESET}                                                                  ${BORDER}│${RESET}"
    echo -e "${BORDER}│${RESET}  ${MUTED}Repositories        ${BORDER}│${RESET}   To start all services again, run:                              ${BORDER}│${RESET}"
    echo -e "${BORDER}│${RESET}  ${MUTED}Datasets            ${BORDER}│${RESET}   👉 ${PRIMARY}${BOLD}./aran start${RESET}                                                ${BORDER}│${RESET}"
    echo -e "${BORDER}│${RESET}  ${MUTED}Alignments          ${BORDER}│${RESET}                                                                  ${BORDER}│${RESET}"
    echo -e "${BORDER}│${RESET}  ${MUTED}Schema Versions     ${BORDER}│${RESET}                                                                  ${BORDER}│${RESET}"
    echo -e "${BORDER}│${RESET}  ${MUTED}Query Workbench     ${BORDER}│${RESET}                                                                  ${BORDER}│${RESET}"
    echo -e "${BORDER}│${RESET}  ${MUTED}Organizations       ${BORDER}│${RESET}                                                                  ${BORDER}│${RESET}"
    echo -e "${BORDER}└──────────────────────┴──────────────────────────────────────────────────────────────────┘${RESET}"
    echo ""
}

# ----------------- Main Shutdown TUI Flow -----------------

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

# 1. Print Shutdown Header
echo "==========================================="
echo "      Aran Stack Shutdown"
echo "==========================================="
echo ""

# Draw initial box state
if [ "$TUI_MODE" -eq 1 ]; then
    render_box 0 0 ""
fi

# 2. Stop the host processes startup.sh launched, and nothing else (see stop_dev_processes)
run_with_spinner "stop_dev_processes" "Stopping background processes" 1

# 3. Stop Dev infrastructure Docker compose
run_with_spinner "docker compose down > docker-infra-shutdown.log 2>&1" "Stopping infrastructure Docker" 2

# 4. Stop Data lake Docker compose
run_with_spinner "docker compose -f aran-lake/docker-compose.yml down > docker-lake-shutdown.log 2>&1" "Stopping data lake Docker" 3

# 5. Final transition
render_final_dashboard
