// Keep the committed OpenAPI snapshot in step with what the running service says.
//
//   node scripts/openapi-sync.mjs --write   regenerate the committed snapshot
//   node scripts/openapi-sync.mjs --check   fail if the committed snapshot is stale
//
// The docs site renders a committed spec so that building the documentation never
// needs a running backend. A committed copy of something generated elsewhere is
// exactly the artefact that goes stale in silence — that already happened to the
// frontend's checked-in openapi.d.ts, which ended up contradicting
// the backend with nothing detecting it. So `--check` runs in CI after the
// generation test, and a drifted snapshot fails the build.
//
// Keys are sorted on the way in. springdoc does not promise a stable property
// order, and an unsorted comparison would flake on reordering alone — which would
// train everyone to ignore this check, defeating the point.
import {readFile, writeFile, mkdir} from 'node:fs/promises';
import {existsSync} from 'node:fs';
import {dirname} from 'node:path';

const GENERATED = '../aran-application/build/openapi.json';
const COMMITTED = 'openapi/aran-api.json';

/** Recursively sort object keys so two logically equal specs compare byte-equal. */
function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.keys(value)
        .sort()
        .map((k) => [k, canonical(value[k])]),
    );
  }
  return value;
}

const mode = process.argv[2];
if (mode !== '--write' && mode !== '--check') {
  console.error('usage: openapi-sync.mjs --write | --check');
  process.exit(2);
}

if (!existsSync(GENERATED)) {
  console.error(
    `No generated spec at ${GENERATED}.\n` +
      'Produce one first:\n' +
      "  ./gradlew :aran-application:test --tests '*OpenApiGenerationTest'\n" +
      '(needs Docker — the test boots the app against a Testcontainers Postgres).',
  );
  process.exit(1);
}

const generated = JSON.stringify(canonical(JSON.parse(await readFile(GENERATED, 'utf8'))), null, 2) + '\n';

if (mode === '--write') {
  await mkdir(dirname(COMMITTED), {recursive: true});
  await writeFile(COMMITTED, generated);
  const spec = JSON.parse(generated);
  const paths = Object.keys(spec.paths ?? {}).length;
  const tags = new Set(
    Object.values(spec.paths ?? {}).flatMap((p) =>
      Object.values(p).flatMap((op) => op?.tags ?? []),
    ),
  );
  console.log(`Wrote ${COMMITTED}: ${paths} paths across ${tags.size} tags.`);
  process.exit(0);
}

if (!existsSync(COMMITTED)) {
  console.error(`No committed spec at ${COMMITTED}. Run with --write.`);
  process.exit(1);
}

const committed = await readFile(COMMITTED, 'utf8');
if (committed === generated) {
  console.log('OK — the committed OpenAPI snapshot matches the service.');
  process.exit(0);
}

// Name what moved rather than printing a whole-file diff: a spec is thousands of
// lines and the useful signal is almost always which paths appeared or vanished.
const before = JSON.parse(committed).paths ?? {};
const after = JSON.parse(generated).paths ?? {};
const added = Object.keys(after).filter((p) => !(p in before));
const removed = Object.keys(before).filter((p) => !(p in after));

console.error('The committed OpenAPI snapshot is stale.\n');
for (const p of added) console.error(`  + ${p}`);
for (const p of removed) console.error(`  - ${p}`);
if (added.length === 0 && removed.length === 0) {
  console.error('  (the same paths, but an operation, schema, or description changed)');
}
console.error(
  `\nRegenerate it:\n` +
    `  ./gradlew :aran-application:test --tests '*OpenApiGenerationTest'\n` +
    `  cd docs-site && node scripts/openapi-sync.mjs --write\n` +
    `then commit ${COMMITTED}.`,
);
process.exit(1);
