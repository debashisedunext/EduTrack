/**
 * Attachment rules — the allow-list, the limits and the client-side checks that
 * every upload surface shares. C-023, blueprint §4B.4.
 *
 * Split out of `attachment-picker.tsx` for the same reason `rich-text.ts` is
 * split out of the editor: the rules are the deliverable and the control is a
 * surface on top of them. A caller that needs to validate without rendering a
 * picker — a drop target somewhere else, Stream D's chat share — imports from
 * here rather than reaching into a component.
 *
 * The clipboard rules at the foot of this file are C-024's; the listener that
 * uses them is `use-attachment-paste.ts`, because it needs an effect and this
 * file is deliberately free of React.
 *
 * ## What this file is *not*
 *
 * It is **not the security boundary.** §4B.4 requires an extension allow-list
 * **and** MIME sniffing, and sniffing means reading the leading bytes: a `.doc`
 * is an OLE container and a renamed `.exe` presents whatever extension its owner
 * typed. The browser hands us `File.type`, which is derived from the extension
 * on most platforms and is empty for `.log` on all of them — so it is advisory
 * here and proves nothing. The real check, plus the AV scan and EXIF stripping,
 * is server-side and is C-025. Everything below exists to fail fast and say why,
 * so the user is not made to wait for a round trip to learn that a 40 MB video
 * was never going to be accepted.
 */

/**
 * §4B.4's list, verbatim, keyed by extension.
 *
 * The legacy binary Office formats (`doc`, `xls`) are deliberately present —
 * clients still send them, and the blueprint calls them out as exactly the
 * reason extension alone is never the test.
 */
export const ATTACHMENT_ALLOWED_EXTENSIONS = [
  // images
  'png',
  'jpg',
  'jpeg',
  'gif',
  'webp',
  // documents
  'pdf',
  'doc',
  'docx',
  'xls',
  'xlsx',
  'csv',
  'txt',
  'log',
  // archives
  'zip',
  // video
  'mp4',
] as const

export type AttachmentExtension = (typeof ATTACHMENT_ALLOWED_EXTENSIONS)[number]

/**
 * The `accept` attribute for the native file input.
 *
 * Extensions only, never MIME types. A `.log` reaches the browser with an empty
 * `type` on every platform, so a MIME-based `accept` greys it out in the picker
 * — and a support agent unable to select the log file is the exact failure §4B.4
 * put `log` on the list to avoid. `accept` is a filter, not a guard; the guard
 * is `validateAttachmentFile` below and, really, the server.
 */
export const ATTACHMENT_ACCEPT = ATTACHMENT_ALLOWED_EXTENSIONS.map((ext) => `.${ext}`).join(',')

/**
 * §4B.4's limits, "all configurable in system settings".
 *
 * There is no settings endpoint yet and no generated constant to import — unlike
 * the length bounds, which come from the contract's schema — so these are the
 * defaults, exported so the surfaces cannot each invent their own. When settings
 * land, the picker takes them as props and these become the fallback.
 */
export const ATTACHMENT_MAX_FILE_BYTES = 10 * 1024 * 1024
export const ATTACHMENT_MAX_TICKET_BYTES = 50 * 1024 * 1024
export const ATTACHMENT_MAX_TICKET_FILES = 20

export interface AttachmentLimits {
  maxFileBytes: number
  maxTotalBytes: number
  maxFiles: number
}

export const ATTACHMENT_DEFAULT_LIMITS: AttachmentLimits = {
  maxFileBytes: ATTACHMENT_MAX_FILE_BYTES,
  maxTotalBytes: ATTACHMENT_MAX_TICKET_BYTES,
  maxFiles: ATTACHMENT_MAX_TICKET_FILES,
}

/** Lower-case, no dot. `''` when the name carries no extension at all. */
export function attachmentExtension(fileName: string): string {
  const dot = fileName.lastIndexOf('.')
  // `lastIndexOf` of 0 is a dotfile — `.gitignore` has no extension, it *is* a name.
  if (dot <= 0 || dot === fileName.length - 1) return ''
  return fileName.slice(dot + 1).toLowerCase()
}

export function isAllowedAttachmentExtension(fileName: string): boolean {
  const ext = attachmentExtension(fileName)
  return (ATTACHMENT_ALLOWED_EXTENSIONS as readonly string[]).includes(ext)
}

