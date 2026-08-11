import * as React from 'react'
import * as PopoverPrimitive from '@radix-ui/react-popover'
import {
  Bold,
  Code,
  Heading3,
  Heading4,
  Italic,
  Link2,
  List,
  ListOrdered,
  Quote,
  RemoveFormatting,
  SquareCode,
  Strikethrough,
  Underline,
} from 'lucide-react'
import { cn } from '@/lib/utils'
import { Button } from './button'
import { Input } from './input'
import {
  RICH_TEXT_DEFAULT_TOOLBAR,
  RICH_TEXT_MAX_LENGTH,
  type RichTextCommand,
  escapeHtml,
  isRichTextEmpty,
  plainTextToRichText,
  richTextProseClasses,
  sanitizeRichText,
} from './rich-text'

/**
 * The shared rich-text editor — C-066, the first one in the codebase.
 *
 * Three surfaces need it and it is built once here: the ticket's Task
 * Description and Steps to Generate (blueprint §7.5) and the comment box
 * (§4B.5). It lives in `components/ui/` because that is the library all four
 * streams consume; changes here are **additive only**.
 *
 * ## Why contentEditable and `execCommand` rather than TipTap or Lexical
 *
 * PLAN.md §3.9 fixes storage as **HTML over a fourteen-tag allow-list**. Both
 * of those editors are document-model editors: they hold a schema, serialise to
 * HTML at the edges, and their value comes from adopting ProseMirror or
 * Lexical's model as a second source of truth beside the contract. For fourteen
 * tags that is a large dependency bought for the part we already have.
 *
 * `execCommand` is deprecated-but-universal, and every browser's implementation
 * emits close to exactly §3.9's vocabulary. What it emits *wrongly* — `<b>`,
 * `<i>`, `<strike>` — is folded back by `sanitizeRichText`'s alias map, which
 * had to exist regardless for content pasted out of Word. So the sanitiser is
 * the real deliverable and the editor is a toolbar on top of it.
 *
 * The precedent is C-019's tab strip: hand-rolled to the APG pattern rather
 * than adding a dependency for one screen.
 *
 * ## Contract
 *
 * `value`/`onChange` speak **sanitised HTML**, never a document model. The
 * component is deliberately *not* fully controlled — writing `value` back into
 * the DOM on every keystroke resets the caret to position zero, which is the
 * single most common contentEditable bug. Incoming `value` is only written when
 * it differs from what this component last emitted, so a parent that echoes
 * `onChange` straight back is a no-op.
 *
 * Works with react-hook-form through `Controller` — `register()` cannot bind a
 * contentEditable, since there is no `input` event carrying a `value`.
 */

interface CommandSpec {
  label: string
  icon: React.ComponentType<{ className?: string }>
  /** Shown after the label in the tooltip; the browser binds these natively. */
  shortcut?: string
}

const COMMANDS: Record<RichTextCommand, CommandSpec> = {
  bold: { label: 'Bold', icon: Bold, shortcut: 'Ctrl+B' },
  italic: { label: 'Italic', icon: Italic, shortcut: 'Ctrl+I' },
  underline: { label: 'Underline', icon: Underline, shortcut: 'Ctrl+U' },
  strikethrough: { label: 'Strikethrough', icon: Strikethrough },
  heading3: { label: 'Heading', icon: Heading3 },
  heading4: { label: 'Subheading', icon: Heading4 },
  bulletList: { label: 'Bulleted list', icon: List },
  numberedList: { label: 'Numbered list', icon: ListOrdered },
  blockquote: { label: 'Quote', icon: Quote },
  inlineCode: { label: 'Inline code', icon: Code },
  codeBlock: { label: 'Code block', icon: SquareCode },
  link: { label: 'Link', icon: Link2 },
  clearFormatting: { label: 'Clear formatting', icon: RemoveFormatting },
}

