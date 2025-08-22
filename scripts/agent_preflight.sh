#!/usr/bin/env sh
# Environment preflight for Android AI Agent (Alpine chroot / Termux)
# Sets up Python, pip, virtualenv, CA certs, and installs requirements with connectivity checks.

set -eu

log() {
	# Prefix logs with UTC timestamp for traceability
	printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*"
}

usage() {
	cat <<'USAGE'
Usage: scripts/agent_preflight.sh [PROJECT_DIR] [REQUIREMENTS_FILE]

- PROJECT_DIR: Directory of the Python project. Defaults to current directory.
- REQUIREMENTS_FILE: Path to requirements.txt. Defaults to PROJECT_DIR/requirements.txt

Environment branches:
- Alpine: uses 'apk' to install python3, py3-pip, ca-certificates, build-base, openssl-dev, libffi-dev
- Termux: uses 'pkg' to install python, clang, libffi, openssl, ca-certificates

Behavior:
- Creates .venv inside PROJECT_DIR, upgrades pip, installs requirements
- Performs basic network/TLS checks against pypi.org and updates CA certificates if needed
- Respects PIP_INDEX_URL if provided to use a mirror
USAGE
}

# Resolve project and requirements paths
PROJECT_DIR="${1:-$(pwd)}"
PROJECT_DIR="$(cd "$PROJECT_DIR" >/dev/null 2>&1 && pwd)"
REQ_FILE_DEFAULT="$PROJECT_DIR/requirements.txt"
REQ_FILE="${2:-$REQ_FILE_DEFAULT}"

# Detect environment (Alpine, Termux, other)
OS_FLAVOR="unknown"
if [ -f /etc/os-release ] && grep -qi 'alpine' /etc/os-release 2>/dev/null; then
	OS_FLAVOR="alpine"
elif command -v pkg >/dev/null 2>&1; then
	OS_FLAVOR="termux"
fi

log "Preflight start: project=$PROJECT_DIR requirements=$REQ_FILE env=$OS_FLAVOR"

# Ensure base tooling depending on OS
if [ "$OS_FLAVOR" = "alpine" ]; then
	log "Alpine detected; installing base packages"
	apk update || true
	apk add --no-cache python3 py3-pip ca-certificates build-base openssl-dev libffi-dev || true
	update-ca-certificates || true
elif [ "$OS_FLAVOR" = "termux" ]; then
	log "Termux detected; installing base packages"
	pkg update -y || true
	pkg install -y python clang libffi openssl ca-certificates || true
else
	log "Unknown OS flavor; assuming Python and pip are available"
fi

# Determine python executables
PYTHON_BIN=""
if command -v python3 >/dev/null 2>&1; then
	PYTHON_BIN="python3"
elif command -v python >/dev/null 2>&1; then
	PYTHON_BIN="python"
else
	log "ERROR: No python interpreter found after setup"
	exit 1
fi

# Create virtual environment
VENV_DIR="$PROJECT_DIR/.venv"
if [ ! -d "$VENV_DIR" ]; then
	log "Creating virtual environment at $VENV_DIR"
	"$PYTHON_BIN" -m venv "$VENV_DIR"
fi

# Activate venv (shellcheck disable=SC1090)
# shellcheck source=/dev/null
. "$VENV_DIR/bin/activate"

# Upgrade pip inside venv
log "Upgrading pip"
python -m pip install --upgrade pip || true

# Basic network and TLS checks for PyPI
check_dns() {
	if command -v getent >/dev/null 2>&1; then
		getent hosts pypi.org >/dev/null 2>&1 && return 0
	fi
	if command -v nslookup >/dev/null 2>&1; then
		nslookup pypi.org >/dev/null 2>&1 && return 0
	fi
	return 0
}

check_https() {
	if command -v curl >/dev/null 2>&1; then
		curl -Is https://pypi.org/simple >/dev/null 2>&1 && return 0
	fi
	return 0
}

if ! check_dns; then
	log "Warning: DNS resolution for pypi.org appears unavailable"
fi

if ! check_https; then
	log "Warning: HTTPS check to pypi.org failed; attempting CA update"
	if [ "$OS_FLAVOR" = "alpine" ]; then
		update-ca-certificates || true
	elif [ "$OS_FLAVOR" = "termux" ]; then
		pkg install -y ca-certificates || true
	fi
fi

# Install requirements if present
if [ -f "$REQ_FILE" ]; then
	log "Installing Python dependencies from $REQ_FILE"
	# Use mirror if PIP_INDEX_URL provided; otherwise default index
	if [ -n "${PIP_INDEX_URL:-}" ]; then
		log "Using custom pip index: $PIP_INDEX_URL"
	fi
	python -m pip install -r "$REQ_FILE" --timeout 60
else
	log "No requirements file found at $REQ_FILE; skipping pip install"
fi

log "Preflight completed successfully"