import { describe, it, expect } from 'vitest';
import {
  buildOql, formatPredicate, fmtHaving, parseQuery, suggest,
  getCatalogData, edgeKey, opsForType, OPS_BY_TYPE,
  orderOptions, havingOptions, pruneBuild, filterGroups, insertIntoGroup, removeFromGroup, ident,
  completeOnInput, completeOnRequest, completionKey, CLOSED_COMPLETION,
  BuildState, Predicate, CompletionState,
} from './workbenchModel';
import { QueryCatalog } from '../services/types';

// Minimal two-class catalog: Artist -[created]-> Artwork
const catalog: QueryCatalog = {
  empty: false,
  schemaVersionId: 'v1',
  versionNumber: 1,
  classes: [
    {
      iri: 'http://ex/Artist', name: 'Artist', intView: 'int_Artist',
      rowCount: 10, enforcedConstraintCount: 0,
      sources: [{
        datasetId: 'ds1', wrapperSql: 'SELECT 1', intView: 'int_Artist', rowCount: 10,
        columns: [
          { globalName: 'id', sourceName: 'id' } as never,
          { globalName: 'name', sourceName: 'name' } as never,
        ],
      }],
    },
    {
      iri: 'http://ex/Artwork', name: 'Artwork', intView: 'int_Artwork',
      rowCount: 50, enforcedConstraintCount: 0,
      sources: [{
        datasetId: 'ds2', wrapperSql: 'SELECT 1', intView: 'int_Artwork', rowCount: 50,
        columns: [
          { globalName: 'id', sourceName: 'id' } as never,
          { globalName: 'title', sourceName: 'title' } as never,
        ],
      }],
    },
  ],
  joinProperties: [{
    iri: 'http://ex/created', name: 'created',
    leftClass: 'Artist', rightClass: 'Artwork',
    leftCol: 'id', rightCol: 'artist_id',
  }],
  objectProperties: [],
};

const emptyState = (): BuildState => ({
  classes: {}, fields: {}, joins: [], filters: [], orders: [],
  distinct: false, aggregates: {}, having: [],
});

describe('getCatalogData', () => {
  it('derives classes, fields, and keyed edges', () => {
    const { classes, edges, classByName } = getCatalogData(catalog);
    expect(classes.map(c => c.name)).toEqual(['Artist', 'Artwork']);
    expect(classByName['Artist'].fields.map(f => f.name)).toEqual(['id', 'name']);
    expect(edges).toHaveLength(1);
    expect(edges[0]._key).toBe(edgeKey(edges[0], 0));
  });

  it('returns empty shapes for a null or empty catalog', () => {
    expect(getCatalogData(null).classes).toEqual([]);
    expect(getCatalogData({ ...catalog, empty: true }).edges).toEqual([]);
  });
});

