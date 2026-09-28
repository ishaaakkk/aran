import { describe, it, expect } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { DatasetRenameNote } from './DatasetRenameNote';

/** UI-OD8 (audit J2-F6): the edit dialog says what a rename does before Update is pressed. */
describe('DatasetRenameNote', () => {
  it('explains why a live dataset cannot be renamed', () => {
    const html = renderToStaticMarkup(<DatasetRenameNote impact="blocked" id="n" />);
    expect(html).toContain('data-testid="dataset-rename-blocked"');
    expect(html).toContain('read live');
    expect(html).toContain('cannot be renamed');
  });

  it('warns about the BREAKING re-bootstrap an upload rename leads to, as a status', () => {
    const html = renderToStaticMarkup(<DatasetRenameNote impact="warn" id="n" />);
    expect(html).toContain('data-testid="dataset-rename-warning"');
    expect(html).toContain('role="status"');
    expect(html).toContain('BREAKING');
  });

  it('says nothing for an unchanged name', () => {
    expect(renderToStaticMarkup(<DatasetRenameNote impact="none" id="n" />)).toBe('');
  });
});
