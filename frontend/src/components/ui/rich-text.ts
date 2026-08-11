import DOMPurify from 'dompurify'
import { cn } from '@/lib/utils'

/**
 * Rich text — the client half of PLAN.md §3.9.
 *
 * §3.9 is normative for all three rich-text fields on the product: the ticket's
 * Task Description and Steps to Generate (blueprint §7.5) and the comment box
 * (§4B.5). Storage is sanitised **HTML**, not Markdown and not a document model.
 *
 * Two rules from §3.9 shape everything below:
 *
 * 1. **The server sanitises on write, always.** This module is the client copy,
 *    and §3.9 calls that copy *advice* — the only sanitiser an attacker cannot
 *    skip is the one on the write path (C-067). Nothing here is a security
 *    boundary on its own.
 * 2. **Render through the sanitiser too, never only over what was stored.** A
 *    row written by an older, looser allow-list is still in the table; running
 *    the render path through today's list is what makes tightening it
 *    retroactive. So `sanitizeRichText` is called on the way in *and* on the way
 *    out, and `RichTextView` exists precisely so no caller is tempted to reach
 *    for `dangerouslySetInnerHTML` directly.
 *
 * These fields are written by support desks pasting client email and
 * screenshots, and read by a manager with every reason to trust the page. That
 * is the exact shape of a stored-XSS vulnerability, which is why the allow-list
 * below is a copy of §3.9's table rather than a judgement call made here.
 */

/**
 * §3.9's allowed markup, verbatim.
 *
 * Deliberately absent and worth naming, because their absence looks like an
 * oversight: no `table` (the blueprint never asks for one and a pasted Outlook
 * table is the main source of markup soup), no `h1`/`h2` (the page owns those —
 * a heading inside a description must not outrank the ticket title), no `span`
 * and no `style` anywhere, which is what keeps pasted content on the design
 * tokens instead of carrying Word's colours into the page.
 */
export const RICH_TEXT_ALLOWED_TAGS = [
  'p',
  'br',
  'strong',
  'em',
  'u',
  's',
  'ol',
  'ul',
  'li',
  'code',
  'pre',
  'blockquote',
  'a',
  'img',
  'h3',
  'h4',
] as const

/** §3.9 allows `a[href]` and `img[src]` and nothing else. */
export const RICH_TEXT_ALLOWED_ATTR = ['href', 'src'] as const

/**
 * §3.9's 20 000-character bound, measured over the **sanitised HTML** because
 * that is the string the column stores and Bean Validation rejects (D-4).
 *
 * Kept as a local constant rather than imported from the generated client: this
 * is `components/ui/`, the library all four streams consume, and it must not
 * depend on the tickets contract. Ticket callers pass the generated
 * `createTicketBodyDescriptionMax` explicitly so a contract change moves the
 * bound without anyone editing this file.
 */
export const RICH_TEXT_MAX_LENGTH = 20_000

/**
 * §3.9 restricts `href` and `src` to `http`, `https` and `data:image/*`.
 *
 * **Narrowed once, on purpose:** `image/svg+xml` is excluded. SVG is a document
 * format with script and external-reference capability, not a screenshot
 * format, and §3.9's stated reason for permitting data URIs at all is the
 * support desk pasting a screenshot. Narrowing an allow-list is the safe
 * direction, but it is still a deviation from the table, so it is written down
 * here and flagged on the task rather than made quietly.
 */
const RICH_TEXT_URI_REGEXP = /^(?:https?:|data:image\/(?:png|jpeg|jpg|gif|webp|avif|bmp)[;,])/i

/** Elements whose presence inside a `div` means the div is a container, not a paragraph. */
const BLOCK_TAGS = new Set([
  'ADDRESS',
  'BLOCKQUOTE',
  'DIV',
  'DL',
  'FIGURE',
  'H1',
  'H2',
  'H3',
  'H4',
  'H5',
  'H6',
  'HR',
  'LI',
  'OL',
  'P',
  'PRE',
  'SECTION',
  'TABLE',
  'UL',
])

/**
 * Legacy and browser-emitted tags mapped onto their §3.9 equivalent **before**
 * sanitisation, so the formatting survives instead of being stripped.
 *
 * This is not cosmetic. `document.execCommand` — which is what a contentEditable
 * toolbar runs, and still the only cross-browser way to do it — emits `<b>`,
 * `<i>` and `<strike>`, none of which are on §3.9's list. Without this map the
 * editor would appear to work and then silently lose every bold word the moment
 * the value was sanitised, which is the worst failure available: invisible,
 * and only at rest.
 *
 * `h1`/`h2` fold down rather than being dropped because a pasted document's
 * top-level heading is real structure; it just must not outrank the page.
 */
