# Security Policy

## Reporting a vulnerability

**Please do not open a public issue for a security problem.** Use one of these
two channels instead:

1. **GitHub private vulnerability reporting** (preferred): open the
   [Security tab](https://github.com/dtak-upc/aran/security/advisories/new)
   and file a draft advisory. The report stays private, we can work on the fix
   with you there, and GitHub can issue a CVE when the fix ships.
2. **Email**: `sergi.nadal@upc.edu`, if you prefer not to use GitHub or do not
   have an account. We do not offer PGP. If you need encrypted transport, say so
   in a first message that contains no details, and we will arrange it.

Please include, as far as you know them: the affected component and version or
commit, what an attacker gains, and the smallest reproduction you have. A proof
of concept is welcome but not required. A clear description of the flaw is
enough.

## What to expect

Aran is maintained by a small research group, not a dedicated security team.
Our response targets are:

| Stage | Target |
|---|---|
| We acknowledge your report | 5 working days |
| We tell you whether we accept it, and our assessment of severity | 15 working days |
| Fix released, or a dated plan if the fix is large | 90 days from acceptance |

If we miss one of these targets, email the address above with `[OVERDUE]` in the
subject.

We will credit you in the advisory and the changelog under the name or handle
you prefer, or leave you uncredited. Tell us which.

## Disclosure

We follow coordinated disclosure. Please give us the windows above before you
publish. If a vulnerability is being actively exploited, say so in the report:
we will set the timeline aside and work to your schedule.

We publish an advisory for every accepted vulnerability, including the ones we
find ourselves.

## Supported versions

**Aran has no released version yet.** The project is pre-1.0, and the only
supported target is the current `main` branch. There are no back-ports.

| Version | Supported |
|---|---|
| `main` | Yes |
| Anything else | No (nothing else has been released) |

The support window will be defined when the first `0.x` release is tagged.

## Scope

**In scope:** anything in this repository, and the deployment topologies it
ships (`docker-compose.yml`, `compose.standalone.yml`, the Helm/deploy assets
under `deploy/`). We are particularly interested in:

- **Tenant isolation failures.** One tenant must not reach another tenant's
  data, metadata, or schema graph. A crossing of that boundary through the query
  engine, the JDBC endpoint, the lake, Fuseki, or a Hibernate filter that does not
  apply is the highest-severity class of bug in this project.
- **SQL or OQL injection**, including anything that escapes
  `WrapperSqlValidator`'s whitelist or reaches DuckDB with attacker-controlled
  text.
- **Authentication and session handling**, including tenant resolution.
- **Credential handling** for registered data repositories.
- **Server-side request forgery** through repository connection parameters.

**Out of scope**

- Findings against a deployment you do not operate, or any host you are not
  authorised to test. Do not test against someone else's Aran instance.
- Missing hardening headers, TLS configuration, or rate limits in the **local
  development** compose files. These are development topologies. Report such
  issues against `compose.standalone.yml` or `deploy/` if they apply there.
- Vulnerabilities in third-party dependencies with no exploitable path through
  Aran. Do report the ones that have a path, and describe the path. An unfiltered
  scanner dump is not a report.
- Denial of service by sending expensive queries. Aran runs analytical queries on
  request. Resource-exhaustion bugs that are *disproportionate* to the request
  are in scope.

## Safe harbour

We will not pursue or support legal action against anyone who reports a
vulnerability in good faith under this policy, makes a reasonable effort to
avoid privacy violations and service degradation, and does not access or modify
data beyond what is needed to demonstrate the problem. If you are unsure whether
an action is covered, ask before you take it.

There is no bug bounty.
