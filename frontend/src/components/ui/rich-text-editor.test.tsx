import * as React from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { RichTextEditor } from './rich-text-editor'
import { RICH_TEXT_COMPACT_TOOLBAR } from './rich-text'

/**
 * jsdom implements neither `execCommand` nor the `queryCommand*` family, and it
 * never will — they are deprecated. So the editing *engine* cannot be asserted
 * here, and pretending otherwise by asserting on innerHTML after a simulated
 * command would test the stub rather than the browser.
 *
 * What is asserted instead is everything that is genuinely ours: which command
 * each button issues, the sanitisation boundary on paste and on `value`, the
 * ARIA contract, and the keyboard model. The engine itself is the browser's,
 * and Storybook is where it gets driven for real.
 */
let exec: ReturnType<typeof vi.fn>
let blockValue = 'p'

beforeEach(() => {
  exec = vi.fn()
  Object.assign(document, {
    execCommand: exec,
    queryCommandState: () => false,
    queryCommandValue: () => blockValue,
  })
})

afterEach(() => {
  blockValue = 'p'
  Reflect.deleteProperty(document, 'execCommand')
  Reflect.deleteProperty(document, 'queryCommandState')
  Reflect.deleteProperty(document, 'queryCommandValue')
})

/** Controlled wrapper — the component's contract is value/onChange, not defaultValue. */
function Harness(props: Partial<React.ComponentProps<typeof RichTextEditor>> = {}) {
  const [value, setValue] = React.useState(props.value ?? '')
  return (
    <RichTextEditor
      aria-label="Task description"
      {...props}
      value={value}
      onChange={(html) => {
        setValue(html)
        props.onChange?.(html)
      }}
    />
  )
}

const editable = () => screen.getByRole('textbox')
const toolbar = () => screen.getByRole('toolbar', { name: 'Formatting' })

/**
 * Select `range` the way a user does — with the `mouseup` that follows.
 *
 * That event is not incidental. Clicking a toolbar button moves focus, and a
 * document selection does not reliably survive the trip, so the component
 * stashes the range on `mouseup`/`keyup` and puts it back before the command
 * runs. Setting the range without the event tests a path no user takes.
 */
function select(box: HTMLElement, range: Range) {
  const selection = window.getSelection()!
  selection.removeAllRanges()
  selection.addRange(range)
  fireEvent.mouseUp(box)
}

describe('accessibility contract', () => {
  it('announces as a multi-line textbox with the label it was given', () => {
    render(<Harness />)

    const box = editable()
    expect(box).toHaveAttribute('aria-multiline', 'true')
    expect(box).toHaveAccessibleName('Task description')
    expect(box).toHaveAttribute('contenteditable', 'true')
  })

  it('forwards the ARIA a FormField hands it', () => {
    render(
      <>
        <span id="lbl">Steps to generate</span>
        <span id="hint">Numbered, one action per line.</span>
        <Harness aria-label={undefined} aria-labelledby="lbl" aria-describedby="hint" aria-invalid />
      </>,
    )

    const box = editable()
    expect(box).toHaveAccessibleName('Steps to generate')
    expect(box).toHaveAccessibleDescription('Numbered, one action per line.')
    expect(box).toHaveAttribute('aria-invalid', 'true')
  })

  it('labels every toolbar button and points the toolbar at the field it controls', () => {
    render(<Harness />)

    expect(toolbar()).toHaveAttribute('aria-controls', editable().id)
    for (const label of ['Bold', 'Italic', 'Underline', 'Strikethrough', 'Bulleted list', 'Link']) {
      expect(within(toolbar()).getByRole('button', { name: label })).toBeInTheDocument()
    }
  })

  it('reports toggle state on the mark buttons and expanded state on Link', () => {
    render(<Harness />)

    // A menu button claiming `aria-pressed` misreports a popover as a toggle,
    // so Link carries `aria-expanded` and the marks carry `aria-pressed`.
    expect(within(toolbar()).getByRole('button', { name: 'Bold' })).toHaveAttribute('aria-pressed', 'false')
    expect(within(toolbar()).getByRole('button', { name: 'Link' })).toHaveAttribute('aria-expanded', 'false')
    expect(within(toolbar()).getByRole('button', { name: 'Link' })).not.toHaveAttribute('aria-pressed')
  })
})