const TAG_ALIASES: Readonly<Record<string, string>> = {
  B: 'strong',
  I: 'em',
  STRIKE: 's',
  DEL: 's',
  INS: 'u',
  MARK: 'strong',
  H1: 'h3',
  H2: 'h3',
  H5: 'h4',
  H6: 'h4',
}

/** Wrappers that carry styling and no meaning — dropped, keeping their children. */
const UNWRAP_TAGS = new Set(['SPAN', 'FONT', 'SECTION', 'ARTICLE', 'MAIN', 'HEADER', 'FOOTER'])

let hooksRegistered = false

/**
 * DOMPurify hooks are global to the instance, and the default export is a
 * singleton. Any other stream calling `DOMPurify.sanitize` directly would
 * therefore inherit these — so don't: import `sanitizeRichText` instead, or the
 * two configurations quietly become one.
 */
function registerHooks(): void {
  if (hooksRegistered) return
  hooksRegistered = true

  DOMPurify.addHook('afterSanitizeAttributes', (node) => {
    if (!(node instanceof Element)) return

    // Re-check every URI ourselves. `ALLOWED_URI_REGEXP` alone is not enough:
    // DOMPurify has a second, separate branch that permits *any* `data:` URI on
    // its DATA_URI_TAGS (img among them) regardless of that regexp. That branch
    // would let `data:image/svg+xml` straight through the narrowing above. This
    // hook runs after all of DOMPurify's own attribute filtering, so it has the
    // last word.
    for (const attr of RICH_TEXT_ALLOWED_ATTR) {
      const value = node.getAttribute(attr)
      // Strip control characters and whitespace before testing, or
      // `java<TAB>script:` reads as an unknown scheme to the regexp and as
      // `javascript:` to the browser. DOMPurify normalises this for its own
      // check; this hook reads the raw attribute, so it has to do the same.
      // The control characters are the point of the expression, not an
      // oversight — which is exactly what `no-control-regex` exists to catch.
      // eslint-disable-next-line no-control-regex
      const scrubbed = (value ?? '').replace(/[\u0000-\u0020\u00a0]/g, '')
      if (value !== null && !RICH_TEXT_URI_REGEXP.test(scrubbed)) {
        node.removeAttribute(attr)
      }
    }

    if (node.tagName === 'IMG' && !node.hasAttribute('src')) {
      // An image whose source was just stripped renders as a broken-image icon
      // and announces its own filename to a screen reader. Neither is content.
      node.remove()
      return
    }

    if (node.tagName === 'IMG') {
      // §3.9 allows `img[src]` and no `alt`, so there is no author-supplied
      // alternative text to keep — and an image with no `alt` at all makes a
      // screen reader read out the URL, which for a pasted data URI is several
      // thousand characters of base64. `alt=""` marks it decorative and silences
      // that. This is normalisation of our own output, not a widened allow-list.
      // Whether authors should be able to *write* alt text is a real question
      // and belongs to §3.9, not to this file — raised on C-066.
      node.setAttribute('alt', '')
    }

    if (node.tagName === 'A' && node.hasAttribute('href')) {
      // A ticket description linking out should not hand the opened tab a
      // reference back to the app, and a client-supplied URL is exactly the
      // input `noopener` exists for.
      node.setAttribute('target', '_blank')
      node.setAttribute('rel', 'noopener noreferrer nofollow')
    }
  })
}

/**
 * Fold browser- and Word-emitted markup onto §3.9's vocabulary.
 *
 * Runs on untrusted input, so it must not be a place where anything executes:
 * `DOMParser.parseFromString(html, 'text/html')` builds an inert document — no
 * script runs, no `src` is fetched, no `onerror` fires. Sanitisation still
 * happens after this, never instead of it, so the final word on the output is
 * always DOMPurify's.
 */
function normaliseLegacyMarkup(html: string): string {
  const doc = new DOMParser().parseFromString(html, 'text/html')

  // A static snapshot: renaming replaces a node, but its children move into the
  // replacement and stay in the tree, so entries later in the list are still
  // live. A live NodeList would skip them.
  for (const el of Array.from(doc.body.querySelectorAll('*'))) {
    const alias = TAG_ALIASES[el.tagName]
    if (alias) {
      renameElement(el, alias)
      continue
    }
    if (UNWRAP_TAGS.has(el.tagName)) {
      unwrapElement(el)
      continue
    }
    if (el.tagName === 'DIV') {
      // A `div` wrapping blocks is a container — unwrapping it keeps the
      // structure. A `div` wrapping only inline content is a paragraph in all
      // but name, and renaming it is what preserves the line break. Renaming
      // the container form instead would produce `<p><ul>…</ul></p>`, which the
      // parser splits into empty paragraphs on the next round trip.
      const wrapsBlocks = Array.from(el.children).some((child) => BLOCK_TAGS.has(child.tagName))
      if (wrapsBlocks) unwrapElement(el)
      else renameElement(el, 'p')
    }
  }

  return doc.body.innerHTML
}

