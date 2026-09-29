// Fails the docs gate on filler patterns that make prose read as machine-written.
// The rules and their reasons are on docs/contributing/writing-style.md; this is
// the mechanical half of them. Fenced code and inline code are ignored.
import {readdirSync, readFileSync, statSync} from 'node:fs';
import {join, relative, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

const root = resolve(fileURLToPath(import.meta.url), '..', '..');
const MAX_EM_DASHES = 8;

// The style page quotes every banned pattern, so it cannot be checked by itself.
const EXEMPT = new Set(['docs/contributing/writing-style.md']);

const PATTERNS = [
  [/\bnot (just|merely|only) [^.\n]{1,80}?,? but\b/i, '"not just X, but Y"'],
  [/\bmore than just\b/i, '"more than just"'],
  [/\b(honest|honestly|candid|candidly)\b/i, '"honest" as a claim'],
  [
    /\b(seamless(ly)?|robust|powerful|cutting-edge|leverag(e|es|ed|ing)|delve|crucial(ly)?|comprehensive|game-chang\w+|state-of-the-art)\b/i,
    'marketing vocabulary',
  ],
  [/\b(it'?s|it is) worth (noting|mentioning|pointing out)\b/i, 'filler before the point'],
  [/(^|[.!?]\s+)(importantly|notably|crucially),/i, 'filler adverb'],
  [/\bintegrates? [^.,\n]{1,60}, and \w+ integrates?\b/i, 'closing slogan'],
];

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    if (name === 'node_modules' || name === 'build' || name.startsWith('.')) continue;
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (/\.(md|mdx)$/.test(name)) out.push(p);
  }
  return out;
}

function prose(text) {
  return text
    .replace(/^---\n[\s\S]*?\n---\n/, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/```[\s\S]*?```/g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/`[^`\n]*`/g, (m) => ' '.repeat(m.length));
}

const files = [
  ...walk(join(root, 'docs')),
  join(root, 'README.md'),
  join(root, '..', 'README.md'),
];

let failures = 0;
for (const file of files) {
  const rel = relative(root, file).split('\\').join('/');
  if (EXEMPT.has(rel)) continue;
  const text = prose(readFileSync(file, 'utf8'));
  const problems = [];

  const dashes = (text.match(/—/g) ?? []).length;
  if (dashes > MAX_EM_DASHES) problems.push(`${dashes} em dashes (limit ${MAX_EM_DASHES})`);

  text.split('\n').forEach((line, i) => {
    for (const [re, label] of PATTERNS) {
      if (re.test(line)) problems.push(`line ${i + 1}: ${label}: ${line.trim().slice(0, 90)}`);
    }
  });

  if (problems.length) {
    failures += problems.length;
    console.error(`\n${rel}`);
    for (const p of problems) console.error(`  ${p}`);
  }
}

if (files.length < 10) {
  console.error(`check-prose found only ${files.length} files; the crawl is broken.`);
  process.exit(1);
}
if (failures) {
  console.error(`\ncheck-prose: ${failures} problem(s). See docs/contributing/writing-style.md.`);
  process.exit(1);
}
console.log(`check-prose: ${files.length} files clean.`);