describe('keyboard — the APG toolbar pattern', () => {
  it('is a single tab stop', () => {
    render(<Harness />)

    const buttons = within(toolbar()).getAllByRole('button')
    expect(buttons.filter((b) => b.getAttribute('tabindex') === '0')).toHaveLength(1)
    // Thirteen tab stops would put twelve presses between the label and the
    // text on every rich-text field on the product.
    expect(buttons.length).toBeGreaterThan(10)
  })

  it('moves between buttons with the arrow keys, and wraps', () => {
    render(<Harness />)

    const buttons = within(toolbar()).getAllByRole('button')
    buttons[0].focus()

    fireEvent.keyDown(toolbar(), { key: 'ArrowRight' })
    expect(buttons[1]).toHaveFocus()

    fireEvent.keyDown(toolbar(), { key: 'ArrowLeft' })
    fireEvent.keyDown(toolbar(), { key: 'ArrowLeft' })
    expect(buttons[buttons.length - 1]).toHaveFocus()
  })

  it('jumps to the ends with Home and End', () => {
    render(<Harness />)

    const buttons = within(toolbar()).getAllByRole('button')
    buttons[0].focus()

    fireEvent.keyDown(toolbar(), { key: 'End' })
    expect(buttons[buttons.length - 1]).toHaveFocus()

    fireEvent.keyDown(toolbar(), { key: 'Home' })
    expect(buttons[0]).toHaveFocus()
  })
})

describe('toolbar commands', () => {
  it.each([
    ['Bold', 'bold', undefined],
    ['Italic', 'italic', undefined],
    ['Underline', 'underline', undefined],
    ['Strikethrough', 'strikeThrough', undefined],
    ['Bulleted list', 'insertUnorderedList', undefined],
    ['Numbered list', 'insertOrderedList', undefined],
    ['Heading', 'formatBlock', '<h3>'],
    ['Subheading', 'formatBlock', '<h4>'],
    ['Quote', 'formatBlock', '<blockquote>'],
    ['Code block', 'formatBlock', '<pre>'],
  ])('%s issues %s', (label, command, argument) => {
    render(<Harness />)

    fireEvent.click(within(toolbar()).getByRole('button', { name: label }))

    expect(exec).toHaveBeenCalledWith(command, false, argument)
  })

  it('toggles a block back to a paragraph when it is already applied', () => {
    // `formatBlock` has no toggle of its own, so without this pressing Quote
    // twice leaves no way out but Clear formatting.
    blockValue = 'blockquote'
    render(<Harness />)

    fireEvent.click(within(toolbar()).getByRole('button', { name: 'Quote' }))

    expect(exec).toHaveBeenCalledWith('formatBlock', false, '<p>')
  })

  it('does not move focus off the editor when a button is clicked', () => {
    // The classic contentEditable toolbar bug: mousedown blurs the field, the
    // selection collapses, and every command silently does nothing.
    render(<Harness />)

    const bold = within(toolbar()).getByRole('button', { name: 'Bold' })
    const prevented = !fireEvent.mouseDown(bold)

    expect(prevented).toBe(true)
  })

  it('wraps the selection for inline code, which no browser implements natively', () => {
    render(<Harness value="<p>run npm test now</p>" />)

    const box = editable()
    const target = box.querySelector('p')!
    const range = document.createRange()
    range.setStart(target.firstChild!, 4)
    range.setEnd(target.firstChild!, 12)
    select(box, range)

    fireEvent.click(within(toolbar()).getByRole('button', { name: 'Inline code' }))

    expect(exec).toHaveBeenCalledWith('insertHTML', false, '<code>npm test</code>')
  })

  it('escapes the selection before wrapping it, so code cannot smuggle markup', () => {
    // Inline code is the one command that re-inserts the user's own text as
    // HTML, so it is the one place a `<` typed by hand could become an element.
    // Braces, not a quoted attribute: JSX decodes entities in string literal
    // attributes, so `value="…&lt;…"` would hand the component a real `<` and
    // this would quietly become a test about tag parsing instead.
    render(<Harness value={'<p>if (a &lt; b &amp;&amp; c &gt; d)</p>'} />)

    const box = editable()
    const text = box.querySelector('p')!.firstChild!
    const range = document.createRange()
    range.setStart(text, 0)
    range.setEnd(text, text.textContent!.length)
    select(box, range)

    fireEvent.click(within(toolbar()).getByRole('button', { name: 'Inline code' }))

    expect(exec).toHaveBeenCalledWith(
      'insertHTML',
      false,
      '<code>if (a &lt; b &amp;&amp; c &gt; d)</code>',
    )
  })

  it('takes a shorter bar when one is given', () => {
    render(<Harness toolbar={RICH_TEXT_COMPACT_TOOLBAR} />)

    expect(within(toolbar()).queryByRole('button', { name: 'Heading' })).not.toBeInTheDocument()
    expect(within(toolbar()).getByRole('button', { name: 'Bold' })).toBeInTheDocument()
  })
})

