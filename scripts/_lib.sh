#!/usr/bin/env bash
# ===========================================================================
#  Aran Stack — Shared Library
#  Contains common TUI styling, loggers, redrawing logic, and execution loops.
# ===========================================================================

# Standard colors and formatting
RED='\033[0;31m'
NC='\033[0m' # No Color

# truecolor/ansi color conventions from the Aran web app's theme
PRIMARY="\033[38;2;139;92;246m"        # Purple / Violet (#8B5CF6)
BG_PRIMARY="\033[48;2;139;92;246m\033[38;2;255;255;255m" # White text on Violet background
SUCCESS="\033[38;2;61;206;160m"        # Neon Teal / Green (#3DCEA0)
ACCENT="\033[38;2;232;179;57m"         # Gold / Yellow (#E8B339)
MUTED="\033[38;2;126;126;138m"         # Muted Slate (#7E7E8A)
BORDER="\033[38;2;50;50;65m"           # Cosmic dark border (#22222B)
TEXT="\033[38;2;232;232;238m"          # Bright foreground text
BOLD="\033[1m"
RESET="\033[0m"

# Logger/printer functions wired to standard colors
log_info() {
    echo -e "ℹ️  ${PRIMARY}$1${RESET}"
}

log_success() {
    echo -e "✅ ${SUCCESS}$1${RESET}"
}

log_warn() {
    echo -e "⚠️  ${ACCENT}$1${RESET}"
}

log_error() {
    echo -e "❌ ${RED}Error: $1${RESET}" >&2
}

# TUI mode detection (allows environment override)
if [ "${TUI_MODE:-}" = "" ]; then
    if [ -t 1 ]; then
        TUI_MODE=1
    else
        TUI_MODE=0
    fi
fi

# Track redraw states
FIRST_DRAW=1

