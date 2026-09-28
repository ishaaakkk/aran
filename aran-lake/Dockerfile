# syntax=docker/dockerfile:1.7

# ---------- builder ----------
FROM python:3.12-slim AS builder

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /build

# uv for fast, reproducible dependency resolution
RUN pip install --no-cache-dir uv==0.4.27

COPY pyproject.toml README.md ./
COPY src/ ./src/

# Resolve and install into a venv we can copy to the runtime image
RUN uv venv /opt/venv \
    && uv pip install --python /opt/venv/bin/python .

# ---------- runtime ----------
FROM python:3.12-slim AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PATH="/opt/venv/bin:${PATH}"

# Non-root user for the service
RUN groupadd --system --gid 1000 aran \
    && useradd  --system --uid 1000 --gid aran --home-dir /app --create-home aran

WORKDIR /app

COPY --from=builder /opt/venv /opt/venv
COPY --chown=aran:aran src/ ./src/
COPY --chown=aran:aran alembic.ini ./
COPY --chown=aran:aran alembic/ ./alembic/

USER aran

# httpfs is not built into the duckdb Python wheel (parquet and json are), and every snapshot read
# goes through it. Left to autoinstall, the first read fetches it from extensions.duckdb.org, so a
# host without that egress cannot read a single snapshot — V69's first standalone run failed its
# profiling step exactly there — and each fresh container fetches it again. excel is the same case
# for one format: duckdb_runner INSTALLs it at the first .xlsx read, which on such a host fails
# that read. Both installed here, into the runtime user's ~/.duckdb, for the duckdb version this
# build resolved.
RUN python -c "import duckdb; con = duckdb.connect(); con.execute('INSTALL httpfs'); con.execute('INSTALL excel')"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/health', timeout=3)" || exit 1

CMD ["sh", "-c", "alembic upgrade head && exec uvicorn aran_lake.main:app --host 0.0.0.0 --port 8080 --proxy-headers"]
