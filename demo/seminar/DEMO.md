# Aran seminar demo: research publications

## Scenario

Two independent paper databases (ACM Digital Library and Semantic Scholar) index overlapping
researcher communities. A third dataset holds researcher profiles. Aran integrates all three:

| Dataset | File | Format | Role in integration |
|---|---|---|---|
| ACM Digital Library | `acm_papers.csv` | CSV | Source A → **IPaper** |
| Semantic Scholar | `semantic_papers.json` | JSON (one array: `authors`) | Source B → **IPaper** |
| Researcher Profiles | `researchers.json` | JSON | **Int_researchers** |

For a click-by-click version of Steps 1–3, see the docs walkthrough
(`docs-site/docs/getting-started/walkthrough.md`). The browser test suite runs that walkthrough
as written (`aran-web/e2e/specs/t1-walkthrough.spec.ts`).

### Optional: add the relational sources

The three datasets above are all files, so they show integration across *formats*
but not across *systems*. [`source-db/`](source-db/) adds two Postgres schemas, a
university publication registry and a funding agency, which you register as
ordinary relational repositories:

```bash
docker compose --profile demo up -d demo-sources
```

They extend the same scenario. `registry` becomes a **third** source for
**IPaper**; five of its publications are works already listed above, under
different identifiers. `funding` adds a concept that no file source has, and joins
to **Int_researchers** through `person_ref`. Their column and venue names differ
from the files on purpose, so join discovery has real work to do.

[`source-db/README.md`](source-db/README.md) lists what to enter in the
registration form. The host and port depend on whether Aran runs on your machine
or in a container.

**IPaper** is the union of both paper sources: one integrated class whose rows come from two
different databases. **Int_researchers** joins IPaper through the **hasAuthor** relationship,
which matches `author_id` (ACM) and `authors[]` (Semantic Scholar) to `researcher_id`. Aran
chooses its name. No other source describes researchers, so no class alignment names the class,
and a class that only a join reaches is promoted automatically as `Int_<source class>`.

---

## Step 1: Register and enroll the datasets

Register the three files in one project, then open each dataset's **Wrapper** tab and click
**Enroll in model**. Keep the wrapper that bootstrapping generated; it is already correct.

### Dataset: ACM Digital Library (`acm_papers.csv`)

The CSV is already flat (one row per paper–author pair). Its wrapper selects one column per field.

### Dataset: Semantic Scholar (`semantic_papers.json`)

The `authors` field is an array. The generated wrapper unnests it, so each author is a separate
row, as in the ACM source. That makes a clean union possible:

```sql
FROM ${SNAPSHOT} s, UNNEST(s."authors") AS u1(_r1)
```

Each record has exactly one array of plain values. Aran refuses a record with two, because
unnesting both would pair every element of one with every element of the other and multiply
every count.

### Dataset: Researcher Profiles (`researchers.json`)

Flat; one column per field.

**Keep each column's alias.** A generated wrapper aliases every column to the full IRI of the
property it feeds (`… AS "http://…/source/<dataset id>/semantic/doi"`), and the integrated views
select those exact names. A wrapper that renames its columns (`doi AS paper_id`, or a bare
`SELECT paper_id, title … FROM ${SNAPSHOT}`) still saves, but every query over the integrated
class then fails with a DuckDB binder error naming the missing column.

---

## Step 2: Align and integrate

After bootstrapping, align the three datasets. The alignment graphs show each source's raw
field names, so Semantic Scholar's paper id is `doi` and its author list is `authors`.

### IPaper: `acm` ↔ `semantic`
| Global property | ACM source property | Semantic Scholar source property |
|---|---|---|
| *(class)* `IPaper` | `acm` | `semantic` |
| `pid` | `paper_id` | `doi` |
| `title` | `title` | `title` |
| `year` | `year` | `year` |
| `authorId` | `author_id` | `authors` (unnested by the wrapper) |

*(ACM also contributes `venue`, and Semantic Scholar contributes `citation_count`. Both
appear as nullable columns in the integrated view.)*

### Join: hasAuthor, `acm` ↔ `researchers`
- Kind `join`, from `author_id` to `researcher_id`
- Label **`authorId`**, the same label as the datatype alignment above. The join matches IPaper
  rows on whatever sits under its label, and only that alignment put Semantic Scholar's unnested
  authors there. A new label also compiles, but then only ACM rows join.
- Relationship **`hasAuthor`**: the name OQL traverses. It must differ from the label. A join
  labelled and named `hasAuthor` compiles, then fails every query with
  `Ambiguous join-property name 'hasAuthor'`.

`researchers` needs no other alignment. The join auto-promotes it to **Int_researchers**, which
keeps the source's own property names (`full_name`, `affiliation`, `country`, `h_index`,
`email`) except `researcher_id`, which appears under the join's label as `authorId`.

