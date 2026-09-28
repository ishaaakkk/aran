"""
The dev stack's stop path stops only what the dev stack started.

``make all``, ``make stop`` and ``./aran start|stop|restart`` used to run
``pkill -f`` over ``aran-application``, ``bootRun``, ``uvicorn`` and ``vite``. That
killed every such process on the machine, the e2e suite's included, although the e2e
suite runs beside the dev stack on offset ports and the contributor guide says the two
can run at once.

These tests run the real ``scripts/shutdown.sh`` with ``docker`` stubbed, a scratch
run directory and a scratch port list, so they never touch a real stack. ``startup.sh``
cannot run here, so its half is pinned by the one test that reads it.
"""

from __future__ import annotations

import os
import re
import socket
import subprocess
import sys
import time
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[2]
SCRIPTS = REPO / "scripts"

# The e2e suite's host processes, as aran-web/e2e/stack/globalSetup.ts starts them.
E2E_COMMAND_LINES = [
    ["-m", "uvicorn", "aran_lake.main:app", "--port", "8185"],
    ["-m", "uvicorn", "freyja.api.profile_api:app", "--port", "8186"],
    ["./gradlew", ":aran-application:bootRun"],
    ["npx", "vite", "--strictPort"],
]

SLEEP = "import time; time.sleep(120)"


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def _alive(pid: int) -> bool:
    """Running, not merely a zombie waiting for a parent that may never reap it."""
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    state = subprocess.run(
        ["ps", "-o", "stat=", "-p", str(pid)], capture_output=True, text=True, check=False
    ).stdout.strip()
    return bool(state) and not state.startswith("Z")


def _await_dead(pid: int, seconds: float = 15.0) -> bool:
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if not _alive(pid):
            return True
        time.sleep(0.1)
    return not _alive(pid)


def _spawn(argv: list[str]) -> subprocess.Popen:
    # Its own session, so nothing the scripts do to a process group can reach it by accident.
    return subprocess.Popen(argv, start_new_session=True)


@pytest.fixture
def stack_env(tmp_path: Path) -> dict[str, str]:
    stub_bin = tmp_path / "bin"
    stub_bin.mkdir()
    docker = stub_bin / "docker"
    docker.write_text("#!/bin/sh\nexit 0\n")
    docker.chmod(0o755)
    env = dict(os.environ)
    env.update(
        {
            "PATH": f"{stub_bin}{os.pathsep}{env.get('PATH', '')}",
            "TUI_MODE": "0",
            "ARAN_RUN_DIR": str(tmp_path / "run"),
            # Never the real dev ports: another stack on this machine may be using them.
            "ARAN_DEV_PORTS": str(_free_port()),
            "ARAN_STOP_GRACE_SECONDS": "3",
        }
    )
    return env


def _shutdown(env: dict[str, str], cwd: Path) -> subprocess.CompletedProcess:
    result = subprocess.run(
        ["bash", str(SCRIPTS / "shutdown.sh")],
        cwd=cwd,
        env=env,
        capture_output=True,
        text=True,
        timeout=90,
        check=False,
    )
    assert result.returncode == 0, result.stdout + result.stderr
    return result


def test_stop_leaves_the_e2e_suites_processes_alone(stack_env, tmp_path):
    decoys = [_spawn([sys.executable, "-c", SLEEP, *argv]) for argv in E2E_COMMAND_LINES]
    try:
        _shutdown(stack_env, tmp_path)
        killed = [
            " ".join(argv)
            for argv, p in zip(E2E_COMMAND_LINES, decoys, strict=True)
            if not _alive(p.pid)
        ]
        assert killed == [], f"stopping the dev stack killed e2e processes: {killed}"
    finally:
        for p in decoys:
            p.kill()
            p.wait()


