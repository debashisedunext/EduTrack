import { readdirSync, readFileSync } from 'node:fs'
import { join, relative, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { RichTextView } from './rich-text-view'

describe('RichTextView', () => {
  it('renders the markup §3.9 allows', () => {
    render(<RichTextView html="<p>Payments <strong>hang</strong> at <code>confirm</code>.</p>" />)

    expect(screen.getByText('hang').tagName).toBe('STRONG')
    expect(screen.getByText('confirm').tagName).toBe('CODE')
  })

  it('sanitises what it was given rather than trusting the write path', () => {
    // §3.9's retroactivity rule. A row written months ago went through whatever
    // the allow-list was then; running the render path through today's list is
    // what makes tightening it apply to data already in the table.
    const { container } = render(
      <RichTextView html='<p>report</p><script>alert(1)</script><img src="x" onerror="alert(2)">' />,
    )

    expect(screen.getByText('report')).toBeInTheDocument()
    expect(container.querySelector('script')).toBeNull()
    expect(container.querySelector('img')).toBeNull()
    expect(container.innerHTML).not.toContain('onerror')
  })

  it('opens outbound links safely', () => {
    render(<RichTextView html='<p><a href="https://client.example">portal</a></p>' />)

    const link = screen.getByRole('link', { name: 'portal' })
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer nofollow')
  })

  it('shows the empty text when there is nothing to render', () => {
    render(<RichTextView html="" emptyText="No description was given." />)

    expect(screen.getByText('No description was given.')).toBeInTheDocument()
  })

  it('renders nothing at all when a value sanitises away and no empty text was given', () => {
    const { container } = render(<RichTextView html="<script>alert(1)</script>" />)

    expect(container).toBeEmptyDOMElement()
  })
})

/**
 * The guarantee behind the component, held mechanically.
 *
 * `RichTextView` is only worth having if it is the *only* way stored HTML
 * reaches the DOM. One `dangerouslySetInnerHTML` added in a hurry somewhere
 * else — a tooltip, an email preview, a chart label — reopens the stored-XSS
 * hole §3.9 exists to close, and no reviewer catches it reliably.
 *
 * If this fails, the fix is to use `RichTextView`, not to add the file to an
 * exclusion list.
 */
describe('the render path is the only one', () => {
  const OWNER = 'src/components/ui/rich-text-view.tsx'

  it('has no dangerouslySetInnerHTML outside RichTextView', () => {
    const root = resolve(process.cwd(), 'src')
    const offenders: string[] = []

    const walk = (dir: string) => {
      for (const entry of readdirSync(dir, { withFileTypes: true })) {
        const path = join(dir, entry.name)
        if (entry.isDirectory()) {
          walk(path)
          continue
        }
        if (!/\.tsx?$/.test(entry.name)) continue

        const rel = relative(process.cwd(), path).replace(/\\/g, '/')
        if (rel === OWNER) continue
        // Comments elsewhere may name it — `rich-text.ts` points readers here.
        // Only a real JSX prop counts.
        if (/dangerouslySetInnerHTML\s*=/.test(readFileSync(path, 'utf8'))) offenders.push(rel)
      }
    }

    walk(root)

    expect(offenders, `render stored HTML through RichTextView (${OWNER}) instead`).toEqual([])
  })
})