export interface RichTextEditorProps {
  /** Sanitised HTML. */
  value: string
  /** Receives sanitised HTML — never the raw contentEditable innerHTML. */
  onChange: (html: string) => void
  onBlur?: () => void
  placeholder?: string
  disabled?: boolean
  className?: string
  /** Minimum height of the editable area, in rows of text. */
  rows?: number
  /**
   * Character bound over the sanitised HTML, matching what the column stores
   * and Bean Validation rejects. Ticket callers should pass the generated
   * contract constant (`createTicketBodyDescriptionMax`) so a contract change
   * moves the bound without an edit here.
   */
  maxLength?: number
  /** Hidden by default; worth showing on the long-form fields. */
  showCount?: boolean
  toolbar?: RichTextCommand[][]
  /**
   * Pasted or dropped image files.
   *
   * Without a handler an image paste is **deliberately dropped**. Letting the
   * browser insert its `blob:` URL would look like it worked and then render a
   * broken image after the next reload, which is worse than nothing. Inline
   * upload is C-023's surface; this prop is where it plugs in.
   */
  onPasteFiles?: (files: File[]) => void
  id?: string
  'aria-labelledby'?: string
  'aria-describedby'?: string
  'aria-invalid'?: boolean
  /** Used when there is no visible label to point `aria-labelledby` at. */
  'aria-label'?: string
}

