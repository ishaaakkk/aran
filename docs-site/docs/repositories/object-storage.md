---
title: 'Object storage'
sidebar_label: 'Object storage'
sidebar_position: 2
description: 'Connecting S3, Google Cloud Storage, Azure Blob Storage, and MinIO buckets, and the state of reading objects out of them.'
# Diátaxis mode: reference
---

Object-storage repositories record where a bucket is and how to authenticate
to it. Aran confirms the service endpoint is reachable and holds a complete
reader for S3-compatible objects, but no HTTP endpoint or button registers a
dataset from an object yet. See [Reading objects](#reading-objects) below.

## Prerequisites

- The endpoint must be reachable from the Aran backend.
- Access key and secret for the bucket, if you want them stored for later.

## Setup

1. **Repositories → Register repository**, pick a project, confirm.
2. Fill in:
   - **Name** and **Description**
   - **Type:** *Object Storage — connect only (upload files)*. The label says
     what the kind does today: a connection test and file uploads, no import
     from the bucket (see [Supported capabilities](#supported-capabilities)).
   - **Provider:** `S3`, `MINIO`, `GCS`, or `AZURE` — the picker marks `GCS`
     and `AZURE` *untested*: no test has ever run against either.
   - **Endpoint** — required for MinIO and anything self-hosted; optional for the
     three public providers, each with a default
   - **Bucket name**, **Region**
   - **Access key**, **Secret key**
3. Submit, then run the connection test.

## Supported capabilities

| Capability | Status |
| --- | --- |
| Connection test | **Yes, but shallow** — see below. |
| Browse contents | **No.** Listing returns empty. |
| Register a dataset from an object | **Not yet.** The service method and its tests exist; no HTTP route or button calls it. |
| Live query over an object in place | **Not yet.** The read path is built; nothing produces the source graph it needs. |
| File upload into this repository | Yes. |

### What the connection test proves

Only that the endpoint answers HTTP. Aran issues a plain `GET` to the
endpoint and treats **any** HTTP status as success, including 403 and 404.
The reasoning: an HTTP error still shows the service is up. Aran reports only
a transport-level failure (DNS, timeout, TLS handshake) as a failure.

A green result therefore proves little:

- It does **not** validate your access key or secret. Aran stores them, but
  does not use them.
- It does **not** check that the bucket exists, or that you can read it.
- For the public providers, it probes the provider's *global* endpoint. So a
  correct-looking pass says nothing about your specific bucket or region.

### Endpoint defaults

When **Endpoint** is left blank, the provider decides what gets probed:

| Provider | Endpoint used |
| --- | --- |
| `S3` / `AWS` | `https://s3.amazonaws.com` |
| `GCS` | `https://storage.googleapis.com` |
| `AZURE` | `https://blob.core.windows.net` |
| Anything else, including `MINIO` | None — the test fails, asking for an explicit endpoint |

## Reading objects

Aran treats object storage as a real source kind internally. Three pieces are
built and tested:

- **Metadata-only schema extraction.** Aran describes the object to learn its
  columns and their types. A Parquet object gives up its footer. A CSV object
  gives up a bounded sample. Aran never reads the object's rows to do it.
- **Live reads in place.** A query resolves an `s3://` object to a DuckDB read
  over `httpfs`. Aran builds a temporary credential on the server, uses it for
  that one connection, and discards it. The credential never appears in a
  plan, an error, or any response a client can read.
- **A copy into the lake.** Aran can copy one object into its own lake through
  the same path a file upload takes. Choosing between a live read and a copy
  is a per-dataset choice, not a repository setting: one bucket can hold an
  object worth reading live and one worth copying.

:::warning None of this is reachable yet
No button, and no HTTP endpoint, registers a dataset from an object in a
bucket. The copy into the lake exists as a service method with tests
(`registerDatasetFromObjectStorage`), but no controller exposes it. The live
read path is built into the query engine, but nothing bootstraps the source
graph it reads from. Until a path exists, get an object into Aran by
downloading it and uploading it as a file. You can file that upload under
this repository, so the provenance stays recorded where you would expect it.
:::

## Configuration reference

| Field | Required | Notes |
| --- | --- | --- |
| `provider` | Yes | Must not be blank. Case-insensitive when matched for endpoint defaults. |
| `bucketName` | Yes | Must not be blank. Stored; not verified. |
| `endpoint` | Conditional | Required for MinIO and any provider with no known default. It also decides addressing: Aran reads with path-style addressing for every provider except AWS S3 with a blank endpoint. |
| `region` | No | Stored; used only by the reader described above. |
| `accessKey` | No | **Stored in plain text**, and returned unmasked to any project member. Treat it as an identifier, not a secret. |
| `secretKey` | No | Encrypted at rest. Masked as `********` in every API response. |

## Known limitations

- **No path in the product registers a dataset from a bucket.**
- **The access key is not encrypted at rest and not masked on the wire.**
  Only the secret key is. Do not put a secret in the access-key field.
- **The connection test does not authenticate.** A green result means "the
  service is up", not "these credentials work on this bucket".
- **No object listing.** The dataset picker shows nothing for this type.
- **The reader is proven against Garage only.** Every test runs against a
  Garage container — the S3-compatible server Aran's own stack runs —
  standing in for S3. No real AWS S3, Google Cloud Storage, or Azure
  account has been used. Region handling, URL style, TLS chains, and error
  bodies are unverified against a real provider. Garage refuses a request
  signed for any region but its own (`garage` in Aran's stack), so a Garage
  repository needs that **Region**, not the form's `us-east-1` default.
- **Azure Blob Storage is not covered.** It does not speak the S3 API, so the
  reader's credential is the wrong shape for it. Supporting Azure is design
  work, not configuration.
- **Two repositories over one bucket have no defined winner.** Aran scopes a
  storage credential by URL prefix. Two repositories with different
  credentials over the same bucket path are not resolved in any stated order.
- **Refresh is declared, not wired.** The object-copy service method records
  no refresh upstream, unlike table import and stream import, so nothing
  could re-copy an object even once the path is exposed.

## Related

- [Data repositories](./index.md) — what every type can and cannot do.
- [Relational databases](./relational-databases.md) — the one type that reads a source in place today.
