import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { SortHeader } from './adminTable'
import type { SortDirection } from './useAdminTable'

/**
 * Audit J7-F17: every active header announced `aria-sort="ascending"`, including a Joined column
 * sorted newest-first. The header must say the order the rows are actually in.
 *
 * No jsdom in this project; `renderToStaticMarkup` is enough for attribute assertions.
 */

function header(sort: string, direction: SortDirection, column = 'joinedAt') {
  return renderToStaticMarkup(
    <table><thead><tr>
      <SortHeader label="Joined" column={column} state={{ sort, direction, setSort: () => {} }} />
    </tr></thead></table>,
  )
}

describe('SortHeader', () => {
  it('announces descending for a column sorted newest-first', () => {
    expect(header('joinedAt', 'descending')).toContain('aria-sort="descending"')
  })

  it('announces ascending once reversed, and the arrow turns with it', () => {
    const html = header('joinedAt', 'ascending')
    expect(html).toContain('aria-sort="ascending"')
    expect(html).toContain('▴')
  })

  it('says none for a column that is not the sort key', () => {
    expect(header('displayName', 'ascending')).toContain('aria-sort="none"')
  })

  it('tells a keyboard or screen-reader user what the click will do', () => {
    expect(header('joinedAt', 'descending')).toContain('click to sort ascending')
  })
})