export function RichTextEditor({
  value,
  onChange,
  onBlur,
  placeholder = 'Write here…',
  disabled = false,
  className,
  rows = 6,
  maxLength = RICH_TEXT_MAX_LENGTH,
  showCount = false,
  toolbar = RICH_TEXT_DEFAULT_TOOLBAR,
  onPasteFiles,
  id,
  'aria-labelledby': ariaLabelledBy,
  'aria-describedby': ariaDescribedBy,
  'aria-invalid': ariaInvalid,
  'aria-label': ariaLabel,
}: RichTextEditorProps) {
  const editorRef = React.useRef<HTMLDivElement>(null)
  /** What we last handed to `onChange`, so the parent's echo doesn't reset the caret. */
  const lastValueRef = React.useRef<string | null>(null)
  /** The caret, remembered across a trip to the toolbar (see `runCommand`). */
  const savedRangeRef = React.useRef<Range | null>(null)

  const [active, setActive] = React.useState<Set<RichTextCommand>>(() => new Set())
  const [empty, setEmpty] = React.useState(() => isRichTextEmpty(value))
  const [length, setLength] = React.useState(() => value.length)
  const [linkOpen, setLinkOpen] = React.useState(false)

  const generatedId = React.useId()
  const editorId = id ?? `${generatedId}-editor`
  const countId = `${editorId}-count`

  // Write incoming `value` into the DOM only when it is genuinely new. Writing
  // unconditionally is what collapses the caret to position zero on every
  // keystroke — the bug this whole ref dance exists to avoid.
  React.useEffect(() => {
    const el = editorRef.current
    if (!el) return
    if (value === lastValueRef.current) return

    const incoming = sanitizeRichText(value)
    if (el.innerHTML !== incoming) el.innerHTML = incoming
    lastValueRef.current = value
    setEmpty(isRichTextEmpty(incoming))
    setLength(incoming.length)
  }, [value])

  const rememberSelection = React.useCallback(() => {
    const el = editorRef.current
    const selection = window.getSelection()
    if (!el || !selection || selection.rangeCount === 0) return
    if (!el.contains(selection.anchorNode)) return
    savedRangeRef.current = selection.getRangeAt(0).cloneRange()
  }, [])

  const refreshActive = React.useCallback(() => {
    setActive(readActiveCommands())
  }, [])

  const emit = React.useCallback(() => {
    const el = editorRef.current
    if (!el) return
    const html = sanitizeRichText(el.innerHTML)
    lastValueRef.current = html
    setEmpty(isRichTextEmpty(html))
    setLength(html.length)
    onChange(html)
  }, [onChange])

  /**
   * Toolbar buttons `preventDefault` on mousedown, so a click never moves focus
   * off the editor and the selection survives. The keyboard path has no such
   * luck — focus really is on the button — so the range is restored explicitly
   * before the command runs. Without both halves the toolbar silently does
   * nothing to a selection, which reads as a broken button.
   */
  const runCommand = React.useCallback(
    (command: RichTextCommand) => {
      if (disabled) return
      const el = editorRef.current
      if (!el) return

      if (command === 'link') {
        // Radix owns the popover's open state. Setting it here as well would
        // fight the trigger's own toggle and leave the button unable to close
        // what it just opened.
        rememberSelection()
        return
      }

      el.focus()
      restoreRange(savedRangeRef.current)
      applyCommand(command)
      rememberSelection()
      refreshActive()
      emit()
    },
    [disabled, emit, refreshActive, rememberSelection],
  )

  const applyLink = React.useCallback(
    (href: string) => {
      const el = editorRef.current
      if (!el) return
      el.focus()
      restoreRange(savedRangeRef.current)

      const selection = window.getSelection()
      const collapsed = !selection || selection.isCollapsed
      if (collapsed) {
        // Nothing selected: the URL becomes its own label. `createLink` on a
        // collapsed range is a no-op in every browser, so inserting the anchor
        // is the only way this can work at all.
        exec('insertHTML', `<a href="${escapeHtml(href)}">${escapeHtml(href)}</a>`)
      } else {
        exec('createLink', href)
      }

      setLinkOpen(false)
      rememberSelection()
      emit()
    },
    [emit, rememberSelection],
  )

  const handlePaste = React.useCallback(
    (event: React.ClipboardEvent<HTMLDivElement>) => {
      const files = Array.from(event.clipboardData.files).filter((f) => f.type.startsWith('image/'))
      if (files.length > 0) {
        // Always swallow the default. See `onPasteFiles` — a `blob:` URL that
        // dies on reload is a worse outcome than an explicit no-op.
        event.preventDefault()
        onPasteFiles?.(files)
        return
      }

      event.preventDefault()
      const html = event.clipboardData.getData('text/html')
      const text = event.clipboardData.getData('text/plain')
      // Sanitising the paste is the point of intercepting it: pasted client
      // email is the most common way hostile or merely enormous markup gets
      // into these fields, and by the time it is in the DOM the styles have
      // already applied.
      const payload = html ? sanitizeRichText(html) : plainTextToRichText(text)
      if (!payload) return

      exec('insertHTML', payload)
      emit()
    },
    [emit, onPasteFiles],
  )

  const over = length > maxLength

  return (
    <div
      className={cn(
        'rounded-control border border-border bg-surface',
        'focus-within:ring-2 focus-within:ring-primary focus-within:ring-offset-1',
        ariaInvalid && 'border-danger focus-within:ring-danger',
        disabled && 'opacity-50',
        className,
      )}
    >
      <Toolbar
        groups={toolbar}
        active={active}
        disabled={disabled}
        controls={editorId}
        linkOpen={linkOpen}
        onLinkOpenChange={setLinkOpen}
        onCommand={runCommand}
        onSubmitLink={applyLink}
      />

      <div className="relative">
        {empty && (
          // A contentEditable is never truly `:empty` — the browser leaves
          // `<p><br></p>` behind — so the CSS-only placeholder trick does not
          // work here. `aria-hidden` because the accessible name already comes
          // from the label.
          <p
            aria-hidden
            className="pointer-events-none absolute left-3 top-2 select-none text-sm text-content-muted"
          >
            {placeholder}
          </p>
        )}
        <div
          ref={editorRef}
          id={editorId}
          // `textbox` + `aria-multiline` is what makes a contentEditable
          // announce as a multi-line field rather than as a group of prose.
          role="textbox"
          aria-multiline="true"
          contentEditable={!disabled}
          suppressContentEditableWarning
          spellCheck
          tabIndex={disabled ? -1 : 0}
          aria-label={ariaLabel}
          aria-labelledby={ariaLabelledBy}
          aria-describedby={cn(ariaDescribedBy, showCount && countId) || undefined}
          aria-invalid={ariaInvalid}
          aria-disabled={disabled || undefined}
          onInput={emit}
          onPaste={handlePaste}
          onBlur={() => {
            rememberSelection()
            onBlur?.()
          }}
          onFocus={refreshActive}
          onKeyUp={() => {
            rememberSelection()
            refreshActive()
          }}
          onMouseUp={() => {
            rememberSelection()
            refreshActive()
          }}
          className={cn(
            richTextProseClasses,
            'w-full overflow-y-auto px-3 py-2 outline-none',
            // `max-h` keeps a 400-line pasted stack trace from pushing the Save
            // button off the screen.
            'max-h-[32rem]',
          )}
          style={{ minHeight: `${rows * 1.25 + 1}rem` }}
        />
      </div>

      {showCount && (
        <div className="flex justify-end border-t border-border px-3 py-1">
          <span
            id={countId}
            // Only announced once it matters. A live region counting every
            // keystroke makes the field unusable with a screen reader.
            role={over ? 'status' : undefined}
            className={cn('text-caption tabular-nums', over ? 'text-danger-text' : 'text-content-muted')}
          >
            {length.toLocaleString()} / {maxLength.toLocaleString()}
            {over && ' — too long'}
          </span>
        </div>
      )}
    </div>
  )
}

// ---------------------------------------------------------------- the toolbar

interface ToolbarProps {
  groups: RichTextCommand[][]
  active: Set<RichTextCommand>
  disabled: boolean
  controls: string
  linkOpen: boolean
  onLinkOpenChange: (open: boolean) => void
  onCommand: (command: RichTextCommand) => void
  onSubmitLink: (href: string) => void
}

