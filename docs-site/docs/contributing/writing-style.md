---
title: 'Writing style'
sidebar_label: 'Writing style'
sidebar_position: 7
description: 'The rules the documentation follows: plain prose, one term per concept, concrete claims, and the patterns the docs gate rejects.'
# Diátaxis mode: reference
---

The reader is an engineer evaluating or operating Aran, often a non-native
English speaker, usually with the page open next to a terminal. Write for that
person: say what the software does, give the command, show the output.

This rule covers prose. [Code comments](./comment-philosophy.md) follow a
different rule, because their reader already has the code open.

## Rules

**State the fact.** Prefer "Aran encrypts stored credentials" to "Aran takes
security seriously". If a sentence would survive on any product's page, delete it
or replace it with something specific: a number, a command, a file, a limit.

**Lead with what the reader needs.** Put the answer or the action first and the
reason second. Start a how-to with the steps. Start a reference page with the table.

**Vary sentence length, and keep sentences clear.** There is no word limit. A
sentence that holds two ideas is still better split, and a run of short
sentences in the same shape reads as a template. Read the paragraph aloud.

**Active voice, named actor.** "The bootstrapping operator reads the snapshot",
not "the snapshot is read".

**One term per concept.** Use **register** for adding a repository, **enrol** for
adding a dataset to an integration model, and **promote** for making a draft
schema active. Do not swap in a synonym for variety. Product names and technical
terms stay exactly as the glossary spells them.

**Plain words.** "Use", not "utilise". "To", not "in order to". "About", not
"approximately".

**Imperative for steps, present tense for behaviour.** "Run `make all`." "Aran
derives a schema."

**Say a limit once, where it matters.** Put what is tested and what is not on the
[status page](../overview/status.md). On another page, add one sentence only where
the limit changes what the reader does next, and link to the status page.

**British spelling** (organisation, optimiser, materialise). Code identifiers keep
their own spelling, for example `materialize-oql`.

## Patterns the docs gate rejects

`npm run check-prose` fails the build on these:

| Pattern | Why | Write instead |
| --- | --- | --- |
| More than eight em dashes on a page | An em dash stands in for the punctuation the sentence needs. | A full stop, a comma, a colon or brackets. |
| "not just X, but Y", "not merely", "more than just" | A template for sounding emphatic. | State Y. |
| "honest", "honestly", "candid" | A claim of honesty adds nothing and invites doubt. | State the limit plainly. |
| "seamless", "robust", "powerful", "cutting-edge", "leverage", "delve", "crucial", "comprehensive", "game-changer" | Marketing vocabulary with no content. | Say what it does. |
| "It's worth noting", "Importantly,", "Notably," | Filler before the point. | Make the point. |
| A closing slogan of the form "X integrates A, and Aran integrates B" | Rhetoric in place of a claim. | End on the last fact. |

The gate cannot catch every habit. In review, also look for bolded lead-ins on
every bullet, headings that only announce ("Overview", "Summary"), triplets used for
rhythm, and paragraphs that restate the previous one.

## What does not change

Front matter, the Diátaxis mode comment, code blocks and links are unaffected. A
rewrite changes sentences. It must still say everything the earlier text said,
correctly.

## Related

- [Comment philosophy](./comment-philosophy.md): the equivalent rule for code.