/**
 * Whether the file can be shown as a thumbnail without downloading it first.
 *
 * Deliberately narrower than "starts with `image/`": SVG is a scriptable
 * document and is not on §4B.4's list, so it should never reach here — but if a
 * future allow-list change lets one through, it must not be handed to an `<img>`
 * on the strength of its MIME prefix. Same narrowing C-066 applied to §3.9's
 * `data:image/*`, for the same reason.
 */
const PREVIEWABLE_TYPES = new Set(['image/png', 'image/jpeg', 'image/gif', 'image/webp'])

export function isPreviewableImage(contentType: string | null | undefined): boolean {
  return contentType !== null && contentType !== undefined && PREVIEWABLE_TYPES.has(contentType.toLowerCase())
}

/**
 * Human file size.
 *
 * Binary units with decimal labels ("KB" for 1024 bytes) because that is what
 * every file manager the user has ever opened shows, and the blueprint writes
 * its own examples that way — `qa-signoff-report.pdf (412 KB)` in §4B.5's
 * history row. Consistency with the product beats standards pedantry here.
 */
export function formatFileSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return '—'
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB']
  let value = bytes / 1024
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit += 1
  }
  // One decimal below 10 ("9.4 MB"), none above ("412 KB") — matches §4B.5.
  return `${value >= 10 ? Math.round(value) : Math.round(value * 10) / 10} ${units[unit]}`
}

export type AttachmentRejectionCode =
  | 'extension-not-allowed'
  | 'file-too-large'
  | 'ticket-size-exceeded'
  | 'too-many-files'
  | 'empty-file'
  | 'duplicate'

export interface AttachmentRejection {
  code: AttachmentRejectionCode
  /** Written for the user, not for a log. */
  message: string
}

export interface AttachmentValidationContext {
  /** Bytes already attached to this ticket — accepted *and* still uploading. */
  existingBytes: number
  /** Files already attached to this ticket, same rule. */
  existingCount: number
  /** Lower-cased names already attached, for the duplicate check. */
  existingNames?: readonly string[]
  limits?: AttachmentLimits
}

/**
 * The client-side gate for one file.
 *
 * Order matters and is not arbitrary: the per-file checks run before the
 * per-ticket ones, so a 40 MB `.exe` is reported as the wrong *type* rather than
 * as "would exceed the ticket budget", which would send the user off to delete
 * other attachments to make room for a file that was never going to be allowed.
 */
export function validateAttachmentFile(
  file: File,
  { existingBytes, existingCount, existingNames = [], limits = ATTACHMENT_DEFAULT_LIMITS }: AttachmentValidationContext,
): AttachmentRejection | null {
  if (!isAllowedAttachmentExtension(file.name)) {
    const ext = attachmentExtension(file.name)
    return {
      code: 'extension-not-allowed',
      message: ext ? `.${ext} files are not allowed` : 'Files without an extension are not allowed',
    }
  }

  // A zero-byte file is almost always a failed drag or a still-syncing cloud
  // placeholder. Uploading it wastes a scan and leaves a useless row behind.
  if (file.size === 0) {
    return { code: 'empty-file', message: `${file.name} is empty` }
  }

  if (file.size > limits.maxFileBytes) {
    return {
      code: 'file-too-large',
      message: `${formatFileSize(file.size)} exceeds the ${formatFileSize(limits.maxFileBytes)} limit for one file`,
    }
  }

  if (existingNames.includes(file.name.toLowerCase())) {
    return { code: 'duplicate', message: `${file.name} is already attached` }
  }

  if (existingCount + 1 > limits.maxFiles) {
    return {
      code: 'too-many-files',
      message: `A ticket may hold ${limits.maxFiles} attachments`,
    }
  }

  if (existingBytes + file.size > limits.maxTotalBytes) {
    return {
      code: 'ticket-size-exceeded',
      message: `This would take the ticket past its ${formatFileSize(limits.maxTotalBytes)} total`,
    }
  }

  return null
}

export interface AttachmentSelection {
  accepted: File[]
  rejected: { file: File; rejection: AttachmentRejection }[]
}

