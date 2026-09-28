"""
scripts/admin-reset-password.sh never puts a secret in a process's argv.

Every process's command line is world-readable (``ps``, ``/proc/<pid>/cmdline``),
on the host for both ``docker compose exec …`` processes and for the kcadm JVM
inside the container. The script handles two secrets — the Keycloak master
admin password and the user's newly generated one — and both must reach kcadm
through ``KC_CLI_PASSWORD`` instead, which kcadm reads for ``config
credentials`` and for ``set-password`` alike.

``docker`` is replaced on PATH by a recorder that answers the few calls the
script makes and writes down each call's argv and the ``KC_CLI_PASSWORD`` it
was given. Watched once against real Keycloak 26.2 by the closing review
(an unprivileged ``ps`` sampler saw no secret); this pins the argv half on
every run, without Docker.
"""
from __future__ import annotations

import json
import os
import re
import stat
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "scripts" / "admin-reset-password.sh"

ADMIN_PASSWORD = "Adm1n-sentinel-pw"
USER_ID = "8d0c6a4e-0000-4000-8000-000000000001"

_FAKE_DOCKER = """#!{python}
import json, os, sys
args = sys.argv[1:]
with open(os.environ["FAKE_DOCKER_LOG"], "a") as log:
    log.write(json.dumps({{"argv": args, "kc_cli_password": os.environ.get("KC_CLI_PASSWORD")}}) + "\\n")
if args[:2] == ["compose", "ps"]:
    print("keycloak")
elif "get" in args and "users" in args:
    print("{user_id}")
"""


def _run(tmp_path: Path) -> tuple[subprocess.CompletedProcess[str], list[dict]]:
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    docker = bin_dir / "docker"
    docker.write_text(_FAKE_DOCKER.format(python=sys.executable, user_id=USER_ID))
    docker.chmod(docker.stat().st_mode | stat.S_IXUSR)
    log = tmp_path / "docker.jsonl"

    env = {
        "PATH": f"{bin_dir}{os.pathsep}{os.environ['PATH']}",
        "HOME": str(tmp_path),
        "KC_ADMIN_PASSWORD": ADMIN_PASSWORD,
        "FAKE_DOCKER_LOG": str(log),
    }
    result = subprocess.run(
        ["bash", str(SCRIPT), "alice"],
        cwd=tmp_path, env=env, capture_output=True, text=True, timeout=60, check=False,
    )
    calls = [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []
    return result, calls


def _printed_password(stdout: str) -> str:
    found = re.findall(r"^ {4}([A-Za-z0-9]{20})\s*$", stdout, re.MULTILINE)
    assert len(found) == 1, stdout
    return found[0]


def _kcadm(calls: list[dict], command: str) -> list[dict]:
    return [c for c in calls if command in " ".join(c["argv"])]


def test_the_reset_succeeds_and_prints_one_password(tmp_path):
    result, _ = _run(tmp_path)
    assert result.returncode == 0, result.stderr
    _printed_password(result.stdout)


def test_no_secret_is_ever_in_a_process_argv(tmp_path):
    result, calls = _run(tmp_path)
    assert result.returncode == 0, result.stderr
    new_password = _printed_password(result.stdout)

    assert calls, "the recorder saw no docker call"
    for call in calls:
        argv = " ".join(call["argv"])
        assert ADMIN_PASSWORD not in argv, argv
        assert new_password not in argv, argv
        assert "--password" not in call["argv"], argv
        assert "--new-password" not in call["argv"], argv


def test_each_secret_reaches_kcadm_through_its_environment(tmp_path):
    result, calls = _run(tmp_path)
    assert result.returncode == 0, result.stderr
    new_password = _printed_password(result.stdout)

    login = _kcadm(calls, "config credentials")
    assert login, "no kcadm login"
    for call in login:
        assert call["kc_cli_password"] == ADMIN_PASSWORD
        # A bare `-e NAME`: compose copies the value from the caller's
        # environment, so the value itself is never an argument.
        assert call["argv"][call["argv"].index("-e") + 1] == "KC_CLI_PASSWORD"

    (reset,) = _kcadm(calls, "set-password")
    assert reset["kc_cli_password"] == new_password
    assert reset["argv"][reset["argv"].index("-e") + 1] == "KC_CLI_PASSWORD"
    assert "--temporary" in reset["argv"]
    assert USER_ID in reset["argv"]

    # The lookup carries no secret, and is not handed one it does not need.
    for call in _kcadm(calls, "get users"):
        assert call["kc_cli_password"] is None