describe('buildOql', () => {
  it('returns empty string with no classes selected', () => {
    expect(buildOql(emptyState())).toBe('');
  });

  it('selects Class.* when no fields are picked', () => {
    const oql = buildOql({ ...emptyState(), classes: { Artist: true } });
    expect(oql).toBe('SELECT Artist.*\nFROM Artist\nLIMIT 100');
  });

  it('builds a join chain FROM the anchor class', () => {
    const { edges } = getCatalogData(catalog);
    const oql = buildOql({
      ...emptyState(),
      classes: { Artist: true, Artwork: true },
      fields: { 'Artist.name': true, 'Artwork.title': true },
      joins: edges,
    });
    expect(oql).toContain('FROM Artist -[created]-> Artwork');
    expect(oql).toContain('SELECT Artist.name, Artwork.title');
  });

  it('adds WHERE with AND/OR connectors in order', () => {
    const filters: Predicate[] = [
      { id: '1', cls: 'Artist', field: 'name', type: 'string', op: '=', value: 'Miró' },
      { id: '2', cls: 'Artist', field: 'name', type: 'string', op: '=', value: 'Dalí', or: true },
    ];
    const oql = buildOql({ ...emptyState(), classes: { Artist: true }, filters });
    expect(oql).toContain("WHERE Artist.name = 'Miró'\n  OR Artist.name = 'Dalí'");
  });

  it('emits GROUP BY for non-aggregated fields when aggregates exist, plus HAVING', () => {
    const oql = buildOql({
      ...emptyState(),
      classes: { Artwork: true },
      fields: { 'Artwork.title': true, 'Artwork.id': true },
      aggregates: { 'Artwork.id': 'COUNT' },
      having: [{ id: 'h1', fn: 'COUNT', field: 'Artwork.id', op: '>', value: '3' }],
    });
    expect(oql).toContain('COUNT(Artwork.id) AS count_id');
    expect(oql).toContain('GROUP BY Artwork.title');
    // HAVING names the aggregate by function and argument (the grammar has no alias form there).
    expect(oql).toContain('HAVING COUNT(Artwork.id) > 3');
  });

  // OqlPlanner refuses a star beside an aggregate ("SELECT Artist.* is neither aggregated nor in
  // the GROUP BY list."), and a join or a filter puts a class on the canvas with no picked field.
  // OqlPlannerTest.builderAggregateOverAFilterOnlyJoinedClassPlans plans this exact text.
  it('omits Class.* for a joined, filter-only class when the query aggregates', () => {
    const { edges } = getCatalogData(catalog);
    const state: BuildState = {
      ...emptyState(),
      classes: { Artist: true, Artwork: true },
      fields: { 'Artwork.title': true, 'Artwork.id': true },
      aggregates: { 'Artwork.id': 'COUNT' },
      joins: edges,
      filters: [{ id: 'f1', cls: 'Artist', field: 'name', type: 'string', op: '=', value: 'Miró' }],
    };
    const oql = buildOql(state);
    expect(oql).not.toContain('.*');
    expect(oql).toBe(
      'SELECT Artwork.title, COUNT(Artwork.id) AS count_id\n'
      + 'FROM Artist -[created]-> Artwork\n'
      + "WHERE Artist.name = 'Miró'\n"
      + 'GROUP BY Artwork.title\n'
      + 'LIMIT 100');
    // Without an aggregate the star is legal and still emitted — only the aggregate drops it.
    expect(buildOql({ ...state, aggregates: {} })).toContain('SELECT Artist.*, Artwork.title, Artwork.id');
  });

  it('honors DISTINCT, ORDER BY, and a custom limit', () => {
    const oql = buildOql({
      ...emptyState(),
      classes: { Artist: true },
      distinct: true,
      orders: [{ id: 'o1', cls: 'Artist', field: 'name', dir: 'DESC' }],
    }, 25);
    expect(oql).toContain('SELECT DISTINCT');
    expect(oql).toContain('ORDER BY Artist.name DESC');
    expect(oql).toContain('LIMIT 25');
  });
});

describe('formatPredicate', () => {
  const base = { id: 'x', cls: 'Artist', field: 'name' };

  it('quotes strings, not numbers', () => {
    expect(formatPredicate({ ...base, type: 'string', op: '=', value: 'a' })).toBe("Artist.name = 'a'");
    expect(formatPredicate({ ...base, type: 'decimal', op: '>', value: 5 })).toBe('Artist.name > 5');
  });

  it('renders IS NULL operators without a value', () => {
    expect(formatPredicate({ ...base, type: 'string', op: 'IS NULL', value: null })).toBe('Artist.name IS NULL');
  });

  it('quotes a string-typed range comparison and leaves a numeric one bare', () => {
    expect(formatPredicate({ ...base, type: 'date', op: '>=', value: '2020-01-01' })).toBe("Artist.name >= '2020-01-01'");
    expect(formatPredicate({ ...base, type: 'decimal', op: '<=', value: '9' })).toBe('Artist.name <= 9');
  });
});

describe('fmtHaving', () => {
  it('uses * when no field is set', () => {
    expect(fmtHaving({ id: 'h', fn: 'COUNT', field: null, op: '>=', value: '2' })).toBe('COUNT(*) >= 2');
    expect(fmtHaving({ id: 'h', fn: 'AVG', field: 'Artwork.id', op: '<', value: '7' })).toBe('AVG(Artwork.id) < 7');
  });
});

describe('parseQuery', () => {
  it('finds referenced classes, fields, and relationship hops', () => {
    const res = parseQuery('SELECT Artist.name FROM Artist -[created]-> Artwork', catalog);
    expect(res.classes).toEqual({ Artist: true, Artwork: true });
    expect(res.fields).toEqual({ 'Artist.name': true });
    expect(Object.keys(res.edges)).toHaveLength(1);
  });

  it('returns empty maps for empty input', () => {
    const res = parseQuery('', catalog);
    expect(res.classes).toEqual({});
  });
});

