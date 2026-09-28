# FREYJA — the profiling and join-discovery sidecar, as an image.
#
#   docker build -t aran-freyja:dev freyja/
#
# The build context is this directory. freyja is not an installable package: it is imported as
# `freyja.api.profile_api` from its PARENT directory (requirements.txt says why), so the tree is
# copied to /app/freyja and uvicorn runs from /app — the same layout `make freyja-sidecar` gets
# from the repository root.

# ---------- builder ----------
# 3.12, matching aran-lake: in development freyja shares the lake's venv, whose
# requires-python is >=3.12,<3.13, so that is the interpreter every freyja test has run on.
FROM python:3.12-slim AS builder

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

COPY requirements.txt /build/requirements.txt
RUN python -m venv /opt/venv \
    && /opt/venv/bin/pip install -r /build/requirements.txt

# ---------- runtime ----------
FROM python:3.12-slim AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PATH="/opt/venv/bin:${PATH}"

# Non-root, with a writable home: federated profiling INSTALLs DuckDB scanner extensions at run
# time, and DuckDB writes them under ~/.duckdb.
RUN groupadd --system --gid 1000 aran \
    && useradd --system --uid 1000 --gid aran --home-dir /app --create-home aran

WORKDIR /app

COPY --from=builder /opt/venv /opt/venv
# models/model.pkl comes with the tree; requirements.txt pins scikit-learn to the exact version
# that pickled it, which is what lets /healthz report "loaded".
COPY --chown=aran:aran . ./freyja/

USER aran

EXPOSE 8086

# /healthz answers 503 when the join-discovery model failed to load, so a sidecar that would
# answer every rank request with an error does not report healthy.
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8086/healthz', timeout=3)" || exit 1

# Configuration is environment only, and the process refuses to start without an inbound secret
# (freyja/api/security.py): FREYJA_SHARED_SECRET(S), ARAN_LAKE_URL, ARAN_LAKE_FREYJA_SECRET.
CMD ["uvicorn", "freyja.api.profile_api:app", "--host", "0.0.0.0", "--port", "8086"]
