#!/usr/bin/env bash
# ===========================================================================
#  Aran Stack — Installer Script
#  Provides an interactive, premium installation experience.
# ===========================================================================
set -euo pipefail

# Colors and formatting
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[0;33m'
NC='\033[0m' # No Color
BOLD='\033[1m'
RESET='\033[0m'
PRIMARY='\033[38;2;139;92;246m' # Violet

# Logger/printer functions
log_info() {
    echo -e "ℹ️  ${PRIMARY}$1${RESET}"
}

log_success() {
    echo -e "✅ ${GREEN}$1${RESET}"
}

log_warn() {
    echo -e "⚠️  ${YELLOW}$1${RESET}"
}

log_error() {
    echo -e "❌ ${RED}Error: $1${RESET}" >&2
}

# 1. Print Header
printf "\033[H\033[2J"
echo "=========================================="
# Brand primary violet color representation for logo
echo -e "\033[38;2;139;92;246m   Aran Installation Script\033[0m"
echo "=========================================="
echo ""

# 2. System information
SHELL_NAME=$(basename "$SHELL")
log_info "System: $(uname -s) $(uname -r)"
log_info "Shell: $SHELL_NAME"
echo ""

# 3. Check and Install uv (Python Package Installer)
log_info "Checking python package installer 'uv'..."
if command -v uv >/dev/null 2>&1; then
    log_success "uv is already installed ($(uv --version))"
else
    log_warn "uv is not installed. Installing uv for blazing fast Python environment setups..."
    if command -v brew >/dev/null 2>&1; then
        brew install uv
    else
        curl -LsSf https://astral.sh/uv/install.sh | sh
    fi
    log_success "uv installed successfully!"
fi

# 4. Check Docker status
log_info "Checking Docker environment..."
if command -v docker >/dev/null 2>&1; then
    if docker info >/dev/null 2>&1; then
        log_success "Docker daemon is running"
    else
        log_warn "Docker is installed, but the daemon is not running. Please start Docker Desktop."
    fi
else
    log_warn "Docker is not installed. Please install Docker Desktop to run data lake and infra dependencies."
fi

# 5. Check Node.js status
log_info "Checking Node.js..."
if command -v node >/dev/null 2>&1; then
    NODE_VERSION=$(node -v)
    NODE_MAJOR=$(echo "$NODE_VERSION" | sed 's/v//' | cut -d. -f1)
    if [ "$NODE_MAJOR" -ge 20 ]; then
        log_success "Node.js $NODE_VERSION is already installed (>= 20)"
    else
        log_warn "Node.js $NODE_VERSION is installed, but version >= 20 is recommended."
    fi
else
    log_warn "Node.js is not installed. Installing via Homebrew..."
    if command -v brew >/dev/null 2>&1; then
        brew install node
        log_success "Node.js installed successfully!"
    else
        log_error "Homebrew not found. Please install Node.js manually: https://nodejs.org"
    fi
fi

# 6. Check JDK 25 (Project rule)
log_info "Checking JDK installation..."
if command -v java >/dev/null 2>&1; then
    JAVA_VER=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
    # Check if JAVA_VER is numeric
    if [[ "$JAVA_VER" =~ ^[0-9]+$ ]]; then
        if [ "$JAVA_VER" -ge 25 ]; then
            log_success "Java JDK $JAVA_VER is installed (>= 25)"
        else
            log_warn "Java JDK $JAVA_VER is installed, but version >= 25 is required."
        fi
    else
        # Fallback for newer java versions formatting
        log_success "Java is installed: $(java -version 2>&1 | head -n 1)"
    fi
else
    log_warn "Java JDK is not installed. Please install JDK 25+."
fi

echo ""
# 7. Path integration (Symlinking the aran CLI)
log_info "Installing 'aran' command line utility..."
# The CLI at the repo root is `aran`. It was `aran` until the 2026-08-24 rename, and
# this line kept pointing at the old name — a path that no longer exists, so `chmod`
# failed and `set -e` killed every `./aran install` before it installed anything.
SCRIPT_PATH="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/aran"

# Make sure command is executable
chmod +x "$SCRIPT_PATH"

# Try to symlink to /usr/local/bin
if [ -d "/usr/local/bin" ] && [ -w "/usr/local/bin" ]; then
    ln -sf "$SCRIPT_PATH" /usr/local/bin/aran
    log_success "Globally symlinked 'aran' command to /usr/local/bin/aran"
else
    log_warn "Cannot write to /usr/local/bin directly without sudo."
    echo -e "   We can either use ${BOLD}sudo${RESET} to symlink it globally, or add an alias to your ${BOLD}~/.zshrc${RESET}."
    
    # Read user input if interactive, or default to sudo if possible
    # In headless/non-interactive, fallback to adding alias
    if [ -t 0 ]; then
        read -p "👉 Would you like to use sudo to install globally? (y/n): " -n 1 -r
        echo ""
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            sudo ln -sf "$SCRIPT_PATH" /usr/local/bin/aran
            log_success "Globally symlinked 'aran' command to /usr/local/bin/aran"
        else
            # Fallback to ZSH / Bash alias
            if [ "$SHELL_NAME" = "zsh" ]; then
                echo "alias aran='$SCRIPT_PATH'" >> ~/.zshrc
                log_success "Added alias to ~/.zshrc (run 'source ~/.zshrc' to apply)"
            else
                echo "alias aran='$SCRIPT_PATH'" >> ~/.bash_profile
                log_success "Added alias to ~/.bash_profile (run 'source ~/.bash_profile' to apply)"
            fi
        fi
    else
        # Non-interactive fallback: write alias
        if [ "$SHELL_NAME" = "zsh" ]; then
            # Check if alias already exists
            if ! grep -q "alias aran=" ~/.zshrc 2>/dev/null; then
                echo "alias aran='$SCRIPT_PATH'" >> ~/.zshrc
            fi
            log_success "Added alias to ~/.zshrc (non-interactive fallback)"
        else
            if ! grep -q "alias aran=" ~/.bash_profile 2>/dev/null; then
                echo "alias aran='$SCRIPT_PATH'" >> ~/.bash_profile
            fi
            log_success "Added alias to ~/.bash_profile (non-interactive fallback)"
        fi
    fi
fi

# 8. Setup shell autocompletions for zsh/bash
log_info "Configuring shell completions for 'aran'..."
if [ "$SHELL_NAME" = "zsh" ]; then
    COMP_LINE="compdef '_arguments \"1: :(start stop restart status install admin help)\"' aran"
    if ! grep -q "$COMP_LINE" ~/.zshrc 2>/dev/null; then
        echo "$COMP_LINE" >> ~/.zshrc
        log_success "Added autocomplete functions to ~/.zshrc"
    else
        log_success "Autocomplete functions are already configured in ~/.zshrc"
    fi
fi

# 9. Final success banner
echo ""
echo "=========================================="
log_success "Installation completed successfully!"
echo "=========================================="
echo ""
echo -e "You can now run: ${BOLD}${PRIMARY}aran status${RESET} or ${BOLD}${PRIMARY}aran start${RESET}"
echo ""