function renameElement(el: Element, tagName: string): void {
  const replacement = el.ownerDocument.createElement(tagName)
  while (el.firstChild) replacement.appendChild(el.firstChild)
  el.replaceWith(replacement)
}

function unwrapElement(el: Element): void {
  el.replaceWith(...Array.from(el.childNodes))
}

/**
 * The one way rich text enters or leaves the app.
 *
 * Call it on every value written *and* on every value rendered — §3.9's
 * retroactivity rule. `RichTextView` does the render half; nothing else in the
 * codebase should reach for `dangerouslySetInnerHTML`.
 */
export function sanitizeRichText(dirty: string): string {
  if (!dirty) return ''
  registerHooks()

  return DOMPurify.sanitize(normaliseLegacyMarkup(dirty), {
    ALLOWED_TAGS: [...RICH_TEXT_ALLOWED_TAGS],
    ALLOWED_ATTR: [...RICH_TEXT_ALLOWED_ATTR],
    ALLOWED_URI_REGEXP: RICH_TEXT_URI_REGEXP,
    // Without these two, `data-*` and `aria-*` survive a whitelist of named
    // attributes — DOMPurify treats them as families, not entries.
    ALLOW_DATA_ATTR: false,
    ALLOW_ARIA_ATTR: false,
    // §3.9: script, style, iframe and every `on*` handler are *stripped, not
    // escaped*. KEEP_CONTENT keeps the text inside tags that are merely not on
    // the list — a `<section>`'s prose survives — while DOMPurify's own
    // forbidden-contents set still discards the body of `<script>` and
    // `<style>`, which is the distinction §3.9 is drawing.
    KEEP_CONTENT: true,
    RETURN_DOM: false,
    RETURN_DOM_FRAGMENT: false,
    RETURN_TRUSTED_TYPE: false,
  })
}

/**
 * Sanitised HTML as flat text.
 *
 * Two callers, both real: the empty check below, and any surface that must show
 * rich text where markup cannot go — a grid cell, a list preview, an email
 * subject. PLAN.md §3.8 also notes that if Steps to Generate ever needs
 * searching, the full-text index goes over a plain-text projection and never
 * over the column, because an index over markup matches `li` and `href` as
 * readily as prose.
 */
export function richTextToPlainText(html: string): string {
  if (!html) return ''
  const doc = new DOMParser().parseFromString(sanitizeRichText(html), 'text/html')

  const walk = (node: Node): string => {
    if (node.nodeType === Node.TEXT_NODE) return node.textContent ?? ''
    if (!(node instanceof Element)) return ''

    const children = Array.from(node.childNodes).map(walk).join('')
    if (node.tagName === 'BR') return '\n'
    if (BLOCK_TAGS.has(node.tagName)) return `${children}\n`
    return children
  }

  return walk(doc.body).replace(/\n{3,}/g, '\n\n').trim()
}

/**
 * Whether the value counts as "the user wrote nothing".
 *
 * A required-field check written as `value.trim() === ''` passes on an empty
 * editor and this is the single most common bug in a contentEditable form: the
 * browser leaves `<p><br></p>` behind, which is 13 characters of nothing. An
 * image with no text is *not* empty — a screenshot is frequently the whole
 * report — so the check is over text **and** embedded images.
 */
export function isRichTextEmpty(html: string): boolean {
  if (!html) return true
  const clean = sanitizeRichText(html)
  if (clean.includes('<img')) return false
  return richTextToPlainText(clean).trim().length === 0
}

/**
 * Plain text as rich text, keeping the line structure.
 *
 * A blank line is a paragraph break and a single newline is a `<br>` — which is
 * what someone pasting a stack trace or a numbered list out of Notepad means,
 * and what dropping the text in verbatim would lose, since HTML collapses it.
 */
export function plainTextToRichText(text: string): string {
  if (!text) return ''
  return escapeHtml(text)
    .split(/\r?\n\s*\r?\n/)
    .filter((block) => block.trim().length > 0)
    .map((block) => `<p>${block.replace(/\r?\n/g, '<br>')}</p>`)
    .join('')
}