Aran's profiling suggests this join on its own: ACM's `author_id` shares its values with
`researcher_id` (all 20 researcher IDs appear as ACM authors). Semantic Scholar has no
`author_id` column. Its ids come from the unnested `authors` array, which is why the join reuses
the `authorId` label above.

Rebuild the draft and promote it to make the `int_IPaper` and `int_Int_researchers` views live.

### Denial constraints (after promotion)

Constraints attach to an integrated class, so you can add them only after the schema is
promoted. Add them from the **Constraints** tab of any contributing dataset. Each one starts
unenforced; tick **enforced**.

**IPaper**
```
year >= 1990
year <= 2030
pid IS NOT NULL
authorId IS NOT NULL
citation_count IS NULL OR citation_count >= 0
```

The last one must handle nulls. `citation_count` is null on every ACM row, and a bare
`citation_count >= 0` is neither true nor false there, so it would drop all 40 ACM rows from
IPaper and from its quarantine view.

**Int_researchers**
```
h_index >= 0
authorId IS NOT NULL
full_name IS NOT NULL
```

---

## Step 3: OQL queries

Open **Query workbench → OQL mode**. Read the class names from the catalog. If yours differ,
use them in place of `IPaper` and `Int_researchers` below.

### Q1: All papers from both sources (the union)
```
SELECT IPaper.*
```
*84 rows: ACM's 40 and Semantic Scholar's 44 (one per author). `venue` is NULL for Semantic
Scholar rows and `citation_count` is NULL for ACM rows. With `LIMIT 30` you will probably see
only ACM rows, because the union lists one source's rows before the other's.*

---

### Q2: Papers with their authors (a join)
```
SELECT IPaper.title, Int_researchers.full_name, Int_researchers.affiliation
FROM IPaper -[hasAuthor]-> Int_researchers
```
*The path join follows the `hasAuthor` relationship. A paper with several authors gives one
row per author. Because the Semantic Scholar wrapper unnests its authors, both sources behave
the same way.*

---

### Q3: Papers by UPC researchers (filter on the right side)
```
SELECT IPaper.title, IPaper.year, Int_researchers.full_name
FROM IPaper -[hasAuthor]-> Int_researchers
WHERE Int_researchers.affiliation = 'UPC'
```
*Maria Garcia (R001) and Carlos Ruiz (R007) are both at UPC, contributing to ACM001,
ACM002, ACM005, ACM009, ACM011, ACM015, 10.1145/SS002, 10.1145/SS004, 10.1145/SS009 and
10.1145/SS011. That is 11 rows, because both wrote 10.1145/SS004. (Semantic Scholar's `pid` is
its full DOI.)*

---

### Q4: Recent papers (2024) and who wrote them (filter on the left side)
```
SELECT IPaper.title, Int_researchers.full_name, Int_researchers.affiliation
FROM IPaper -[hasAuthor]-> Int_researchers
WHERE IPaper.year > 2023
```
*Returns ACM002, ACM009, ACM012, ACM019, 10.1145/SS011 and 10.1145/SS013 with their authors:
15 rows.*

---

### Q5: Papers by researchers with h-index > 15
```
SELECT IPaper.title, IPaper.year, Int_researchers.full_name, Int_researchers.h_index
FROM IPaper -[hasAuthor]-> Int_researchers
WHERE Int_researchers.h_index > 15
```
*Returns papers authored by James Chen (24), David Kim (21), Emma Wilson (19),
Maria Garcia (18), Mei Liu (17), Thomas Müller (16).*

---

### Q6: Portuguese researchers and their papers
```
SELECT Int_researchers.full_name, IPaper.title, IPaper.year
FROM IPaper -[hasAuthor]-> Int_researchers
WHERE Int_researchers.country = 'Portugal'
```

---

## Step 4: OQL aggregate queries

OQL supports `GROUP BY`, `HAVING` and the aggregate functions `COUNT`, `SUM`, `AVG`, `MIN` and
`MAX`. Stay in **OQL mode**; you do not need SQL for these.

### A1: Paper count per institution
```
SELECT Int_researchers.affiliation, COUNT(*) AS paper_count
FROM IPaper -[hasAuthor]-> Int_researchers
GROUP BY Int_researchers.affiliation
LIMIT 20
```

### A2: Papers per year
```
SELECT IPaper.year, COUNT(*) AS papers
FROM IPaper
GROUP BY IPaper.year
LIMIT 20
```

### A3: Average h-index per country
```
SELECT Int_researchers.country, AVG(Int_researchers.h_index) AS avg_h_index, COUNT(*) AS researcher_count
FROM Int_researchers
GROUP BY Int_researchers.country
LIMIT 20
```

### A4: Institutions with more than 2 papers (HAVING)
```
SELECT Int_researchers.affiliation, COUNT(*) AS paper_count
FROM IPaper -[hasAuthor]-> Int_researchers
GROUP BY Int_researchers.affiliation
HAVING COUNT(*) > 2
```