describe('suggest', () => {
  it('suggests fields after Class., with * last — it is the rarest pick, not the default', () => {
    const s = suggest('SELECT Artist.', 14, catalog);
    expect(s.map(x => x.label)).toEqual(['id', 'name', '*']);
  });

  it('drops * once a field name is being typed', () => {
    expect(suggest('SELECT Artist.na', 16, catalog).map(x => x.label)).toEqual(['name']);
  });

  it('suggests relations after -[', () => {
    const s = suggest('FROM Artist -[', 14, catalog);
    expect(s.map(x => x.label)).toEqual(['created']);
  });

  it('suggests keywords and classes by prefix', () => {
    const labels = suggest('SEL', 3, catalog).map(x => x.label);
    expect(labels).toContain('SELECT');
  });
});

describe('opsForType', () => {
  it('falls back to equality ops for unknown types', () => {
    expect(opsForType('geometry')).toEqual(['=', '!=']);
    expect(opsForType('string')).toContain('IS NULL');
  });

  it('offers no operator the OQL parser refuses', () => {
    // OqlParser's comparison operators, plus its two null tests — nothing else parses.
    const parseable = new Set(['=', '!=', '<>', '<', '<=', '>', '>=', 'IS NULL', 'IS NOT NULL']);
    for (const type of [...Object.keys(OPS_BY_TYPE), 'geometry']) {
      for (const op of opsForType(type)) {
        expect(parseable.has(op), `${type} offers ${op}`).toBe(true);
      }
    }
  });
});

// ── J5-F2 / J5-F9: HAVING and ORDER BY offer only what the planner accepts ──────────────────

const aggState = (): BuildState => ({
  ...emptyState(),
  classes: { Artwork: true },
  fields: { 'Artwork.title': true, 'Artwork.id': true },
  aggregates: { 'Artwork.id': 'COUNT' },
})

describe('COUNT(*) AS n and aliased aggregates (J5-F9)', () => {
  it('builds "titles by row count, most first" — walkthrough Q4 was unbuildable', () => {
    // OqlPlannerTest.builderCountAllOrderedByAliasPlans plans this exact text.
    const oql = buildOql({
      ...emptyState(),
      classes: { Artwork: true },
      fields: { 'Artwork.title': true },
      countAll: true,
      orders: [{ id: 'o1', cls: '', field: '', alias: 'n', dir: 'DESC' }],
    })
    expect(oql).toBe(
      'SELECT Artwork.title, COUNT(*) AS n\n'
      + 'FROM Artwork\n'
      + 'GROUP BY Artwork.title\n'
      + 'ORDER BY n DESC\n'
      + 'LIMIT 100')
  })

  it('keeps two aggregates over same-named properties apart', () => {
    const { edges } = getCatalogData(catalog)
    const oql = buildOql({
      ...emptyState(),
      classes: { Artist: true, Artwork: true },
      fields: { 'Artist.id': true, 'Artwork.id': true },
      aggregates: { 'Artist.id': 'COUNT', 'Artwork.id': 'COUNT' },
      joins: edges,
    })
    expect(oql).toContain('COUNT(Artist.id) AS count_id')
    expect(oql).toContain('COUNT(Artwork.id) AS count_id_2')
  })
})

describe('orderOptions — ORDER BY lists SELECT outputs only (J5-F9)', () => {
  it('offers every column of a class selected whole, and nothing from outside the query', () => {
    const opts = orderOptions({ ...emptyState(), classes: { Artist: true } }, getCatalogData(catalog))
    expect(opts.map(o => o.label)).toEqual(['Artist.id', 'Artist.name'])
  })

  it('offers only the picked fields when fields are picked', () => {
    const opts = orderOptions({ ...emptyState(), classes: { Artwork: true }, fields: { 'Artwork.title': true } }, getCatalogData(catalog))
    expect(opts.map(o => o.label)).toEqual(['Artwork.title'])
  })

  it('offers group keys and aggregate aliases once the query aggregates — never an unaggregated field', () => {
    const opts = orderOptions({ ...aggState(), countAll: true }, getCatalogData(catalog))
    expect(opts.map(o => o.label)).toEqual(['Artwork.title', 'count_id', 'n'])
    expect(opts.find(o => o.label === 'n')?.alias).toBe('n')
  })
})