describe('value', () => {
  it('sanitises incoming HTML before it reaches the DOM', () => {
    // A value arriving from the API is not trusted either — §3.9's render rule
    // is what makes tightening the allow-list retroactive.
    render(<Harness value='<p>report</p><img src="x" onerror="alert(1)"><script>alert(2)</script>' />)

    const box = editable()
    expect(box.innerHTML).toContain('report')
    expect(box.innerHTML).not.toContain('onerror')
    expect(box.innerHTML).not.toContain('script')
  })

  it('emits sanitised HTML, folding what execCommand actually produces', () => {
    const onChange = vi.fn()
    render(<Harness onChange={onChange} />)

    const box = editable()
    // What Chrome leaves behind after execCommand('bold').
    box.innerHTML = '<b>bolded</b>'
    fireEvent.input(box)

    expect(onChange).toHaveBeenCalledWith('<strong>bolded</strong>')
  })

  it('does not rewrite the DOM when the parent echoes back what was just emitted', () => {
    // Writing `value` back on every keystroke resets the caret to position
    // zero. The Harness is a genuine controlled parent, so this is the real
    // round trip and not a simulation of one.
    const onChange = vi.fn()
    render(<Harness onChange={onChange} />)

    const box = editable()
    box.innerHTML = '<p>typed</p>'
    fireEvent.input(box)

    expect(onChange).toHaveBeenCalledWith('<p>typed</p>')
    expect(box.innerHTML).toBe('<p>typed</p>')
  })

  it('shows a placeholder while empty and hides it once there is content', () => {
    const { rerender } = render(<RichTextEditor aria-label="d" value="" onChange={() => {}} placeholder="Write here…" />)
    expect(screen.getByText('Write here…')).toBeInTheDocument()

    rerender(<RichTextEditor aria-label="d" value="<p>x</p>" onChange={() => {}} placeholder="Write here…" />)
    expect(screen.queryByText('Write here…')).not.toBeInTheDocument()
  })

  it('still counts an emptied editor as empty', () => {
    // `<p><br></p>` is 13 characters of nothing and is what the browser leaves
    // behind — a placeholder keyed on string length would never come back.
    render(<RichTextEditor aria-label="d" value="<p><br></p>" onChange={() => {}} placeholder="Write here…" />)

    expect(screen.getByText('Write here…')).toBeInTheDocument()
  })
})

