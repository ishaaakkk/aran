"""
In-process job runner.

A single asyncio task loops, claims one PENDING job from the catalog
via ``SELECT ... FOR UPDATE SKIP LOCKED``, dispatches to the handler,
and writes the result back. The same code works unchanged when promoted
to dedicated worker containers — you just run ``run_worker_forever`` in
a separate process and the FOR UPDATE SKIP LOCKED makes concurrent
workers correct by construction.

For v0.1 we host one worker inside the API container. Switching to N
worker containers later is a deployment change, not a refactor — that's
the point of the SKIP LOCKED pattern.
"""
from __future__ import annotations

import asyncio
import copy
import traceback
from contextlib import suppress
from typing import Any, cast

import structlog

from aran_lake.catalog import repositories as repo
from aran_lake.catalog.models import Job
from aran_lake.catalog.session import session_scope
from aran_lake.config import Settings
from aran_lake.jobs.handlers import HANDLERS
from aran_lake.jobs.redaction import redacted

log = structlog.get_logger(__name__)


class _ClaimedJob:
    """
    A claimed job with the credentials the claim lifted out of the row put back — in
    memory, for the handler, and nowhere else.

    A wrapper rather than a write onto the ORM instance, because that write would be
    flushed straight back into ``job.payload`` when the runner's session commits, and the
    claim-time redaction below would undo itself. The handler reads ``payload`` from here
    and everything else (``id``, ``tenant_id``, ``created_by_caller``) from the real row.
    """

    def __init__(self, job: Job, payload: dict[str, Any]) -> None:
        self._job = job
        self.payload = payload

    def __getattr__(self, name: str) -> Any:
        return getattr(self._job, name)


def _lift_secrets(job: Job) -> dict[str, Any]:
    """
    IMPORTS-9: take the credentials out of the stored payload at claim time and return
    the full payload for the handler to use in memory.

    The import payload holds the source DSN verbatim (``api/imports.py`` puts it there;
    encrypting job payloads at rest is out of v1 scope). Redaction used to happen only in
    the handler's ``finally``, which never runs if the worker is killed mid-import — so
    the credential rested in the row indefinitely, and ``GET /api/jobs/{id}`` served it.
    Scrubbing at claim narrows the window to "while this process is actually running the
    job"; the handler's ``finally`` stays as the belt for anything this misses.

    Reassignment, not in-place mutation: a plain JSON column tracks no mutation, so a
    nested write would never reach the database.
    """
    payload = copy.deepcopy(dict(job.payload or {}))
    scrubbed = redacted(payload)
    if scrubbed != payload:
        job.payload = scrubbed
    return payload


class JobWorker:
    """
    A long-running coroutine that drains the job queue.

    Lifecycle is managed by the FastAPI lifespan: ``start()`` schedules
    the task, ``stop()`` cancels it and awaits clean shutdown.
    """

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._task: asyncio.Task[None] | None = None
        self._stopping = asyncio.Event()

    async def start(self) -> None:
        if self._task is not None:
            raise RuntimeError("worker already started")
        self._task = asyncio.create_task(self._loop(), name="aran-lake-worker")

    async def stop(self) -> None:
        if self._task is None:
            return
        self._stopping.set()
        with suppress(asyncio.CancelledError):
            self._task.cancel()
            await self._task
        self._task = None

    async def _loop(self) -> None:
        log.info("job worker started")
        try:
            while not self._stopping.is_set():
                ran_anything = await self._try_one_job()
                if not ran_anything:
                    # No job available — back off briefly. This isn't a
                    # real queue (we're polling Postgres); the poll
                    # interval trades latency for catalog load.
                    await asyncio.sleep(self._settings.job_poll_interval_seconds)
        except asyncio.CancelledError:
            log.info("job worker cancelled")
            raise
        finally:
            log.info("job worker stopped")

    async def _try_one_job(self) -> bool:
        """
        Claim and run one job. Returns True if a job was run (success or
        failure), False if the queue was empty.
        """
        # Step 1 — claim (its own transaction so the claim is durable
        # even if execution crashes; the row is now RUNNING, so a future
        # restart can decide what to do with it).
        async with session_scope() as session:
            job = await repo.claim_next_pending_job(session)
            if job is None:
                return False
            # Re-bind the values we need outside the session.
            job_id = job.id
            job_type = job.job_type
            tenant_id = job.tenant_id
            # IMPORTS-9: the credential leaves the row here, in the claim's own
            # transaction, and lives only in this variable from now on.
            payload = _lift_secrets(job)

        # Step 2 — execute. Handler manages its own session(s).
        handler = HANDLERS.get(job_type)
        if handler is None:
            await self._mark_failed(
                job_id=job_id,
                tenant_id=tenant_id,
                error=f"no handler registered for job_type={job_type}",
            )
            return True

        try:
            async with session_scope() as s:
                # Re-fetch inside the new session so the handler has a
                # live, attached Job instance to read payload from.
                job_live = await repo.get_job(s, tenant_id=tenant_id, job_id=job_id)
                if job_live is None:
                    log.error("job vanished between claim and execute",
                              job_id=str(job_id))
                    return True
                # cast: handlers are typed against the ORM row and read `payload`,
                # `id`, `tenant_id` and `created_by_caller` off it — all of which
                # _ClaimedJob serves, the first from memory instead of the row.
                job_for_handler = cast(Job, _ClaimedJob(job_live, payload))
                result = await handler(job_for_handler, self._settings)
                await repo.mark_job_succeeded(s, job=job_live, result=result)
                log.info("job succeeded",
                         job_id=str(job_id), job_type=job_type)
        except Exception as e:
            tb = traceback.format_exc()
            log.error("job failed",
                      job_id=str(job_id), job_type=job_type, error=str(e), tb=tb)
            await self._mark_failed(
                job_id=job_id,
                tenant_id=tenant_id,
                error=f"{type(e).__name__}: {e}",
            )
        return True

    async def _mark_failed(
        self, *, job_id, tenant_id, error: str,
    ) -> None:
        try:
            async with session_scope() as s:
                job = await repo.get_job(s, tenant_id=tenant_id, job_id=job_id)
                if job is not None:
                    await repo.mark_job_failed(s, job=job, error_message=error)
        except Exception:
            # If we can't even mark it failed, we've lost — but at least
            # log it so an operator can intervene manually.
            log.exception("could not mark job failed", job_id=str(job_id))
