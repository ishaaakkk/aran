---
title: 'Writing style'
sidebar_label: 'Writing style'
sidebar_position: 7
description: 'The Simplified Technical English rules this site follows, and why.'
# Diátaxis mode: explanation
---

This site follows [ASD-STE100](https://www.asd-ste100.org/) — Simplified Technical
English. ASD-STE100 was built for aircraft maintenance manuals: text that a
non-native English reader must follow correctly, once, under time pressure. A
reader setting up Aran for the first time is in the same position. The rules
below are ASD-STE100's, adapted for software rather than aircraft.

This is a rule for **prose**. It does not apply to [code
comments](./comment-philosophy.md). Comments follow a different rule, for a
different reason: a comment's audience already has the code open.

## The rules

**One sentence, one idea.** If a sentence has "and", "which", or a comma doing the
work of a full stop, split it.

> Before: The usual answer is an ETL pipeline: write transformation code per
> source, land everything in a warehouse, and maintain that code forever.
>
> After: The usual answer is an ETL pipeline. You write transformation code for
> each source. You load everything into a warehouse. You maintain that code
> forever.

**Twenty words a sentence, at most.** Most real sentences in aircraft manuals run
6 to 12 words. Software explanation can run longer, but a sentence past twenty
words is a sign it holds two ideas, not one.

**Active voice. Name the actor.** "X does Y", not "Y is done by X" or "Y is
done". A reader should never have to guess who is acting.

> Before: The schema is derived by inspecting the source.
>
> After: The bootstrapping operator reads the source and derives a schema.

**One word, one meaning.** Pick one verb per action and use it everywhere.
This site uses **register** for adding a repository. It uses **enrol** for
adding a dataset to an integration model. It uses **promote** for making a
draft schema active. Never use a synonym for any of the three, even for
variety.

**Simple words over long ones.** "Use", not "utilize". "Start", not
"commence" or "initiate". "To", not "in order to". "About", not "approximately".
If a shorter, plainer word means the same thing, use it.

**No noun stacks.** English lets you chain nouns as modifiers — "query
optimiser cost estimate cache". ASD-STE100 forbids it past two. Rewrite with
a preposition.

> Before: the query optimiser's cost estimates
>
> After: the cost estimates the query optimiser uses

**Present tense for how the system behaves.** Write "Aran derives a schema",
not "Aran will derive a schema" or "Aran has derived a schema". Use another
tense only when the tense itself is the point — a past event, a planned
change.

**Imperative for instructions.** "Run `make all`", not "You should run `make
all`" or "`make all` needs to be run".

**Technical names stay exactly as they are.** ASD-STE100 exempts part names and
system names from its vocabulary rules, because you cannot rename a bolt. This
site's technical names are the product's own vocabulary: **Aran**, **OQL**,
**FREYJA**, **DuckDB**, **Postgres**, **wrapper SQL**, **tenant**, **schema
graph**, **integration model**, **join discovery**, **data repository**,
**dataset**, **snapshot**, **operator**, **Calcite**, **Keycloak**, **API
token**. Use them exactly as written elsewhere in this site. Do not invent a
synonym for one. Do not simplify one into a vaguer general word.

**Short paragraphs, real lists.** A paragraph past four or five sentences is
almost always a list that hasn't been formatted as one yet.

## What does not change

This page does not change the [Diátaxis mode](./comment-philosophy.md) each
page is written in, the front matter, code blocks, or links. Simplifying
prose is a rewrite of sentences, not of structure or of technical content.
A simplified page must still say everything the dense version said,
correctly.

## In review

A reviewer checking a page against this rule asks:

1. Does any sentence pass twenty words?
2. Does any sentence hold more than one idea?
3. Is any verb passive where the actor is knowable?
4. Does the same idea appear under two different words anywhere on the page?

A "yes" to any of these means the sentence gets rewritten, not merely trimmed.

## Related

- [Comment philosophy](./comment-philosophy.md) — the equivalent rule for code.
