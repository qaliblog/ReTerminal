### Android AI Agent Upgrade Plan

#### Context and issues observed
- Commands log showed `pip` and `python3` missing in Alpine chroot; exit code 127.
- Dependency install attempted from the parent directory, not the app folder containing `requirements.txt`.
- No preflight environment setup, CA/TLS checks, or venv usage before `pip`.

#### What this change adds
- `scripts/agent_preflight.sh`: portable preflight script that:
  - Detects environment (Alpine vs Termux) and installs Python, pip, CA certs, and build tooling.
  - Creates a local virtual environment `.venv` in the project root and upgrades pip.
  - Performs basic DNS/HTTPS checks to `pypi.org` and updates CA certs if needed.
  - Installs dependencies from a specified `requirements.txt`, honoring `PIP_INDEX_URL` if provided.
- This document with prompt-engineering and connectivity recommendations.

#### Prompt-engineering updates (operational rules)
- Always detect OS and package manager; branch for Alpine (`apk`) and Termux (`pkg`).
- Ensure `python3`, `pip`, `git`, and `ca-certificates` exist before invoking `pip`.
- Use absolute paths and verify the `requirements.txt` exists in the intended project directory.
- Create and use a project-local `.venv`; upgrade pip before install.
- Add bounded retries and clear error surfacing; never proceed after failing preflight checks.

#### Connectivity and environment resilience
- On Alpine: `apk update && apk add --no-cache python3 py3-pip ca-certificates build-base openssl-dev libffi-dev` and `update-ca-certificates`.
- On Termux: `pkg update -y && pkg install -y python clang libffi openssl ca-certificates`.
- Check DNS and HTTPS reachability to PyPI; if TLS fails, refresh CA certs and retry.
- Support mirrors via `PIP_INDEX_URL` and timeouts via `--timeout 60`.

#### Usage
```bash
# From repo root
bash scripts/agent_preflight.sh /absolute/path/to/project /absolute/path/to/project/requirements.txt

# Example (Alpine chroot path)
bash scripts/agent_preflight.sh \
  /data/user/0/com.rk.terminal.debug/local/alpine/home/milad/projects/TicTacToeFlask \
  /data/user/0/com.rk.terminal.debug/local/alpine/home/milad/projects/TicTacToeFlask/requirements.txt
```

#### Next steps
- Integrate preflight invocation before any `pip` actions in the agent flow.
- Log each step with command, `wd`, exit code, and timestamps to aid diagnosis.
- Optionally add wheel caching to mitigate flaky networks.