/**
 * Validate a whole drop or multi-select.
 *
 * The running totals are the point. Validating each file against the *original*
 * counts would let twelve 5 MB files through one drop — each one individually
 * under both caps, the twelve of them 10 MB over the ticket's. The rejected list
 * is returned rather than thrown so a mixed drop can accept what it can and
 * report the rest, which is what a user dragging a folder expects.
 */
export function selectAttachments(files: readonly File[], context: AttachmentValidationContext): AttachmentSelection {
  const accepted: File[] = []
  const rejected: { file: File; rejection: AttachmentRejection }[] = []

  let bytes = context.existingBytes
  let count = context.existingCount
  const names = [...(context.existingNames ?? [])]

  for (const file of files) {
    const rejection = validateAttachmentFile(file, { ...context, existingBytes: bytes, existingCount: count, existingNames: names })
    if (rejection) {
      rejected.push({ file, rejection })
      continue
    }
    accepted.push(file)
    bytes += file.size
    count += 1
    names.push(file.name.toLowerCase())
  }

  return { accepted, rejected }
}

/**
 * Names a browser invents for a bitmap that came off the clipboard rather than
 * off a disk. C-024.
 *
 * Chrome, Edge and Firefox all hand a Snipping Tool capture over as
 * `image.png`; Safari has used `Image (1).png`; some paths supply no name at
 * all. **Every screenshot a user pastes therefore arrives under the same name as
 * the last one**, and `validateAttachmentFile`'s duplicate check would refuse
 * the second — which reads as "paste is broken" on the single most common
 * attachment action §4B.4 has.
 *
 * A file genuinely called `image.png`, copied out of a file manager, is renamed
 * too. That is the intended trade: the original name carries no information the
 * generated one does not, and keeping it would reintroduce the collision.
 */
const GENERIC_CLIPBOARD_NAME = /^(image|photo|screenshot|blob|unknown|untitled)(\s*\(\d+\))?(\.[a-z0-9]+)?$/i

export function isGenericClipboardName(fileName: string): boolean {
  const trimmed = fileName.trim()
  return trimmed === '' || GENERIC_CLIPBOARD_NAME.test(trimmed)
}

/**
 * Extension for a clipboard file, from its MIME type.
 *
 * Derived rather than defaulted. Guessing `png` for a type we do not recognise
 * would name a file something it is not, and C-025's server-side sniffing would
 * then reject a file this side had just told the user was fine — the two must
 * not disagree. An unrecognised type keeps its own subtype (`image/tiff` →
 * `tiff`), falls off the allow-list, and is refused with an honest reason.
 */
function clipboardExtension(contentType: string): string {
  const subtype = contentType.split('/')[1]?.split(';')[0]?.trim().toLowerCase() ?? ''
  if (!subtype) return ''
  if (subtype === 'jpeg') return 'jpg'
  // `svg+xml` must stay recognisable as `svg` so it is rejected as one — it is a
  // scriptable document and is deliberately off §4B.4's list.
  if (subtype === 'svg+xml') return 'svg'
  return subtype.replace(/[^a-z0-9]/g, '')
}

/**
 * Local time, to the second, in a name a person can read and a list can sort.
 *
 * Local and not UTC on purpose: storage is UTC everywhere, but a file name is
 * presentation, and "the screenshot I took at 14:30" is the only handle the
 * person who pasted it has.
 *
 * One second of resolution is deliberate. Two different screenshots cannot be
 * captured and pasted inside the same second, but the *same* clipboard pasted
 * twice — a double `Ctrl+V` — lands on the identical name and is refused as a
 * duplicate, which is the correct outcome rather than a bug in the stamp.
 */
function clipboardStamp(now: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return (
    `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}` +
    `-${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`
  )
}

/**
 * What a pasted file should be called.
 *
 * A real name is kept untouched — pasting a copied `error-log.txt` out of a file
 * manager must not lose the one thing that says what it is. `index` separates
 * several images pasted in a single event, which Chrome produces when a run of
 * cells is copied out of a spreadsheet.
 */