describe('havingOptions — HAVING only with an aggregate (J5-F2)', () => {
  it('offers nothing when the query does not aggregate: the planner has no groups to filter', () => {
    expect(havingOptions({ ...emptyState(), classes: { Artwork: true }, fields: { 'Artwork.title': true } })).toEqual([])
  })

  it("offers exactly the SELECT list's aggregates — COUNT(*) only when it is selected", () => {
    expect(havingOptions(aggState()).map(o => o.label)).toEqual(['COUNT(Artwork.id)'])
    expect(havingOptions({ ...aggState(), countAll: true }).map(o => o.label))
      .toEqual(['COUNT(Artwork.id)', 'COUNT(*)'])
  })
})

describe('pruneBuild — clauses that stopped being valid leave with their cause', () => {
  it('drops HAVING and alias orders once the aggregate they named is removed', () => {
    const pruned = pruneBuild({
      ...aggState(),
      aggregates: {},
      having: [{ id: 'h', fn: 'COUNT', field: 'Artwork.id', op: '>', value: '1' }],
      orders: [{ id: 'o', cls: '', field: '', alias: 'count_id', dir: 'DESC' }],
    }, getCatalogData(catalog))
    expect(pruned.having).toEqual([])
    expect(pruned.orders).toEqual([])
  })

  it('keeps an order on a column that is still an output', () => {
    const state = { ...emptyState(), classes: { Artist: true }, orders: [{ id: 'o', cls: 'Artist', field: 'name', dir: 'ASC' as const }] }
    expect(pruneBuild(state, getCatalogData(catalog)).orders).toHaveLength(1)
  })
})

describe('literals and names are written so they parse (J5-F9 builder honesty)', () => {
  const base = { id: 'x', cls: 'Artist', field: 'name' }

  it("escapes a quote inside a string literal instead of ending it early", () => {
    expect(formatPredicate({ ...base, type: 'string', op: '=', value: "O'Brien" })).toBe("Artist.name = 'O''Brien'")
  })

  it('never invents a 0 for an empty or non-numeric number — the server says what is wrong', () => {
    expect(formatPredicate({ ...base, type: 'decimal', op: '>', value: '' })).toBe("Artist.name > ''")
    expect(formatPredicate({ ...base, type: 'decimal', op: '>', value: '12abc' })).toBe("Artist.name > '12abc'")
    expect(formatPredicate({ ...base, type: 'decimal', op: '>', value: '12.5' })).toBe('Artist.name > 12.5')
  })

  it('writes booleans as keywords', () => {
    expect(formatPredicate({ ...base, type: 'boolean', op: '=', value: 'true' })).toBe('Artist.name = TRUE')
  })

  it('quotes a name that is not a bare identifier', () => {
    expect(ident('title')).toBe('title')
    expect(ident('first-name')).toBe('"first-name"')
    expect(ident('a"b')).toBe('"a""b"')
    expect(formatPredicate({ id: 'x', cls: 'Artist', field: 'first-name', type: 'string', op: 'IS NULL', value: null }))
      .toBe('Artist."first-name" IS NULL')
  })
})

