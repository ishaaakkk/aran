# Browser end-to-end suite

Drives the real UI in Chromium against a stack this suite starts and stops itself.

## Running

    make e2e                      # from the repo root
    cd aran-web && npm run test:e2e -- a1     # one spec
    cd aran-web && npm run test:e2e -- --headed --workers=1   # watch it

Requires Docker and the Python environments the stack runs from: `make aran-lake-venv
freyja-deps`, or `make bootstrap` for everything. `make e2e` also builds aran-mcp's venv and
installs Chromium.

## How isolation works

The schema graph is one ACTIVE graph per tenant, not per project, and
a user belongs to exactly one tenant. Each spec therefore gets its own Keycloak user,
and with it its own tenant and its own Fuseki dataset. That is what lets the
specs run in parallel.

Identities live in one list (`SPEC_IDS` in `e2e/stack/spec-ids.mjs`) and are generated into
both the e2e Keycloak realm and the matching Flyway seed:

    node docker/e2e/generate-identities.mjs

Editing `SPEC_IDS` in `e2e/stack/spec-ids.mjs` means regenerating both the
realm and the seed with that command.

## Design facts the page objects rely on

- Join discovery runs only when asked, through "Auto-Infer Join Alignments" or the panel's
  own Run. Choosing the two sources does not start a run or open a modal over the
  controls, so `AlignmentsPage` has nothing to dismiss before it acts, and
  `closeDiscovery()` closes only the panel `runDiscovery()` opened. Opening the panel on a pair with no candidates
  starts a run, and Run stays disabled until it ends, so `runDiscovery()` clicks Run only
  when nothing is in flight. The panel closes after an accept only when every
  candidate was added; a refused one keeps it open with its reason on the row.
- A promote is confirmed in a dialog (`promote-confirm`, audit J4-F1). The dialog shows the diff
  against the ACTIVE schema and says "tenant-wide", and `promote-confirm-confirm` stays
  disabled until the diff has loaded. `AlignmentsPage.promote()` clicks through it and fails
  at once on `promote-confirm-error`, for example the server's 409 when the draft would
  remove classes another project's model promoted. Every spec runs in its own tenant with
  one project, so a spec's first attempt never meets that refusal. A retry can meet it: CI runs
  `retries: 1`, a retry reuses the spec's tenant, and the first attempt's project keeps every
  class it promoted until it compiles without it, even one the retry's project promotes too.
  Label-minted and auto-promoted IRIs usually repeat, so a retry that promotes the same classes
  passes; one whose promote drops a class the first attempt promoted (fewer sources, or a
  later step of the spec that removes one) is refused. `promote()` says so when it fails on a
  retry; the failure to read is the first attempt's.
- Confirmations are in-app (`ConfirmDialog`, J8-F14). Playwright never auto-dismisses one,
  so a spec that deletes something clicks `<testId>-confirm`.

## Ports

Everything is offset from dev so this runs beside `make all`: Vite 5273,
backend 8181, Keycloak 8180, lake 8185, freyja 8186, Fuseki 3130, Postgres
55432 (aran) / 55433 (federated source) / 55434 (lake).

## Debugging a failure

Stack logs are kept at `build/e2e-run/` whenever a test fails. Playwright
traces are under `aran-web/test-results/`:

    cd aran-web && npx playwright show-trace test-results/<dir>/trace.zip

A health-gate failure names the service that did not come up; read that
service's log first. Never pass `--reporter=line`: it replaces the config's
reporter list and drops the keep-logs reporter that preserves `build/e2e-run/`
after a failure.

## Fixtures

`e2e/data/` holds seven sources whose value domains are built for join
discovery; see the comment on `EXPECTED` in `e2e/data/index.ts`. Editing a
fixture means editing `EXPECTED` in the same commit.

`curated_papers.csv` is the eighth file and stays outside `EXPECTED` on
purpose: it carries no join-discovery signal, and adding it would make every
count there wrong. It exists for denial-constraint discovery (`v34-…`), and that
spec derives its two key facts from the file instead of hard-coding them; see
the block above `EXPECTED`.

`t1-walkthrough.spec.ts` is the exception to all of the above. It uploads
the files in `demo/seminar/`, not `e2e/data/`, because it performs the docs
walkthrough (`docs-site/docs/getting-started/walkthrough.md`). It reads the
page's own queries, alignment tables and constraints through
`e2e/data/walkthrough.ts`, and derives its expected rows from the seminar
files. Editing the page or a seminar file therefore changes that spec's
input. When they drift apart, the spec says which one it disagrees with.

`venues.parquet` is committed as a binary; regenerate with:

    aran-lake/.venv/bin/python aran-web/e2e/data/generate-parquet.py

## More

The [documentation site](https://dtak-upc.github.io/aran/) describes the platform under
test. [CONTRIBUTING.md](../../CONTRIBUTING.md) covers the wider validation gate.
