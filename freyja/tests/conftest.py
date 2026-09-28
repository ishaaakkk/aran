"""Suite-wide pytest configuration for freyja."""
from __future__ import annotations


def pytest_configure(config) -> None:
    # The Java side tags its Testcontainers suites @Tag("docker") so
    # `validate.sh --offline` can drop them; this is the same contract here,
    # and the offline gate deselects it with `-m "not docker"`.
    config.addinivalue_line(
        "markers",
        "docker: needs a Docker daemon (Testcontainers); excluded by validate.sh --offline",
    )