### A5: Most prolific researchers
```
SELECT Int_researchers.full_name, COUNT(*) AS paper_count
FROM IPaper -[hasAuthor]-> Int_researchers
GROUP BY Int_researchers.full_name
HAVING COUNT(*) > 3
LIMIT 10
```

---

## Step 5: SQL mode for more advanced aggregates

Some queries cannot be written in OQL yet, for example DISTINCT inside an aggregate or CASE
expressions. For those, switch to **SQL mode** and write DuckDB SQL over the `int_` views.
Column names are full global property IRIs. Look them up in the Mappings tab, or run
`SELECT *` first to see the column headers.

The queries below use shorthand. Replace `<pid>`, `<year>` and the others with the IRIs shown
in the Mappings tab.

---

### A1: Paper count per institution, across sources
```sql
SELECT r.<affiliation>   AS institution,
       COUNT(*)           AS paper_count
FROM   int_IPaper   AS p
JOIN   int_Int_researchers AS r
       ON p.<authorId> = r.<authorId>
GROUP  BY r.<affiliation>
ORDER  BY paper_count DESC
```
*Expected top results: INESC (12), UPC (11), then ETH Zurich and Oxford (8 each). Counts are
per paper–author row and include both ACM and Semantic Scholar papers.*

---

### A2: Papers per year
```sql
SELECT p.<year>  AS publication_year,
       COUNT(DISTINCT p.<pid>) AS papers
FROM   int_IPaper AS p
GROUP  BY p.<year>
ORDER  BY publication_year
```
*Covers 2020–2024. The DISTINCT on `pid` counts a multi-author paper once, although the
integrated view has one row per author.*

---

### A3: Average h-index per country
```sql
SELECT r.<country>          AS country,
       ROUND(AVG(r.<h_index>), 1) AS avg_h_index,
       COUNT(*)             AS researcher_count
FROM   int_Int_researchers AS r
GROUP  BY r.<country>
ORDER  BY avg_h_index DESC
```
*USA (MIT and CMU) should top the list. Japan has two researchers with very different
profiles.*

---

### A4: Most prolific researchers across both paper databases
```sql
SELECT r.<full_name>        AS researcher,
       r.<affiliation> AS institution,
       COUNT(DISTINCT p.<pid>) AS paper_count
FROM   int_IPaper      AS p
JOIN   int_Int_researchers AS r
       ON p.<authorId> = r.<authorId>
GROUP  BY r.<full_name>, r.<affiliation>
ORDER  BY paper_count DESC
LIMIT  10
```
*Maria Garcia (R001) appears in ACM001, ACM005, ACM009, ACM015, 10.1145/SS002,
10.1145/SS004 and 10.1145/SS011, so she ranks first with 7 distinct papers.*

---

### A5: Citation counts (Semantic Scholar only)
```sql
SELECT p.<title>          AS title,
       p.<citation_count> AS citations
FROM   int_IPaper AS p
WHERE  p.<citation_count> IS NOT NULL
ORDER  BY citations DESC
LIMIT  10
```
*Filters to Semantic Scholar rows (ACM rows have NULL citation_count).
Top result: "Ontology-Based Data Integration: A Decade of Progress" (143 citations).*

---

### A6: Institutions with papers in both databases
```sql
SELECT r.<affiliation> AS institution,
       COUNT(DISTINCT CASE WHEN p.<pid> LIKE 'ACM%' THEN p.<pid> END) AS acm_papers,
       COUNT(DISTINCT CASE WHEN p.<pid> LIKE '10.%' THEN p.<pid> END) AS semantic_papers
FROM   int_IPaper      AS p
JOIN   int_Int_researchers AS r
       ON p.<authorId> = r.<authorId>
GROUP  BY r.<affiliation>
HAVING acm_papers > 0 AND semantic_papers > 0
ORDER  BY acm_papers + semantic_papers DESC
```
*The HAVING clause keeps only institutions whose researchers published in both databases, a
result neither source can produce on its own.*

---

## Key talking points

| Concept | Where it shows up |
|---|---|
| **Union** | `int_IPaper` has rows from ACM CSV *and* Semantic Scholar JSON |
| **Array flattening** | the generated wrapper's `UNNEST(s."authors")` normalises the JSON array before integration |
| **Join** | `FROM IPaper -[hasAuthor]-> Int_researchers` in OQL; the planner generates a SQL JOIN |
| **Global schema** | Researchers query with local names (`affiliation`, `h_index`); the IRIs stay hidden |
| **Denial constraints** | Prevent h_index < 0, future years, null keys entering the integrated view |
| **Join prediction** | Profiler detects ACM `author_id` ↔ `researcher_id` overlap (all 20 IDs appear in both) |
| **Aggregate queries** | SQL mode over `int_` views supports full DuckDB SQL (GROUP BY, HAVING, COUNT, AVG) |