describe('paste — the main way hostile markup arrives', () => {
  function paste(box: HTMLElement, data: { html?: string; text?: string; files?: File[] }) {
    fireEvent.paste(box, {
      clipboardData: {
        files: data.files ?? [],
        getData: (type: string) => (type === 'text/html' ? (data.html ?? '') : (data.text ?? '')),
      },
    })
  }

  it('sanitises pasted HTML before it is inserted, not after', () => {
    // By the time hostile markup is in the DOM its styles have applied and its
    // handlers are bound; intercepting the paste is the only point at which
    // this is preventable on the client.
    render(<Harness />)

    paste(editable(), {
      html: '<p style="color:red">Client wrote</p><script>alert(1)</script>',
    })

    expect(exec).toHaveBeenCalledWith('insertHTML', false, '<p>Client wrote</p>')
  })

  it('keeps line structure when only plain text is on the clipboard', () => {
    render(<Harness />)

    paste(editable(), { text: 'Step one\nStep two\n\nThen it fails' })

    expect(exec).toHaveBeenCalledWith(
      'insertHTML',
      false,
      '<p>Step one<br>Step two</p><p>Then it fails</p>',
    )
  })

  it('escapes plain text rather than parsing it', () => {
    render(<Harness />)

    paste(editable(), { text: '<script>alert(1)</script>' })

    expect(exec).toHaveBeenCalledWith('insertHTML', false, '<p>&lt;script&gt;alert(1)&lt;/script&gt;</p>')
  })

  it('hands a pasted screenshot to the upload surface', () => {
    const onPasteFiles = vi.fn()
    render(<Harness onPasteFiles={onPasteFiles} />)

    const file = new File(['x'], 'snip.png', { type: 'image/png' })
    paste(editable(), { files: [file] })

    expect(onPasteFiles).toHaveBeenCalledWith([file])
    expect(exec).not.toHaveBeenCalled()
  })

  it('drops a pasted image when nothing is wired to receive it', () => {
    // Letting the browser insert its `blob:` URL would look like it worked and
    // then render a broken image after the next reload. Inline upload is C-023.
    render(<Harness />)

    paste(editable(), { files: [new File(['x'], 'snip.png', { type: 'image/png' })] })

    expect(exec).not.toHaveBeenCalled()
  })
})

describe('length', () => {
  it('counts the sanitised HTML, which is what the column stores', () => {
    render(<Harness value="<p>hello</p>" showCount maxLength={20000} />)

    expect(screen.getByText('12 / 20,000')).toBeInTheDocument()
  })

  it('announces once the value is over the bound', () => {
    render(<Harness value="<p>far too long</p>" showCount maxLength={10} />)

    const count = screen.getByRole('status')
    expect(count).toHaveTextContent('too long')
    // Not a live region below the bound: announcing every keystroke makes the
    // field unusable with a screen reader.
    expect(count).toHaveClass('text-danger-text')
  })

  it('has no counter unless one is asked for', () => {
    render(<Harness value="<p>hello</p>" />)

    expect(screen.queryByText(/\/ 20,000/)).not.toBeInTheDocument()
  })
})

describe('disabled', () => {
  it('stops editing and every command', () => {
    render(<Harness value="<p>read only</p>" disabled />)

    expect(editable()).toHaveAttribute('contenteditable', 'false')
    for (const button of within(toolbar()).getAllByRole('button')) {
      expect(button).toBeDisabled()
    }

    fireEvent.click(within(toolbar()).getByRole('button', { name: 'Bold' }))
    expect(exec).not.toHaveBeenCalled()
  })
})

describe('link', () => {
  it('refuses a URL the sanitiser would strip anyway', async () => {
    // Better to say so in the form than to accept it, drop it silently, and
    // leave the user looking at unlinked text.
    const user = userEvent.setup()
    render(<Harness />)

    await user.click(within(toolbar()).getByRole('button', { name: 'Link' }))

    const field = await screen.findByLabelText('Link address')
    await user.type(field, 'javascript:alert(1)')
    expect(screen.getByRole('button', { name: 'Add link' })).toBeDisabled()

    await user.clear(field)
    await user.type(field, 'https://client.example/portal')
    expect(screen.getByRole('button', { name: 'Add link' })).toBeEnabled()
  })

  it('inserts the URL as its own label when nothing is selected', async () => {
    const user = userEvent.setup()
    render(<Harness />)

    await user.click(within(toolbar()).getByRole('button', { name: 'Link' }))
    await user.type(await screen.findByLabelText('Link address'), 'https://client.example')
    await user.click(screen.getByRole('button', { name: 'Add link' }))

    // `createLink` is a no-op on a collapsed range in every browser, so this is
    // the only way the command can work at all with no selection.
    expect(exec).toHaveBeenCalledWith(
      'insertHTML',
      false,
      '<a href="https://client.example">https://client.example</a>',
    )
  })
})
