---
title: 'Singer taps'
sidebar_label: 'Singer taps'
sidebar_position: 7
description: 'Pulling data from SaaS APIs by running an operator-installed Singer tap and snapshotting its streams into the lake.'
# Diátaxis mode: reference
---

A Singer tap repository runs a [Singer](https://www.singer.io/)-protocol
extractor program on the backend and snapshots its output into the lake. It
is the one repository type that can pull from a SaaS API. Aran ships no taps:
whoever operates the deployment installs a tap binary and allowlists it, and
users then select it by id and supply its settings. A tap is a snapshot
mechanism by nature — there is no live passthrough.

## Prerequisites

- The deployment must have at least one tap configured under
  `aran.singer.taps` (see [the operator's catalogue](#the-operators-tap-catalogue)
  below). The shipped default is empty, which turns the type off. With no
  taps configured, the registration form shows:

  > No Singer taps are configured on this Aran, so this source type is off.
  > A tap is a program the server operator installs and allowlists under
  > `aran.singer.taps` — ask whoever runs this instance to add one.

- Whatever the tap itself needs: an API key, a start date, network access to
  the SaaS it calls. Aran opens no socket for a tap; the tap dials out
  itself.

## The operator's tap catalogue

Taps are deployment configuration, bound from the `aran.singer.taps` list.
The canonical example:

```yaml
aran:
  singer:
    taps:
      - id: exchangeratesapi                          # what a user selects
        label: Exchange Rates API                     # what the form shows
        command: [/opt/taps/bin/tap-exchangeratesapi] # absolute path on THIS host
        config-keys: [base, start_date, api_key]      # what a user may set
        secret-keys: [api_key]                        # which of those are credentials
```

Property-style equivalent:

```
aran.singer.taps[0].id=exchangeratesapi
aran.singer.taps[0].label=Exchange Rates API
aran.singer.taps[0].command[0]=/opt/taps/bin/tap-exchangeratesapi
aran.singer.taps[0].config-keys[0]=base
aran.singer.taps[0].config-keys[1]=api_key
aran.singer.taps[0].secret-keys[0]=api_key
```

| Field | Meaning |
| --- | --- |
| `id` | What a user selects, and what is stored on the repository. Must be unique. |
| `label` | What the form shows. Defaults to `id` when blank. |
| `command` | The argv Aran runs, as a list. Only Aran ever builds it; nothing a request carries reaches it. |
| `config-keys` | The settings a user may supply. Anything else is refused. |
| `secret-keys` | The subset of `config-keys` treated as credentials. |

A misconfigured catalogue **fails startup** rather than degrading:

- a tap with no id → *"a tap needs an id"*
- a tap with no command → *"tap 'X' has no command configured"*
- a blank argv element → *"tap 'X' has a blank argument"*
- a secret key the tap does not accept → *"tap 'X' marks keys secret that it
  does not accept: [...]"*
- two taps with one id → *"two Singer taps are configured with id 'X'"*

On startup with a non-empty catalogue, Aran logs the tap ids only — never
commands or key names. `GET /api/singer-taps` publishes
`[{id, label, configKeys, secretKeys}]` per tap, with `configKeys` sorted so
a form renders stably. The command is never published. An empty list means
the feature is off, not an error.

To prove an installed tap works end to end, run the opt-in integration test
against it:

```bash
./gradlew :aran-operators:bootstrapping:test --tests '*RealSingerTapTest*' \
    -Daran.singer.command="/path/to/venv/bin/tap-exchangeratesapi" \
    -Daran.singer.stream=exchange_rate
```

## Setup

1. **Repositories → Register repository**, pick a project, confirm.
2. Fill in:
   - **Name** and **Description**
   - **Type:** SaaS API via Singer tap (`SINGER_TAP`)
   - **Tap** — one of the deployment's configured taps
   - The tap's own settings — the form renders one field per declared
     `config-keys` entry; secret keys render as password fields labelled
     `<key> (secret)`
3. Submit, then run the connection test.

Create request:

```json
{
  "type": "SINGER_TAP",
  "name": "rates",
  "description": "daily rates",
  "tapId": "exchangeratesapi",
  "config": { "base": "EUR", "api_key": "k-live-9f2" }
}
```

Response for the same row, with `secret-keys: [api_key]`:

```json
{
  "type": "SINGER_TAP",
  "id": "…", "projectId": "…", "tenantId": "…",
  "name": "rates", "description": "daily rates",
  "tapId": "exchangeratesapi",
  "config": { "base": "EUR", "api_key": "********" }
}
```

`config` may be omitted — a tap that needs no settings is a real case (a
public feed). A setting the tap does not declare is refused, not dropped:

> tap 'exchangeratesapi' does not accept [apiKey]. It accepts: [api_key,
> base, start_date]

An unknown tap id is refused with:

> no Singer tap 'X' is configured on this Aran. Taps are declared by the
> deployment, not by users; ask whoever operates this instance to add it
> under aran.singer.taps.

The tap id and settings are re-validated against the catalogue on every
write and every probe, not once at registration — an operator can narrow the
catalogue later, and existing repositories are cut off with it.

## Supported capabilities

| Capability | Status |
| --- | --- |
| Connection test | **Yes — a real run of the tap.** |
| Browse contents | **Yes.** The stream list from the tap's own discovery. |
| Import contents | **Yes**, one dataset per stream. |
| Live federated query | **No, by nature.** A tap emits records; there is nothing to scan in place. |
| Preview | **No.** Previewing would mean running the tap. |
| File upload into this repository | Yes. |

### What the connection test proves

The test **runs the tap** and reports what happened:

- The settings pass the catalogue check, or the test fails with *"Tap
  settings are not accepted"* and the catalogue's message.
- On a build without the bootstrapping operator: *"Singer taps cannot run in
  this build"* — *"The bootstrapping operator, which runs a tap, is not on
  this server's classpath."*
- A successful run returns *"Tap ran and offered N stream(s): [...]"*.
- Any other failure returns *"The tap did not complete discovery"* with the
  tap's own diagnostics — *"exited with status 1"* tells an operator which
  half of the setup is wrong. The message never carries a config value; the
  config travels to the tap by file, not by argv.

No outbound host screening applies to this type. Aran opens no socket — the
tap dials out itself — so `aran.security.block-private-networks` does not
constrain where a tap connects. The trust decision is the operator's
catalogue.

### Browsing streams

`GET …/repositories/{id}/scan` re-validates the catalogue, then runs the
tap's discovery again. The list it returns is what stream import checks
membership against, so it must come from the source.

Discovery is **not** Singer's `--discover` mode. Aran appends only
`--config <file>` to the configured command, starts an ordinary sync, and
harvests stream names from the `SCHEMA` messages in its output. A tap whose
full sync exceeds the 1-minute discovery deadline fails both the scan and
the connection test.

### Importing a stream

`POST …/repositories/{id}/datasets/import-stream` with body
`{"stream": "exchange_rate"}` registers one dataset per stream. This is a
separate route from the relational `/datasets/import`.

- The dataset is named exactly the stream name; its description is generated
  as *"Pulled by the exchangeratesapi tap, stream exchange_rate"*; it starts
  `PENDING`.
- The stream must be in a **fresh** discovery listing, else: *"Stream 'X' is
  not offered by this tap (discovery returned N stream(s))"*.
- On a non-tap repository the route fails with
  `UNSUPPORTED_IMPORT_SOURCE`: *"Stream import is only available for Singer
  tap repositories. Use table import for relational sources or file upload
  for other kinds."*
- On a build without the extractor: *"This build cannot run Singer taps, so
  a stream cannot be imported. Use file upload instead."*
- The extract runs inside one job (kind `dataset:import`) whose id comes
  back with the registration. Progress stages: *"running the
  exchangeratesapi tap for stream exchange_rate"*, then *"uploading
  records"*. The records land in the lake as parquet through the same tail a
  file upload takes, and the ordinary bootstrap pipeline follows.
- A failed extract marks the dataset `FAILED`, not `PENDING`.

Types come from re-reading the produced parquet, not from the tap's `SCHEMA`
message — there is exactly one place types are decided.

### Refreshing

A stream dataset keeps its upstream. `POST …/datasets/{id}/refresh` re-runs
the same extract through the same job body. The default policy is
**manual** — a tap calls someone else's API, often against a rate limit, so
no schedule starts unless you set one (`PUT …/datasets/{id}/refresh-policy`).

Every run is a **full re-pull**. Singer `STATE` messages are read and
discarded, and nothing ever passes `--state`, so there is no incremental
extraction. No catalog harvest runs on refresh — a tap has no
`information_schema`.

## Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/singer-taps` | List configured taps: `{id, label, configKeys, secretKeys}` |
| `GET` | `/api/repository-types` | Every repository type, by name |
| `POST` | `/api/projects/{projectId}/repositories` | Create |
| `PUT` | `/api/projects/{projectId}/repositories/{repositoryId}` | Update |
| `POST` | `/api/projects/{projectId}/repositories/test-connection` | Run the tap's discovery |
| `GET` | `…/repositories/{repositoryId}/scan` | List streams |
| `POST` | `…/repositories/{repositoryId}/datasets/import-stream` | Register a dataset from a stream |
| `POST` | `…/datasets/{datasetId}/refresh` | Re-run the extract |

## Configuration reference

| Field | Required | Notes |
| --- | --- | --- |
| `tapId` | Yes | Must name a tap in the deployment's catalogue, checked on every write. |
| `config` | No | One JSON object of `configKeys` values. Undeclared keys are refused. Encrypted at rest as a whole. |

### Secret masking and the update sentinel

Unlike the other types, which mask one whole credential field, masking here
is **per key**, driven by the tap's declared `secretKeys`:

- A secret key comes back as `********`; a non-secret key comes back in the
  clear so you can edit it. An empty value stays empty.
- If the catalogue no longer declares the tap, **every** value is masked —
  fail closed.
- On update, the `********` sentinel means "keep the stored value", applied
  entry by entry. Only values are carried over, never keys: a key you remove
  from the form stays removed, so a tap's settings can be cleared.
- The form filters its payload to the selected tap's declared keys, so
  switching taps cannot leave a stale key behind and trigger the server's
  refusal.

At rest, the whole `config` JSON object is encrypted — the column cannot
know which keys are secret. Only the tap id is stored, never a command.

## Execution limits

| Limit | Value |
| --- | --- |
| Discovery deadline | 1 minute |
| Extract deadline | 30 minutes |
| Import byte ceiling | 2 GiB, enforced before the bytes are read into the heap |
| Environment the tap sees | `PATH`, `HOME`, `TMPDIR` only |
| Config file | mode `rw-------`, JVM temp dir, deleted in a `finally` |
| Shell | None — the argv is executed directly, never shell-interpreted |
| Timeout kill | Descendant processes first, then the tap |

The environment allowlist means a third-party binary never sees Aran's own
secrets (such as `ARAN_LAKE_SECRET`). Failure messages are exact:

- non-zero exit → *"the tap exited with status N"*
- deadline hit → *"the tap did not finish within PT1M"* (a timeout, never
  reported as status 137)
- an empty stream → *"the tap emitted no records for stream 'X'"* — a hard
  error, not an empty dataset

## Known limitations

- **No preview.** *"Preview is not available for repository type
  SingerTapRepository. Only relational databases can be previewed by name;
  upload a file to ingest data from other sources."*
- **No incremental extraction.** Every refresh is a full re-pull; `STATE`
  messages are discarded.
- **No published tap has run in Aran's CI.** Every automated test drives a
  shell script or a purpose-built test tap. Nested or varying record shapes,
  a real `--config` contract, and large streams are unproven against a
  published tap. Run `RealSingerTapTest` against your installed tap to close
  that gap for your deployment.
- **Installing and licensing a tap is the operator's act.** Aran ships no
  taps and depends on none. Check the tap's licence: the Airbyte catalogue
  is largely ELv2, while Meltano Hub's Singer taps are predominantly
  Apache-2/MIT.
- **Discovery runs a real sync.** A tap that cannot produce output inside
  1 minute cannot be scanned or connection-tested, even if a full extract
  would fit its 30-minute deadline.
- **No catalog harvest.** A tap contributes no row counts, column
  statistics, or foreign keys.

## Related

- [Data repositories](./index.md) — what every type can and cannot do.
- [Register a data repository](../how-to/register-a-data-repository.md) — the task, step by step.