/**
 * APG toolbar pattern: **one tab stop for the whole bar**, arrows to move
 * within it.
 *
 * Thirteen buttons each taking a tab stop would put twelve presses between the
 * label and the text for a keyboard user on every rich-text field on the
 * product. Roving tabindex is what the pattern is for.
 */
function Toolbar({
  groups,
  active,
  disabled,
  controls,
  linkOpen,
  onLinkOpenChange,
  onCommand,
  onSubmitLink,
}: ToolbarProps) {
  const flat = React.useMemo(() => groups.flat(), [groups])
  const [focused, setFocused] = React.useState(0)
  const buttonsRef = React.useRef<Map<RichTextCommand, HTMLButtonElement>>(new Map())

  const move = (next: number) => {
    const clamped = (next + flat.length) % flat.length
    setFocused(clamped)
    buttonsRef.current.get(flat[clamped])?.focus()
  }

  const onKeyDown = (event: React.KeyboardEvent) => {
    if (event.key === 'ArrowRight') {
      event.preventDefault()
      move(focused + 1)
    } else if (event.key === 'ArrowLeft') {
      event.preventDefault()
      move(focused - 1)
    } else if (event.key === 'Home') {
      event.preventDefault()
      move(0)
    } else if (event.key === 'End') {
      event.preventDefault()
      move(flat.length - 1)
    }
  }

  return (
    <div
      role="toolbar"
      aria-label="Formatting"
      aria-controls={controls}
      aria-orientation="horizontal"
      onKeyDown={onKeyDown}
      className="flex flex-wrap items-center gap-0.5 border-b border-border px-1.5 py-1"
    >
      {groups.map((group, groupIndex) => (
        <React.Fragment key={group.join('-')}>
          {groupIndex > 0 && <span aria-hidden className="mx-1 h-5 w-px shrink-0 bg-border" />}
          {group.map((command) => {
            const spec = COMMANDS[command]
            const Icon = spec.icon
            const isPressed = active.has(command)
            const title = spec.shortcut ? `${spec.label} (${spec.shortcut})` : spec.label

            const button = (
              <button
                key={command}
                type="button"
                ref={(node) => {
                  if (node) buttonsRef.current.set(command, node)
                  else buttonsRef.current.delete(command)
                }}
                disabled={disabled}
                tabIndex={flat[focused] === command ? 0 : -1}
                aria-label={spec.label}
                // `link` opens a popover rather than toggling a mark, so it
                // carries `aria-expanded` instead — claiming `aria-pressed` for
                // it would misreport a menu as a toggle.
                {...(command === 'link'
                  ? { 'aria-expanded': linkOpen, 'aria-haspopup': 'dialog' as const }
                  : { 'aria-pressed': isPressed })}
                title={title}
                // Keeps focus — and therefore the selection — in the editor.
                // Without this the command runs against a collapsed range and
                // appears to do nothing.
                onMouseDown={(event) => event.preventDefault()}
                onFocus={() => setFocused(flat.indexOf(command))}
                onClick={() => onCommand(command)}
                className={cn(
                  'inline-flex h-8 w-8 items-center justify-center rounded-control text-content-muted transition-colors',
                  'hover:bg-subtle hover:text-content',
                  'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary',
                  'disabled:pointer-events-none disabled:opacity-50',
                  isPressed && 'bg-primary-soft text-primary',
                )}
              >
                <Icon className="h-4 w-4" />
              </button>
            )

            if (command !== 'link') return button

            return (
              <PopoverPrimitive.Root key={command} open={linkOpen} onOpenChange={onLinkOpenChange}>
                <PopoverPrimitive.Trigger asChild>{button}</PopoverPrimitive.Trigger>
                <PopoverPrimitive.Portal>
                  <PopoverPrimitive.Content
                    align="start"
                    sideOffset={6}
                    role="dialog"
                    aria-label="Insert link"
                    className="z-50 w-80 rounded-control border border-border bg-surface p-3 shadow-modal"
                  >
                    <LinkForm onSubmit={onSubmitLink} onCancel={() => onLinkOpenChange(false)} />
                  </PopoverPrimitive.Content>
                </PopoverPrimitive.Portal>
              </PopoverPrimitive.Root>
            )
          })}
        </React.Fragment>
      ))}
    </div>
  )
}