export function clipboardAttachmentName(file: File, now: Date = new Date(), index = 0): string {
  if (!isGenericClipboardName(file.name)) return file.name

  const prefix = file.type.startsWith('image/') ? 'screenshot' : 'pasted-file'
  const suffix = index > 0 ? `-${index + 1}` : ''
  const ext = clipboardExtension(file.type)
  const stem = `${prefix}-${clipboardStamp(now)}${suffix}`
  // No type at all means no honest extension to give it. Left bare, so the
  // allow-list refuses it and says why, rather than being handed a guess.
  return ext ? `${stem}.${ext}` : stem
}

/**
 * Give a batch of pasted files usable names — C-024.
 *
 * Separate from `filesFromClipboard` because not every paste arrives as a
 * `DataTransfer`: `RichTextEditor` intercepts image pastes itself and hands out
 * plain `File[]`, and those files carry exactly the same `image.png` collision.
 * Anything already named is returned untouched and by identity.
 *
 * A rename allocates a new `File` over the same underlying blob rather than
 * shadowing `name` on the existing one. That is not fussiness: Chrome's
 * `FormData` reads a file's name from an internal slot and not from the JS
 * property, so a shadowed name would upload under the old one — the rename would
 * appear to work everywhere except the one place it has to.
 */
export function renameClipboardFiles(files: readonly File[], { now = new Date() }: { now?: Date } = {}): File[] {
  return files.map((file, index) => {
    const name = clipboardAttachmentName(file, now, index)
    if (name === file.name) return file
    return new File([file], name, { type: file.type, lastModified: file.lastModified })
  })
}

/**
 * Files out of a clipboard paste — C-024, blueprint §4B.4.
 *
 * `items` over `files` for the same reason `filesFromDataTransfer` prefers it:
 * a paste carrying both text and an image populates `items` with a `string`
 * entry beside the file, and only `kind` tells them apart. A text-only paste
 * yields no files at all, which is what lets a caller ignore it cheaply.
 */
export function filesFromClipboard(
  data: DataTransfer | null | undefined,
  options: { now?: Date } = {},
): File[] {
  if (!data) return []

  const items = data.items ? Array.from(data.items) : []
  const raw: File[] =
    items.length > 0
      ? items.flatMap((item) => {
          if (item.kind !== 'file') return []
          const file = item.getAsFile()
          return file ? [file] : []
        })
      : data.files
        ? Array.from(data.files)
        : []

  return renameClipboardFiles(raw, options)
}

/**
 * Whether the clipboard carries text a text field would actually receive.
 *
 * The guard for "do not hijack an ordinary paste". Copying an image out of a web
 * page puts an `<img>` tag on the clipboard as `text/html` **beside** the file,
 * so the presence of a file is not on its own evidence that the user meant to
 * attach something. Whitespace-only `text/plain` does not count — Chrome ships a
 * single newline with some image copies.
 */
export function clipboardHasText(data: DataTransfer | null | undefined): boolean {
  if (!data || typeof data.getData !== 'function') return false
  const types = Array.from(data.types ?? [])
  if (types.includes('text/plain') && data.getData('text/plain').trim() !== '') return true
  return types.includes('text/html') && data.getData('text/html').trim() !== ''
}

/**
 * Files out of a drag-and-drop `DataTransfer`.
 *
 * `items` is preferred over `files` because it is the only one that
 * distinguishes a dragged file from dragged *text* — dropping a selection from
 * another page populates `items` with a string entry, and reading `files` alone
 * silently yields nothing while the drop visibly "worked". Directories are
 * skipped: `webkitGetAsEntry` is the only way to recurse one and §4B.4 asks for
 * files, not folder upload.
 */
export function filesFromDataTransfer(transfer: DataTransfer | null | undefined): File[] {
  if (!transfer) return []
  if (transfer.items && transfer.items.length > 0) {
    const files: File[] = []
    for (const item of Array.from(transfer.items)) {
      if (item.kind !== 'file') continue
      const file = item.getAsFile()
      // A dragged directory has an empty type and a size of 0 in every browser;
      // `webkitGetAsEntry().isDirectory` is the reliable test where it exists.
      const entry = (item as DataTransferItem & { webkitGetAsEntry?: () => { isDirectory?: boolean } | null })
        .webkitGetAsEntry?.()
      if (entry?.isDirectory) continue
      if (file) files.push(file)
    }
    return files
  }
  return transfer.files ? Array.from(transfer.files) : []
}