export function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/**
 * Render-time shim for values written before these fields were rich text.
 *
 * Every description already in the database is plain text, and will stay that
 * way until C-067 lands the write path and backfills. Handing that straight to
 * `RichTextView` renders it correctly *except* for its line breaks, which HTML
 * collapses — so a two-paragraph description written last week would silently
 * become one. This gives markup-free values their paragraphs back and leaves
 * anything already containing elements alone.
 *
 * **Delete this when the backfill lands.** It exists to make the migration
 * invisible to the reader, not to be a permanent format sniff.
 */
export function ensureRichText(value: string): string {
  if (!value) return ''
  // Parsing rather than pattern-matching for `<`: a description reading
  // `if (a < b)` contains no element, and a regexp guessing at tags gets that
  // wrong in the direction that mangles the text.
  const doc = new DOMParser().parseFromString(value, 'text/html')
  return doc.body.children.length > 0 ? value : plainTextToRichText(value)
}

// ------------------------------------------------- shared editor vocabulary
//
// The command names, the toolbar presets and the prose styling live here
// rather than beside the components for two reasons: the editor and the view
// must agree on the styling or the editor is lying about what the reader will
// see, and a component file that also exports constants breaks fast refresh.

export type RichTextCommand =
  | 'bold'
  | 'italic'
  | 'underline'
  | 'strikethrough'
  | 'heading3'
  | 'heading4'
  | 'bulletList'
  | 'numberedList'
  | 'blockquote'
  | 'inlineCode'
  | 'codeBlock'
  | 'link'
  | 'clearFormatting'

/** Full bar — the description and Steps to Generate, where structure matters. */
export const RICH_TEXT_DEFAULT_TOOLBAR: RichTextCommand[][] = [
  ['bold', 'italic', 'underline', 'strikethrough'],
  ['heading3', 'heading4'],
  ['bulletList', 'numberedList'],
  ['blockquote', 'inlineCode', 'codeBlock'],
  ['link', 'clearFormatting'],
]

/**
 * Short bar for the comment box (C-029). A comment is a paragraph or two;
 * offering headings there invites people to shout, and the control is at its
 * width budget inside the slide-over anyway.
 */
export const RICH_TEXT_COMPACT_TOOLBAR: RichTextCommand[][] = [
  ['bold', 'italic', 'strikethrough'],
  ['bulletList', 'numberedList'],
  ['inlineCode', 'link'],
]

/**
 * Typography for §3.9's fourteen tags, on the design tokens.
 *
 * Written as arbitrary-variant utilities rather than `@tailwindcss/typography`:
 * that plugin styles a much larger vocabulary than we allow, ships its own grey
 * scale, and would be a second source of truth beside blueprint §12.1's frozen
 * tokens. Fourteen selectors is less than one dependency's worth of divergence.
 *
 * Worn by both the editable region and the rendered view — what the author
 * types must look like what the reader gets.
 */
export const richTextProseClasses = cn(
  'text-sm text-content',
  '[&_p]:my-2 [&_p:first-child]:mt-0 [&_p:last-child]:mb-0',
  '[&_h3]:mb-1 [&_h3]:mt-4 [&_h3]:text-h3 [&_h3:first-child]:mt-0',
  '[&_h4]:mb-1 [&_h4]:mt-3 [&_h4]:font-semibold [&_h4:first-child]:mt-0',
  '[&_strong]:font-semibold',
  '[&_em]:italic',
  '[&_u]:underline',
  '[&_s]:line-through',
  '[&_ul]:my-2 [&_ul]:list-disc [&_ul]:pl-6',
  '[&_ol]:my-2 [&_ol]:list-decimal [&_ol]:pl-6',
  '[&_li]:my-0.5',
  '[&_blockquote]:my-2 [&_blockquote]:border-l-2 [&_blockquote]:border-border [&_blockquote]:pl-3 [&_blockquote]:text-content-muted',
  '[&_code]:rounded [&_code]:bg-subtle [&_code]:px-1 [&_code]:py-0.5 [&_code]:font-mono [&_code]:text-caption',
  // `whitespace-pre-wrap` is the point of a code block: indentation in pasted
  // steps to reproduce is content, and HTML collapses it by default.
  '[&_pre]:my-2 [&_pre]:overflow-x-auto [&_pre]:rounded-control [&_pre]:bg-subtle [&_pre]:p-3 [&_pre]:font-mono [&_pre]:text-caption [&_pre]:whitespace-pre-wrap',
  '[&_pre_code]:bg-transparent [&_pre_code]:p-0',
  '[&_a]:text-primary [&_a]:underline [&_a]:underline-offset-2 [&_a:hover]:no-underline',
  // A pasted screenshot is frequently 2000px wide and would otherwise blow out
  // the summary panel it was pasted into.
  '[&_img]:my-2 [&_img]:max-w-full [&_img]:rounded-control [&_img]:border [&_img]:border-border',
)
