// Link-check the built site. Every milestone gate runs this after `docusaurus build`.
//
// Why a script rather than a bare `linkinator` invocation, twice over:
//
//  1. The obvious skip pattern for "external links only"
//     (`^https?://(?!localhost)`) silently matches linkinator's own local server,
//     and the run then reports success after scanning zero links. A gate that
//     passes when it checked nothing is worse than no gate, so this asserts a
//     non-trivial link count as well as a clean result.
//  2. "localhost" is not a synonym for "this site". The documentation legitimately
//     names service URLs — the UI on :5173, the API on :8081, Keycloak on :8080 —
//     and remark autolinks them. Those are examples for a reader to type, not
//     links this build can resolve. Pinning the crawler to a known port and
//     following only that origin is the only rule that distinguishes them.
import {LinkChecker} from 'linkinator';

const MIN_EXPECTED_LINKS = 50;

// Fixed so the skip pattern below can name it. Chosen to avoid every port the
// docs mention and every port the dev stack uses.
const PORT = 8730;
const origin = `^https?://(localhost|127\\.0\\.0\\.1):${PORT}/`;

const checker = new LinkChecker();

const result = await checker.check({
  path: 'build',
  recurse: true,
  port: PORT,
  // Follow this site and nothing else. External links are the network's problem,
  // not the build's, and checking them makes the gate flaky.
  linksToSkip: [`^(?!${origin})`],
});

const broken = result.links.filter((link) => link.state === 'BROKEN');
const checked = result.links.filter((link) => link.state !== 'SKIPPED');

for (const link of broken) {
  console.error(`BROKEN [${link.status ?? '---'}] ${link.url}\n         found on ${link.parent}`);
}

if (broken.length > 0) {
  console.error(`\n${broken.length} broken link(s).`);
  process.exit(1);
}

if (checked.length < MIN_EXPECTED_LINKS) {
  console.error(
    `Only ${checked.length} link(s) were actually checked, which is below the ` +
      `${MIN_EXPECTED_LINKS} expected for this site. The crawl is misconfigured — ` +
      `treating this as a failure rather than a pass.`,
  );
  process.exit(1);
}

console.log(`OK — ${checked.length} links checked, none broken.`);