describe('WHERE as OR-groups (J5-F8)', () => {
  const f = (id: string, or = false): Predicate => ({ id, cls: 'Artist', field: 'name', type: 'string', op: '=', value: id, or })

  it('reads the filters the way the parser binds them: AND tighter than OR', () => {
    // venue = VLDB AND year = 2023 OR year = 2024  ≡  (VLDB AND 2023) OR 2024
    expect(filterGroups([f('a'), f('b'), f('c', true)]).map(g => g.map(p => p.id))).toEqual([['a', 'b'], ['c']])
    expect(filterGroups([])).toEqual([])
  })

  it('adds a condition to the end of its own group, keeping every other group intact', () => {
    const next = insertIntoGroup([f('a'), f('b', true), f('c')], 0, f('new'))
    expect(filterGroups(next).map(g => g.map(p => p.id))).toEqual([['a', 'new'], ['b', 'c']])
  })

  it('starts a new OR group past the last one', () => {
    const next = insertIntoGroup([f('a')], 1, f('new'))
    expect(filterGroups(next).map(g => g.map(p => p.id))).toEqual([['a'], ['new']])
  })

  // The group boundary lives in its head's `or` flag, so dropping the head by id alone folded the
  // rest of that group into the previous one: (venue) OR (year AND title), minus year, became
  // venue AND title — fewer rows than either condition the user kept.
  it('removing the head of an OR group keeps the rest of that group OR-ed', () => {
    const groups = [
      { id: 'venue', cls: 'Paper', field: 'venue', type: 'string', op: '=', value: 'VLDB' },
      { id: 'year', cls: 'Paper', field: 'year', type: 'string', op: '=', value: '2023', or: true },
      { id: 'title', cls: 'Paper', field: 'title', type: 'string', op: '=', value: 'X' },
    ] as Predicate[]
    const next = removeFromGroup(groups, 'year')
    expect(filterGroups(next).map(g => g.map(p => p.id))).toEqual([['venue'], ['title']])
    expect(buildOql({ ...emptyState(), classes: { Paper: true }, filters: next }))
      .toContain("WHERE Paper.venue = 'VLDB'\n  OR Paper.title = 'X'")
  })

  it('removing the first condition overall hands the head to the next one in its group', () => {
    const next = removeFromGroup([f('a'), f('b'), f('c', true)], 'a')
    expect(filterGroups(next).map(g => g.map(p => p.id))).toEqual([['b'], ['c']])
    expect(next[0].or).toBe(false)
  })

  it('removing a whole one-condition group leaves the neighbouring groups as they were', () => {
    const next = removeFromGroup([f('a'), f('b', true), f('c', true), f('d')], 'b')
    expect(filterGroups(next).map(g => g.map(p => p.id))).toEqual([['a'], ['c', 'd']])
  })

  it('removing a condition inside a group touches nothing else', () => {
    const next = removeFromGroup([f('a'), f('b'), f('c', true), f('d')], 'd')
    expect(filterGroups(next).map(g => g.map(p => p.id))).toEqual([['a', 'b'], ['c']])
    expect(removeFromGroup([f('a')], 'zz').map(p => p.id)).toEqual(['a'])
  })
})

// ── J5-F3: completion never rewrites ordinary typing ─────────────────────────────────────

/** Type `text` one character at a time, as the textarea's input events would. */
function typeInto(text: string, start: CompletionState = CLOSED_COMPLETION): CompletionState {
  let state = start
  for (let i = 1; i <= text.length; i++) state = completeOnInput(state, text.slice(0, i), i, catalog)
  return state
}

describe('completion (J5-F3)', () => {
  it('stays closed while a query is typed without . or -[, so Enter is a newline', () => {
    // "SELECT Artist.name FROM Artist⏎" used to become "FROM AArtist": the list, computed from
    // the previous render's text, was open and Enter accepted its first item.
    const state = typeInto('SELECT Artist.name FROM Artist')
    expect(state.open).toBe(false)
  })

  it('opens by itself after "Class." and narrows as the property is typed', () => {
    expect(typeInto('SELECT Artist.').items.map(i => i.label)).toEqual(['id', 'name', '*'])
    expect(typeInto('SELECT Artist.na').items.map(i => i.label)).toEqual(['name'])
  })

  it('opens on -[ with the class\'s join properties, not classes', () => {
    const state = typeInto('SELECT Artist.name FROM Artist -[')
    expect(state.open).toBe(true)
    expect(state.items.map(i => i.kind)).toEqual(['relation'])
  })

  it('closes on whitespace rather than offering every class after a finished word', () => {
    expect(typeInto('SELECT Artist.name ').open).toBe(false)
  })

  it('Tab accepts; Enter accepts only once the user moved the highlight, otherwise the newline passes', () => {
    const open = typeInto('SELECT Artist.')
    expect(completionKey(open, 'Tab')).toBe('accept')
    expect(completionKey(open, 'Enter')).toBe('close')
    expect(completionKey({ ...open, moved: true }, 'Enter')).toBe('accept')
    expect(completionKey(open, 'ArrowDown')).toBe('next')
    expect(completionKey(open, 'Escape')).toBe('close')
    expect(completionKey(open, 'a')).toBe('pass')
    expect(completionKey(CLOSED_COMPLETION, 'Enter')).toBe('pass')
  })

  it('opens anywhere on an explicit request (Ctrl+Space)', () => {
    expect(completeOnRequest('SEL', 3, catalog).items.map(i => i.label)).toContain('SELECT')
  })

  it('inserts a name that is not a bare identifier quoted, so it parses', () => {
    const quirky = {
      ...catalog,
      classes: [{ ...catalog.classes[0], sources: [{ ...catalog.classes[0].sources[0],
        columns: [{ globalName: 'first-name', sourceName: 'first-name' } as never] }] }],
    }
    expect(suggest('SELECT Artist.', 14, quirky).find(x => x.label === 'first-name')?.insert).toBe('"first-name"')
  })
})
