import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { PromoteConfirmDialog } from './PromoteConfirmDialog';
import { SchemaDiffList } from './SchemaDiffList';

/**
 * J4-F1 / UI-OD2: a promote replaces the tenant-wide ACTIVE schema, so it is confirmed against a
 * diff and says "tenant-wide". Static markup shows the first paint — before the diff arrives —
 * which is exactly when the confirm button must not be pressable.
 */
describe('PromoteConfirmDialog', () => {
  const render = (error: string | null = null) => renderToStaticMarkup(
    <PromoteConfirmDialog
      open
      draft={{ id: 'd7', versionNumber: 7 }}
      busy={false}
      error={error}
      onConfirm={() => {}}
      onCancel={() => {}}
    />,
  );

  it('names the promote tenant-wide, in its title and on its button', () => {
    const html = render();
    // Named by its visible heading (aria-labelledby), not a duplicate aria-label that could drift.
    expect(html).toMatch(/<h2 id="[^"]+"[^>]*>Promote v7 \(tenant-wide\)<\/h2>/);
    expect(html).toContain('role="alertdialog"');
    expect(html).toMatch(/data-testid="promote-confirm-confirm"[^>]*>Promote v7 \(tenant-wide\)</);
    expect(html).toContain('not only this project');
  });

  it('keeps confirm disabled until the diff against ACTIVE has loaded — Cancel stays usable', () => {
    const html = render();
    expect(html).toMatch(/<button[^>]*data-testid="promote-confirm-confirm"[^>]*\sdisabled=""/);
    expect(html).not.toMatch(/<button[^>]*data-testid="promote-confirm-cancel"[^>]*\sdisabled=""/);
    expect(html).toContain('Comparing with the active schema');
  });

  it('shows the server\'s refusal inside the dialog, never behind it', () => {
    const html = render('Promotion failed: Promoting this draft would remove 2 classes … Author, Paper.');
    expect(html).toContain('data-testid="promote-confirm-error"');
    expect(html).toContain('Author, Paper');
  });
});

describe('SchemaDiffList', () => {
  it('lists removed classes first, with what losing them costs', () => {
    const html = renderToStaticMarkup(
      <SchemaDiffList
        testId="d"
        removedNote="Queries over these stop working."
        diff={{
          addedClasses: ['http://ex/integration/Venue'],
          removedClasses: ['http://ex/integration/Author', 'http://ex/integration/Paper'],
          changedClasses: [{ classIri: 'http://ex/integration/Book', addedProperties: ['http://ex/p/isbn'], removedProperties: [], wrapperChanged: true }],
        }}
      />,
    );
    expect(html.indexOf('2 removed classes')).toBeLessThan(html.indexOf('1 added class'));
    expect(html).toContain('Queries over these stop working.');
    expect(html).toContain('− Author');
    expect(html).toContain('+ Venue');
    expect(html).toContain('~ wrapper SQL changed');
  });

  it('says "no differences" rather than rendering an empty box', () => {
    const html = renderToStaticMarkup(<SchemaDiffList diff={{ addedClasses: [], removedClasses: [], changedClasses: [] }} />);
    expect(html).toContain('No differences');
  });
});
