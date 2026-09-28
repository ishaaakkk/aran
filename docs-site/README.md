# Aran documentation site

The published documentation for Aran, built with [Docusaurus](https://docusaurus.io/).
It is a separate npm workspace from `aran-web` and has no dependency on the running
platform.

## Local development

```bash
cd docs-site
npm install
npm start            # http://localhost:3000, live reload
```

## The gate

A documentation change is finished when all three of these pass:

```bash
npm run build           # fails on any broken internal link or anchor
npm run check-links     # crawls the built site; fails on dead links or an empty crawl
npm run check-diagrams  # renders every page in Chromium; fails if a diagram did not draw
```

`npm run validate` runs them in sequence, and `.github/workflows/docs.yml` runs the
same three on every pull request that touches this directory.

The diagram check needs a Chromium (`npx playwright install chromium`, or an
existing one found via `PLAYWRIGHT_BROWSERS_PATH`). Mermaid runs in the browser, so
a syntax error in a diagram is invisible to the other two checks. For example, a
semicolon inside a sequence diagram's `Note` ends the Mermaid statement: the diagram
renders nothing while the build and the link check stay green.

## Writing rules

- **One Diátaxis mode per page.** Every page declares its mode in a front-matter
  comment (`# Diátaxis mode: tutorial | how-to | reference | explanation`). A how-to
  guide does not carry rationale essays; an explanation page does not carry numbered
  steps. When one needs the other, it links.
- **Claims must trace to code.** Do not document a capability without a file in this
  repository that implements it. "Connects and tests" and "can be ingested and queried"
  are different claims and must not be blurred.
- **Plain register.** Describe what the software does, in the third person, in terms a
  reader could check. No marketing adjectives.
- **Front-matter scalars are quoted.** Titles and descriptions frequently contain a
  colon, which is a YAML mapping separator. An unquoted one fails the build.

## Deployment

`.github/workflows/docs.yml` builds the site on every pull request and publishes it to
GitHub Pages (https://dtak-upc.github.io/aran/) on every push to `main`. Enable Pages
with "GitHub Actions" as the source in the repository settings. The build output is a
plain static directory, so it can also be served from any static host; set
`DOCS_SITE_URL` and `DOCS_BASE_URL` (the path prefix, `/` by default) at build time.
