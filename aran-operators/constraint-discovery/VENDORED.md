# Vendored research code

The packages `edu.upc.lima`, `edu.upc.lattice`, `edu.upc.data`, and `edu.upc.utils` in this
module are a vendored copy of the DTAK constraint-discovery research codebase (LIMA lattice
scheduler). They keep their own dataset/schema model (`CSVDataset`, `Schema`) rather than
Aran's domain types.

Rules of engagement:

- The only supported entry point is `edu.upc.dtak.aran.operators.constraintdiscovery.ConstraintDiscoveryOperator`,
  which adapts Aran datasets into `CSVDataset` and drives `edu.upc.lima.Scheduler`.
- Treat the vendored packages as read-only: upstream fixes should land in the research repo
  first and be re-vendored, so the copies don't fork.
- **The copies have nevertheless been edited locally, and pretending otherwise makes the next
  re-vendor harder, not easier.** 2026-08-27 (audit CONSTRAINT_DISCOVERY-8) removed code no
  caller reached: `Lattice.supersets`/`subsets`/`fetchSupersets`/`getFrom` (and `Node.getFrom`),
  `CSVDataset.get`, `PredSet.get`, and the whole `Scheduler.ar` mode — a `static boolean ar=false`
  nothing ever set, whose branches were dead by construction (including a ternary with identical
  arms) — plus the `System.gc()` inside the lattice search loop and the locals left unread by an
  abandoned `logProbDist` criterion. Nothing that runs changed: `edu.upc.lima.
  ConstraintDiscoveryTest` exercises the algorithm and is unmodified.
- Long-term intent (from the 2026-07 health audit): repackage under
  `edu.upc.dtak.aran.operators.constraintdiscovery.vendored.*` so the module has a single
  package root. Deferred because it is a large mechanical rename touching the module's tests.