# Helper to calculate terminal column visual width (emojis ✅/⏳/❌ take 2 columns)
get_visual_width() {
    local str=$1
    local len=${#str}
    
    local without_check="${str//✅/}"
    local num_checks=$(( len - ${#without_check} ))
    
    local without_hour="${str//⏳/}"
    local num_hours=$(( len - ${#without_hour} ))
    
    local without_cross="${str//❌/}"
    local num_crosses=$(( len - ${#without_cross} ))
    
    local visual_len=$(( len + num_checks + num_hours + num_crosses ))
    echo "$visual_len"
}

render_box() {
    local active_step=$1
    local active_pct=$2
    local active_spin=$3
    
    # In non-TUI mode, suppress TUI box rendering
    if [ "$TUI_MODE" -eq 0 ]; then
        return 0
    fi
    
    local num_steps=$(( ${#STEP_DESC[@]} - 1 ))
    local line_count=12
    
    # Restore cursor to top left of the box to redraw in-place
    if [ "$FIRST_DRAW" -eq 0 ]; then
        echo -ne "\033[${line_count}A"
    else
        FIRST_DRAW=0
    fi
    
    local header_printable="  ${TUI_HEADER}"
    local header_pad=$(( 66 - ${#header_printable} ))
    local header_pad_spaces=""
    for ((j=0; j<header_pad; j++)); do
        header_pad_spaces="${header_pad_spaces} "
    done
    
    echo -e "${BORDER}┌──────────────────────┬──────────────────────────────────────────────────────────────────┐${RESET}"
    echo -e "${BORDER}│${RESET}  ${BG_PRIMARY}${BOLD} A ${RESET}  ${BOLD}Aran${RESET}           ${BORDER}│${RESET}  ${BOLD}${TUI_HEADER}${RESET}${header_pad_spaces}${BORDER}│${RESET}"
    echo -e "${BORDER}├──────────────────────┼──────────────────────────────────────────────────────────────────┤${RESET}"
    
    local highlight_idx=$TUI_HIGHLIGHT
    if [ "$active_step" -gt 0 ] && [ "$active_step" -le 8 ]; then
        highlight_idx=$active_step
    fi
    
    for i in {1..8}; do
        local left_text=""
        case "$i" in
            1) left_text="  Home                " ;;
            2) left_text="  Projects            " ;;
            3) left_text="  Repositories        " ;;
            4) left_text="  Datasets            " ;;
            5) left_text="  Alignments          " ;;
            6) left_text="  Schema Versions     " ;;
            7) left_text="  Query Workbench     " ;;
            8) left_text="  Organizations       " ;;
        esac
        
        # Highlight specific item if configured
        if [ "$i" -eq "$highlight_idx" ]; then
            left_text="  ${PRIMARY}${BOLD}${left_text:2}${RESET}"
        else
            left_text="  ${MUTED}${left_text:2}${RESET}"
        fi
        
        local raw_text=""
        local color_text=""
        
        if [ "$i" -le "$num_steps" ]; then
            if [ "${STEP_STATUS[$i]}" = "completed" ]; then
                raw_text="   ✅ ${STEP_DESC[$i]} completed successfully (${STEP_ELAPSED[$i]}s)"
                color_text="   ✅ ${SUCCESS}${STEP_DESC[$i]} completed successfully (${STEP_ELAPSED[$i]}s)${RESET}"
            elif [ "${STEP_STATUS[$i]}" = "failed" ]; then
                raw_text="   ❌ ${STEP_DESC[$i]} FAILED"
                color_text="   ❌ ${RED}${STEP_DESC[$i]} FAILED${RESET}"
            elif [ "$i" -eq "$active_step" ]; then
                if [ "$active_pct" -lt 0 ]; then
                    # Honest indicator: no percentage bar for background commands
                    raw_text="   ⏳ ${STEP_DESC[$i]}... [${active_spin}]"
                    color_text="   ⏳ ${BOLD}${STEP_DESC[$i]}...${RESET} [${active_spin}]"
                else
                    # Honest indicator: driven by elapsed/timeout metrics
                    local width=15
                    local filled=$(( width * active_pct / 100 ))
                    local empty=$(( width - filled ))
                    
                    local raw_bar=""
                    for ((j=0; j<width; j++)); do
                        if [ "$j" -lt "$filled" ]; then raw_bar="${raw_bar}█"; else raw_bar="${raw_bar}░"; fi
                    done
                    raw_text="   ⏳ [${raw_bar}] ${active_pct}%  ${STEP_DESC[$i]}... [${active_spin}]"
                    
                    local color_bar=""
                    for ((j=0; j<width; j++)); do
                        if [ "$j" -lt "$filled" ]; then 
                            color_bar="${color_bar}${PRIMARY}█"
                        else 
                            color_bar="${color_bar}${SUCCESS}░"
                        fi
                    done
                    color_text="   ⏳ ${PRIMARY}[${color_bar}${PRIMARY}] ${active_pct}%${RESET}  ${BOLD}${STEP_DESC[$i]}...${RESET} [${active_spin}]"
                fi
            else
                raw_text="   • ${STEP_DESC[$i]} (Pending)"
                color_text="   ${MUTED}• ${STEP_DESC[$i]} (Pending)${RESET}"
            fi
        else
            raw_text=""
            color_text=""
        fi
        
        local visual_width
        visual_width=$(get_visual_width "$raw_text")
        
        local pad_needed=$(( 66 - visual_width ))
        if [ "$pad_needed" -lt 0 ]; then
            pad_needed=0
        fi
        
        local pad_spaces=""
        for ((j=0; j<pad_needed; j++)); do
            pad_spaces="${pad_spaces} "
        done
        
        echo -e "${BORDER}│${RESET}${left_text}${BORDER}│${RESET}${color_text}${pad_spaces}${BORDER}│${RESET}"
    done
    
    echo -e "${BORDER}└──────────────────────┴──────────────────────────────────────────────────────────────────┘${RESET}"
}

run_with_spinner() {
    local cmd=$1
    local description=$2
    local step_idx=$3
    
    local start_time
    start_time=$(date +%s)
    
    STEP_STATUS[step_idx]="running"
    
    if [ "$TUI_MODE" -eq 0 ]; then
        log_info "[Step $step_idx/$(( ${#STEP_DESC[@]} - 1 ))] Starting: $description..."
    fi
    
    eval "$cmd" &
    local pid=$!
    
    local count=0
    local spinstr='/-\|'
    while kill -0 $pid 2>/dev/null; do
        if [ "$TUI_MODE" -eq 1 ]; then
            local idx=$((count % 4))
            render_box "$step_idx" -1 "${spinstr:idx:1}"
        fi
        sleep 0.2
        count=$((count + 1))
    done
    
    # `wait` must not be the last command of a bare statement: startup.sh and
    # shutdown.sh call this function under `set -euo pipefail`, so a plain
    # `wait $pid` on a failed step makes errexit fire HERE — killing the caller
    # mid-TUI before the failure branch below can render anything. `|| exit_code=$?`
    # puts the wait in a condition context, which suspends errexit for it.
    local exit_code=0
    wait "$pid" || exit_code=$?

    local end_time
    end_time=$(date +%s)
    local elapsed=$((end_time - start_time))
    
    if [ "$exit_code" -eq 0 ]; then
        STEP_STATUS[step_idx]="completed"
        STEP_ELAPSED[step_idx]=$elapsed
        if [ "$TUI_MODE" -eq 1 ]; then
            render_box 0 100 ""
        else
            log_success "[Step $step_idx/$(( ${#STEP_DESC[@]} - 1 ))] Completed: $description (${elapsed}s)"
        fi
        return 0
    else
        STEP_STATUS[step_idx]="failed"
        if [ "$TUI_MODE" -eq 1 ]; then
            render_box 0 100 ""
            printf "\n\n❌ ${RED}Error: %s failed with exit code %d.${RESET}\n" "$description" "$exit_code"
        else
            log_error "[Step $step_idx/$(( ${#STEP_DESC[@]} - 1 ))] Failed: $description (exit code $exit_code)"
        fi
        # Every step redirects its own output to a *.log in the project root, so the
        # exit code alone is never enough to act on.
        printf "   ${MUTED}The step's output is in the project root's *.log files (docker-*.log, migrations.log, backend.log, …).${RESET}\n"
        return "$exit_code"
    fi
}

poll_endpoint() {
    local url=$1
    local description=$2
    local step_idx=$3
    local timeout=$4
    
    local start_time
    start_time=$(date +%s)
    
    STEP_STATUS[step_idx]="running"
    
    if [ "$TUI_MODE" -eq 0 ]; then
        log_info "[Step $step_idx/$(( ${#STEP_DESC[@]} - 1 ))] Starting: $description..."
    fi
    
    local count=0
    local spinstr='/-\|'
    local max_attempts=$(( timeout * 5 ))
    
    while [ "$count" -lt "$max_attempts" ]; do
        if curl -sf "$url" >/dev/null 2>&1; then
            local end_time
            end_time=$(date +%s)
            local elapsed=$((end_time - start_time))
            STEP_STATUS[step_idx]="completed"
            STEP_ELAPSED[step_idx]=$elapsed
            if [ "$TUI_MODE" -eq 1 ]; then
                render_box 0 100 ""
            else
                log_success "[Step $step_idx/$(( ${#STEP_DESC[@]} - 1 ))] Completed: $description (${elapsed}s)"
            fi
            return 0
        fi
        
        if [ "$TUI_MODE" -eq 1 ]; then
            local idx=$((count % 4))
            local pct=$(( count * 100 / max_attempts ))
            if [ "$pct" -ge 100 ]; then pct=99; fi
            render_box "$step_idx" "$pct" "${spinstr:idx:1}"
        fi
        
        sleep 0.2
        count=$((count + 1))
    done
    
    STEP_STATUS[step_idx]="failed"
    if [ "$TUI_MODE" -eq 1 ]; then
        render_box 0 100 ""
        printf "\n\n❌ ${RED}Error: %s failed to respond on %s in %d seconds.${RESET}\n" "$description" "$url" "$timeout"
    else
        log_error "[Step $step_idx/$(( ${#STEP_DESC[@]} - 1 ))] Failed: $description (Timeout after ${timeout}s)"
    fi
    return 1
}

# ----------------- Starting and stopping the dev stack's host processes -----------------
# Stop only what startup.sh started. Stopping by name (`pkill -f` over uvicorn, vite, bootRun
# and aran-application) also killed the e2e suite's lake, freyja, backend and Vite, which run
# beside the dev stack on offset ports, and every other uvicorn or vite on the machine.
# tests/dev_scripts pins this.

# Where launch_service records each service's process group. The ARAN_* overrides are for tests.
ARAN_RUN_DIR="${ARAN_RUN_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/.run}"
# The dev stack's listen ports: lake, FREYJA, backend API, backend management, Vite. The e2e
# suite's are offset from these (aran-web/e2e/stack/ports.ts), so freeing them spares it.
ARAN_DEV_PORTS="${ARAN_DEV_PORTS:-8085 8086 8081 8082 5173}"
ARAN_STOP_GRACE_SECONDS="${ARAN_STOP_GRACE_SECONDS:-10}"

# Recorded beside the pid so a stale pid file cannot name a stranger's process group once the
# pid has been recycled.
process_start_time() {
    ps -o lstart= -p "$1" 2>/dev/null || true
}

# launch_service NAME DIR LOG COMMAND... runs COMMAND from DIR in the background, writes its
# output to LOG (relative to DIR), and records its process group in $ARAN_RUN_DIR/NAME.pid. Stop
# signals the group, not the pid: gradlew forks the Spring JVM and `npm run dev` forks vite.
launch_service() {
    local name=$1 dir=$2 log=$3
    shift 3
    mkdir -p "$ARAN_RUN_DIR"
    (
        # Job control gives the background job a process group of its own, led by $!.
        set -m
        cd "$dir" || exit 1
        nohup "$@" >"$log" 2>&1 &
        local pid=$!
        printf '%s\n%s\n' "$pid" "$(process_start_time "$pid")" >"$ARAN_RUN_DIR/$name.pid"
    )
}

# A zombie counts as gone: nothing may ever reap it (a container's PID 1 often does not).
_group_alive() {
    ps -A -o pgid= -o stat= 2>/dev/null | awk -v g="$1" '$1 == g && $2 !~ /^Z/ { found = 1 } END { exit !found }'
}

_pid_alive() {
    local stat
    stat=$(ps -o stat= -p "$1" 2>/dev/null) || return 1
    [ -n "$stat" ] && [[ "$stat" != Z* ]]
}

# _await_or_kill group|pid ID... waits up to the grace period for each to exit after SIGTERM,
# then sends SIGKILL to whatever is still running.
_await_or_kill() {
    local kind=$1
    shift
    local deadline=$(( $(date +%s) + ARAN_STOP_GRACE_SECONDS ))
    local id alive
    while :; do
        alive=""
        for id in "$@"; do
            if "_${kind}_alive" "$id"; then
                alive="$alive $id"
            fi
        done
        if [ -z "$alive" ] || [ "$(date +%s)" -ge "$deadline" ]; then
            break
        fi
        sleep 0.2
    done
    for id in $alive; do
        if [ "$kind" = group ]; then
            kill -KILL -- "-$id" 2>/dev/null || true
        else
            kill -KILL "$id" 2>/dev/null || true
        fi
    done
}

# A dev-stack server still listening on a dev port: a stack started before pid files existed, a
# stale pid file, or a Spring JVM that a Gradle daemon forked outside the recorded group.
# -sTCP:LISTEN leaves out a process that is only connected to one of these ports. Only the stack's
# own runtimes (the JVM, Python for uvicorn, Node for Vite) qualify: a container's published port
# is held by docker-proxy, or on macOS by Docker Desktop's backend, and the containerised lake
# (aran-lake/docker-compose.yml) publishes 8085.
_dev_port_listeners() {
    local port pid comm
    for port in $ARAN_DEV_PORTS; do
        lsof -nP -t -iTCP:"$port" -sTCP:LISTEN 2>/dev/null || true
    done | sort -u | while read -r pid; do
        comm=$(ps -o comm= -p "$pid" 2>/dev/null | tr '[:upper:]' '[:lower:]' || true)
        case "${comm##*/}" in
            *java* | *python* | *uvicorn* | node*) echo "$pid" ;;
        esac
    done
}

stop_dev_processes() {
    local f pid started now groups=""
    for f in "$ARAN_RUN_DIR"/*.pid; do
        [ -e "$f" ] || continue
        pid=$(sed -n 1p "$f")
        started=$(sed -n 2p "$f")
        now=$(process_start_time "$pid")
        # Skip only a leader that is alive as some other process. A leader that exited keeps its
        # group id reserved while any member lives, so that id is still ours.
        if [ -n "$pid" ] && { [ -z "$now" ] || [ "$now" = "$started" ]; }; then
            kill -TERM -- "-$pid" 2>/dev/null || true
            groups="$groups $pid"
        fi
        rm -f "$f"
    done
    # shellcheck disable=SC2086 # one argument per recorded group
    _await_or_kill group $groups

    if ! command -v lsof >/dev/null 2>&1; then
        log_warn "lsof not found: not checking the dev ports ($ARAN_DEV_PORTS) for leftover processes."
        return 0
    fi
    local listeners
    listeners=$(_dev_port_listeners)
    if [ -n "$listeners" ]; then
        # shellcheck disable=SC2086 # one argument per pid
        kill -TERM $listeners 2>/dev/null || true
        # shellcheck disable=SC2086
        _await_or_kill pid $listeners
    fi
}
