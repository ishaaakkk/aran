<!--
Thanks for contributing to Aran.

Two things worth knowing before you fill this in:

  * Commits must be signed off (DCO) — `git commit -s`. See CONTRIBUTING.md.
  * Aran's gate is `./scripts/validate.sh`. If you have no Docker daemon, run
    `./scripts/validate.sh --offline` and say so below — that is a normal and
    accepted answer, not a failure.
-->

## What this changes

<!-- One or two sentences. What is different after this PR that was not before? -->

## Why

<!--
The reasoning, not the restatement. If it fixes an issue, link it (`Fixes #123`).
If it is a decision someone might later wonder about, this is the place that
survives — the repo's convention is to explain *why*, never *what*.
-->

## How it was verified

<!-- Delete what does not apply. Be specific — "tests pass" is not a claim anyone can check. -->

- [ ] `./scripts/validate.sh` — all green
- [ ] `./scripts/validate.sh --offline` — all green (no Docker available here)
- [ ] New or changed tests cover the behaviour, with a `@DisplayName` that states the claim
- [ ] Ran it against a live stack, and watched the change work

<!--
If any part of this is written but has never run against reality — a live
backend, a real client, a browser — say so here, with how to check it.
Unverified work can land honestly; it should not land quietly.
-->

## Anything reviewers should look at closely

<!--
Optional. A decision you were unsure about, a trade-off you made, a file that
looks worse than it is and why.
-->