function LinkForm({ onSubmit, onCancel }: { onSubmit: (href: string) => void; onCancel: () => void }) {
  const [href, setHref] = React.useState('')
  const id = React.useId()
  // §3.9 allows `http` and `https` in an `href` and nothing else, so a bad URL
  // is caught here rather than being silently stripped by the sanitiser after
  // the user thinks they have inserted it.
  const valid = /^https?:\/\/\S+$/i.test(href.trim())

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault()
        if (valid) onSubmit(href.trim())
      }}
      className="flex flex-col gap-2"
    >
      <label htmlFor={id} className="text-caption font-medium text-content">
        Link address
      </label>
      <Input
        id={id}
        autoFocus
        value={href}
        placeholder="https://"
        aria-describedby={`${id}-hint`}
        onChange={(event) => setHref(event.target.value)}
      />
      <p id={`${id}-hint`} className="text-caption text-content-muted">
        Must start with http:// or https://
      </p>
      <div className="flex justify-end gap-2">
        <Button type="button" variant="ghost" size="sm" onClick={onCancel}>
          Cancel
        </Button>
        <Button type="submit" size="sm" disabled={!valid}>
          Add link
        </Button>
      </div>
    </form>
  )
}

// ------------------------------------------------------------ execCommand glue

/**
 * `execCommand` is deprecated and unimplemented in jsdom, so every call goes
 * through here: a missing implementation is a no-op rather than a `TypeError`
 * that fails an unrelated assertion three frames later.
 */
function exec(command: string, value?: string): void {
  if (typeof document.execCommand !== 'function') return
  document.execCommand(command, false, value)
}

function queryState(command: string): boolean {
  if (typeof document.queryCommandState !== 'function') return false
  try {
    return document.queryCommandState(command)
  } catch {
    return false
  }
}

function queryBlock(): string {
  if (typeof document.queryCommandValue !== 'function') return ''
  try {
    return String(document.queryCommandValue('formatBlock')).toLowerCase()
  } catch {
    return ''
  }
}

function readActiveCommands(): Set<RichTextCommand> {
  const block = queryBlock()
  const state = new Set<RichTextCommand>()

  if (queryState('bold')) state.add('bold')
  if (queryState('italic')) state.add('italic')
  if (queryState('underline')) state.add('underline')
  if (queryState('strikeThrough')) state.add('strikethrough')
  if (queryState('insertUnorderedList')) state.add('bulletList')
  if (queryState('insertOrderedList')) state.add('numberedList')
  if (block === 'h3') state.add('heading3')
  if (block === 'h4') state.add('heading4')
  if (block === 'blockquote') state.add('blockquote')
  if (block === 'pre') state.add('codeBlock')

  return state
}

function applyCommand(command: RichTextCommand): void {
  const block = queryBlock()

  switch (command) {
    case 'bold':
    case 'italic':
    case 'underline':
      return exec(command)
    case 'strikethrough':
      return exec('strikeThrough')
    case 'bulletList':
      return exec('insertUnorderedList')
    case 'numberedList':
      return exec('insertOrderedList')
    // Every block command toggles back to a paragraph when it is already
    // applied. `formatBlock` has no toggle of its own, so pressing Quote twice
    // would otherwise leave the user with no way out but Clear formatting.
    case 'heading3':
      return exec('formatBlock', block === 'h3' ? '<p>' : '<h3>')
    case 'heading4':
      return exec('formatBlock', block === 'h4' ? '<p>' : '<h4>')
    case 'blockquote':
      return exec('formatBlock', block === 'blockquote' ? '<p>' : '<blockquote>')
    case 'codeBlock':
      return exec('formatBlock', block === 'pre' ? '<p>' : '<pre>')
    case 'inlineCode':
      return applyInlineCode()
    case 'clearFormatting':
      exec('removeFormat')
      return exec('formatBlock', '<p>')
    case 'link':
      // Handled by the popover in `runCommand`; unreachable here.
      return
  }
}

/**
 * `execCommand` has no inline-code command in any browser, so the selection is
 * re-inserted wrapped.
 *
 * Nested marks inside the selection are lost, which is the right trade: inline
 * code is a literal, and bold-inside-code is not a thing anyone means.
 */
function applyInlineCode(): void {
  const selection = window.getSelection()
  if (!selection || selection.rangeCount === 0) return
  const text = selection.toString()
  if (!text) return
  exec('insertHTML', `<code>${escapeHtml(text)}</code>`)
}

function restoreRange(range: Range | null): void {
  if (!range) return
  const selection = window.getSelection()
  if (!selection) return
  selection.removeAllRanges()
  selection.addRange(range)
}
