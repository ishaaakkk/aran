import { describe, expect, it } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { ShareLinkFallback } from './ShareLinkFallback'

/** J5-F17: when Share cannot copy, the link is handed over in a selectable, labelled field. */
describe('ShareLinkFallback (J5-F17)', () => {
  it('says the copy failed and offers the link in a labelled read-only field', () => {
    const html = renderToStaticMarkup(<ShareLinkFallback url="http://lan-box/oql?q=abc" onDismiss={() => {}} />)
    expect(html).toContain('Couldn’t copy')
    expect(html).toContain('aria-label="Share link"')
    expect(html).toContain('value="http://lan-box/oql?q=abc"')
    expect(html).toContain('readOnly=""')
    expect(html).not.toContain('copied')
  })
})