def test_stop_ends_every_process_group_startup_recorded(stack_env, tmp_path):
    # A launcher that forks, the way gradlew forks the Spring JVM and npm forks vite: the child is
    # what holds the port, so signalling only the recorded pid would leave it running.
    child_pid_file = tmp_path / "child.pid"
    launch = (
        f'source "{SCRIPTS / "_lib.sh"}" && '
        f'launch_service demo "{tmp_path}" demo.log '
        f"sh -c 'sleep 120 & echo $! > \"{child_pid_file}\"; wait'"
    )
    subprocess.run(["bash", "-c", launch], env=stack_env, check=True, timeout=30)

    pid_file = Path(stack_env["ARAN_RUN_DIR"]) / "demo.pid"
    assert pid_file.exists(), "launch_service records the group it started"
    leader = int(pid_file.read_text().split()[0])
    deadline = time.monotonic() + 10
    while not child_pid_file.exists() and time.monotonic() < deadline:
        time.sleep(0.05)
    child = int(child_pid_file.read_text().strip())
    try:
        assert _alive(leader)
        assert _alive(child)

        _shutdown(stack_env, tmp_path)

        assert _await_dead(leader), "the recorded group leader survived stop"
        assert _await_dead(child), "a process the launcher forked survived stop"
        assert not pid_file.exists(), "stop removes the pid file of a group it ended"
    finally:
        for pid in (leader, child):
            if _alive(pid):
                os.kill(pid, 9)


def _listen(executable: str, port: int) -> subprocess.Popen:
    listener = _spawn(
        [
            executable,
            "-c",
            "import socket, time\n"
            "s = socket.socket()\n"
            "s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)\n"
            f"s.bind(('127.0.0.1', {port}))\n"
            "s.listen()\n"
            "time.sleep(120)\n",
        ]
    )
    deadline = time.monotonic() + 10
    while True:
        try:
            socket.create_connection(("127.0.0.1", port), timeout=1).close()
            return listener
        except OSError:
            if time.monotonic() > deadline:
                listener.kill()
                raise
            time.sleep(0.05)


def test_stop_frees_a_dev_port_nothing_recorded_but_not_its_clients(stack_env, tmp_path):
    # A stack started before stop recorded anything, or one whose pid file is gone.
    port = int(stack_env["ARAN_DEV_PORTS"])
    listener = _listen(sys.executable, port)
    client = None
    try:
        client = _spawn(
            [
                sys.executable,
                "-c",
                "import socket, time\n"
                f"c = socket.create_connection(('127.0.0.1', {port}))\n"
                "time.sleep(120)\n",
            ]
        )
        time.sleep(0.5)

        _shutdown(stack_env, tmp_path)

        assert _await_dead(listener.pid), "the process listening on a dev port survived stop"
        assert _alive(client.pid), "stop killed a process that was only connected to a dev port"
    finally:
        for p in (listener, client):
            if p is not None:
                p.kill()
                p.wait()


def test_stop_spares_a_container_port_forwarder_on_a_dev_port(stack_env, tmp_path):
    # The containerised lake publishes 8085, so its listener is docker-proxy, or on macOS Docker
    # Desktop's own backend. A symlink gives the listener that process name.
    forwarder = tmp_path / "docker-proxy"
    forwarder.symlink_to(sys.executable)
    listener = _listen(str(forwarder), int(stack_env["ARAN_DEV_PORTS"]))
    try:
        _shutdown(stack_env, tmp_path)

        assert _alive(listener.pid), "stop killed a container runtime's port forwarder"
    finally:
        listener.kill()
        listener.wait()


def _commands(script: Path) -> list[str]:
    return [
        line
        for line in script.read_text().splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    ]


def test_startup_stops_and_launches_through_the_recorded_helpers():
    """startup.sh starts real services, so this reads it rather than running it."""
    for name in ("startup.sh", "shutdown.sh", "_lib.sh"):
        by_name = [c for c in _commands(SCRIPTS / name) if re.search(r"\b(pkill|killall)\b", c)]
        assert by_name == [], f"{name} stops processes by name: {by_name}"

    startup = _commands(SCRIPTS / "startup.sh")
    assert any(re.match(r"\s*stop_dev_processes\b", c) for c in startup), (
        "startup.sh clears the previous dev stack through stop_dev_processes"
    )
    # A service started with a bare nohup is never recorded, so stop could only find it by port.
    bare = [c for c in startup if re.search(r"\bnohup\b", c)]
    assert bare == [], f"startup.sh launches without launch_service: {bare}"
    launched = [c for c in startup if re.match(r"\s*launch_service\b", c)]
    assert len(launched) == 4, (
        f"lake, freyja, Vite and the backend each go through launch_service: {launched}"
    )
