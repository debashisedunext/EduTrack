import * as React from 'react'
import type { Meta, StoryObj } from '@storybook/react-vite'
import { RichTextEditor } from './rich-text-editor'
import { RichTextView } from './rich-text-view'
import { RICH_TEXT_COMPACT_TOOLBAR, richTextToPlainText, sanitizeRichText } from './rich-text'

/**
 * Storybook is the contract for a shared component, and for this one it is also
 * the only place the editing engine can be exercised: `execCommand` does not
 * exist in jsdom, so the unit tests assert which command each button issues and
 * stop there. **Bold actually going bold is verified here, by hand.**
 */
const meta: Meta<typeof RichTextEditor> = {
  title: 'UI/RichTextEditor',
  component: RichTextEditor,
  tags: ['autodocs'],
  parameters: {
    docs: {
      description: {
        component:
          'Shared rich-text editor (C-066). Storage is sanitised HTML over PLAN.md §3.9’s fourteen-tag allow-list — the same control serves the ticket description, Steps to Generate and the comment box.',
      },
    },
  },
}
export default meta

type Story = StoryObj<typeof RichTextEditor>

/** Stories need a real value/onChange pair — the component is not uncontrolled. */
function Stateful({
  initial = '',
  ...props
}: { initial?: string } & Partial<React.ComponentProps<typeof RichTextEditor>>) {
  const [value, setValue] = React.useState(initial)
  return (
    <div className="max-w-2xl">
      <label id="story-label" className="mb-1.5 block text-sm font-medium text-content">
        Task description
      </label>
      <RichTextEditor {...props} aria-labelledby="story-label" value={value} onChange={setValue} />
    </div>
  )
}

export const Default: Story = {
  render: () => (
    <Stateful placeholder="What happened, what was expected, and how to reproduce it." showCount />
  ),
}

export const WithContent: Story = {
  render: () => (
    <Stateful
      showCount
      initial={[
        '<p>Card payments hang at the confirmation step for about 30 seconds, then fail with a generic error.</p>',
        '<h3>Steps to reproduce</h3>',
        '<ol><li>Open <strong>Fees → Collect payment</strong></li><li>Choose any card</li><li>Submit</li></ol>',
        '<blockquote>Reported by the client on 9 Aug, affects three schools.</blockquote>',
        '<p>Gateway response: <code>TIMEOUT_502</code></p>',
      ].join('')}
    />
  ),
}

/**
 * The comment box (C-029) takes a shorter bar. A comment is a paragraph or two;
 * offering headings there invites people to shout, and the control is at its
 * width budget inside the slide-over anyway.
 */
export const CompactToolbar: Story = {
  render: () => <Stateful toolbar={RICH_TEXT_COMPACT_TOOLBAR} placeholder="Add a comment…" rows={3} />,
}

export const Invalid: Story = {
  render: () => <Stateful aria-invalid initial="" placeholder="A description is required." />,
}

export const OverTheLimit: Story = {
  render: () => (
    <Stateful showCount maxLength={80} initial="<p>The counter turns red and announces itself only once the value is actually over the bound — a live region counting every keystroke makes the field unusable with a screen reader.</p>" />
  ),
}

export const Disabled: Story = {
  render: () => <Stateful disabled initial="<p>Read-only, because the current stage is not yours.</p>" />,
}

/**
 * The half that matters. Paste this straight into the editor from the panel on
 * the left — or paste something out of Outlook — and watch what survives.
 *
 * §3.9's allow-list is not advisory decoration: these fields are written by a
 * support desk quoting client email and read by a manager who trusts the page.
 */
export const SanitiserPlayground: Story = {
  render: function Playground() {
    const hostile = [
      '<p style="color:#ff00ff;font-size:40px" class="mso-normal">Styling is dropped.</p>',
      '<script>alert("stored XSS")</script>',
      '<img src="x" onerror="alert(1)">',
      '<a href="javascript:alert(1)">A poisoned link keeps its text and loses its href.</a>',
      '<a href="https://client.example">A real link gains target and rel.</a>',
      '<b>Legacy &lt;b&gt; folds to &lt;strong&gt;</b>, <i>and &lt;i&gt; to &lt;em&gt;.</i>',
      '<h1>An h1 folds to h3 so it cannot outrank the ticket title.</h1>',
      '<table><tr><td>A table is dropped but its words are kept.</td></tr></table>',
      '<iframe src="https://evil.test"></iframe>',
    ].join('\n')

    const [value, setValue] = React.useState(hostile)

    return (
      <div className="grid max-w-6xl gap-4 lg:grid-cols-2">
        <div className="space-y-4">
          <div>
            <h3 className="mb-1.5 text-h3 text-content">Raw input</h3>
            <textarea
              value={value}
              onChange={(event) => setValue(event.target.value)}
              rows={12}
              className="w-full rounded-control border border-border bg-surface p-3 font-mono text-caption text-content"
            />
          </div>
          <div>
            <h3 className="mb-1.5 text-h3 text-content">The editor, on the same value</h3>
            <p className="mb-1.5 text-caption text-content-muted">
              Paste something out of Outlook or a browser here — paste is intercepted and sanitised
              before insertion, which is the only point on the client where hostile markup is still
              preventable.
            </p>
            <RichTextEditor
              aria-label="Sanitiser playground editor"
              value={value}
              onChange={setValue}
              rows={5}
            />
          </div>
        </div>
        <div className="space-y-4">
          <div>
            <h3 className="mb-1.5 text-h3 text-content">Rendered — RichTextView</h3>
            <div className="rounded-control border border-border bg-surface p-3">
              <RichTextView html={value} emptyText="Nothing survived." />
            </div>
          </div>
          <div>
            <h3 className="mb-1.5 text-h3 text-content">What would be stored</h3>
            <pre className="max-h-48 overflow-auto rounded-control bg-subtle p-3 font-mono text-caption text-content">
              {oneTagPerLine(sanitizeRichText(value))}
            </pre>
          </div>
          <div>
            <h3 className="mb-1.5 text-h3 text-content">Plain-text projection</h3>
            <pre className="max-h-32 overflow-auto whitespace-pre-wrap rounded-control bg-subtle p-3 font-mono text-caption text-content">
              {richTextToPlainText(value)}
            </pre>
          </div>
        </div>
      </div>
    )
  },
}

/** One tag per line — the point of the panel is being able to see what was dropped. */
function oneTagPerLine(html: string): string {
  return html.replace(/></g, '>\n<')
}
