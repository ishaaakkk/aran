---
title: 'File uploads'
sidebar_label: 'File uploads'
sidebar_position: 3
description: 'The FILE_SYSTEM repository type: a named home for CSV, JSON and Parquet files you upload.'
# Diátaxis mode: reference
---

A `FILE_SYSTEM` repository holds the files you upload to it. It has nothing to
connect to: Aran reads no directory on the server for it, and the only way
data gets in is an upload through the dataset wizard or the API.

## Setup

1. **Repositories → Register repository**, pick a project, confirm.
2. Fill in:
   - **Name** and **Description**
   - **Type:** `FILE_SYSTEM`
3. Submit. There is nothing else to configure.

Then register datasets under it by uploading files — see
[Register a data repository](../how-to/register-a-data-repository.md#upload-a-file).

## Supported capabilities

| Capability | Status |
| --- | --- |
| Connection test | **Nothing to test.** It passes and says the type holds uploads. |
| Browse contents | **No.** A scan returns an empty list. |
| Preview by name | **No.** Refused as unavailable for this type. |
| Import contents | **No.** There is no upstream to import from. |
| Live federated query | **No.** |
| File upload into this repository | **Yes** — CSV, JSON, and Parquet. |

An uploaded file has no upstream, so a dataset under this type cannot be
refreshed. To replace its data, upload the new file as a new dataset.

## Configuration reference

| Field | Required | Notes |
| --- | --- | --- |
| `name` | Yes | Rejected if blank. |
| `description` | No | |

There are no credentials and no path. A request that still sends the
`rootPath` field that older versions required is accepted, and the field
is ignored.

## Why there is no directory

Earlier versions bound this type to a directory on the backend host: Aran
listed the files in it, previewed them, and probed the path from the
connection test. Nothing could be imported from a listed file, so all that
surface did was read the server's own filesystem on behalf of any project
member. It was fenced by an operator allowlist, `aran.fs.allowed-roots`, and
then removed before the first release. That setting and its environment
variable, `ARAN_FS_ALLOWED_ROOTS`, no longer exist and are ignored if set.
To bring in files that live on a server, upload them.
