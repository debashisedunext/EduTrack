import { describe, expect, it } from 'vitest'
import {
  ATTACHMENT_ACCEPT,
  ATTACHMENT_DEFAULT_LIMITS,
  attachmentExtension,
  filesFromDataTransfer,
  formatFileSize,
  isAllowedAttachmentExtension,
  isPreviewableImage,
  selectAttachments,
  validateAttachmentFile,
} from './attachments'

/**
 * `File` in jsdom takes its size from its content, so a fixture for the 10 MB
 * bound would have to allocate 10 MB. The size is redefined instead — every
 * check under test reads `file.size` and nothing reads the bytes.
 */
function fileOf(name: string, size = 1024, type = ''): File {
  const file = new File(['x'], name, { type })
  Object.defineProperty(file, 'size', { value: size })
  return file
}

const MB = 1024 * 1024
const emptyContext = { existingBytes: 0, existingCount: 0, existingNames: [] as string[] }

describe('attachmentExtension', () => {
  it('lower-cases, so a screenshot saved as .PNG is not rejected as a new type', () => {
    expect(attachmentExtension('Screenshot.PNG')).toBe('png')
  })

  it('reads the last dot — a version number in the name is not the extension', () => {
    expect(attachmentExtension('report.v2.final.pdf')).toBe('pdf')
  })

  it('treats a dotfile as a name, not an extension', () => {
    expect(attachmentExtension('.gitignore')).toBe('')
    expect(isAllowedAttachmentExtension('.gitignore')).toBe(false)
  })

  it('is empty for a trailing dot and for no dot at all', () => {
    expect(attachmentExtension('archive.')).toBe('')
    expect(attachmentExtension('Dockerfile')).toBe('')
  })
})

describe('the §4B.4 allow-list', () => {
  it.each(['png', 'jpg', 'jpeg', 'gif', 'webp', 'pdf', 'doc', 'docx', 'xls', 'xlsx', 'csv', 'txt', 'log', 'zip', 'mp4'])(
    'allows .%s',
    (ext) => {
      expect(isAllowedAttachmentExtension(`file.${ext}`)).toBe(true)
    },
  )

  it.each(['exe', 'sh', 'svg', 'html', 'js', 'bat', 'dll'])('refuses .%s', (ext) => {
    expect(isAllowedAttachmentExtension(`file.${ext}`)).toBe(false)
  })

  it('keeps the legacy binary Office formats, which §4B.4 names explicitly', () => {
    // Clients still send them. Their presence is also why the extension is never
    // the real test — a `.doc` is an OLE container and proves nothing.
    expect(isAllowedAttachmentExtension('spec.doc')).toBe(true)
    expect(isAllowedAttachmentExtension('costs.xls')).toBe(true)
  })

  it('offers `accept` as extensions, never MIME types', () => {
    // A `.log` reaches the browser with an empty `type` on every platform, so a
    // MIME-based `accept` greys out the one file support agents attach most.
    expect(ATTACHMENT_ACCEPT).toContain('.log')
    expect(ATTACHMENT_ACCEPT).not.toContain('/')
  })
})

describe('isPreviewableImage', () => {
  it.each(['image/png', 'image/jpeg', 'image/gif', 'image/webp'])('previews %s', (type) => {
    expect(isPreviewableImage(type)).toBe(true)
  })

  it('refuses SVG even though it starts with image/', () => {
    // SVG is a scriptable document. Same narrowing C-066 applied to §3.9's
    // `data:image/*`, and for the same reason — an `<img>` is not a safe sink
    // for one just because its MIME prefix matches.
    expect(isPreviewableImage('image/svg+xml')).toBe(false)
  })

  it('handles absent and empty content types', () => {
    expect(isPreviewableImage(undefined)).toBe(false)
    expect(isPreviewableImage(null)).toBe(false)
    expect(isPreviewableImage('')).toBe(false)
  })
})

describe('formatFileSize', () => {
  it('matches the units §4B.5 writes its own history rows in', () => {
    expect(formatFileSize(421_888)).toBe('412 KB')
  })

  it('keeps one decimal below 10 and drops it above', () => {
    expect(formatFileSize(9.4 * MB)).toBe('9.4 MB')
    expect(formatFileSize(48 * MB)).toBe('48 MB')
  })

  it('reports bytes under 1 KB and never NaN', () => {
    expect(formatFileSize(512)).toBe('512 B')
    expect(formatFileSize(Number.NaN)).toBe('—')
  })
})

describe('validateAttachmentFile', () => {
  it('accepts a normal file', () => {
    expect(validateAttachmentFile(fileOf('gateway-500.png', 184_320, 'image/png'), emptyContext)).toBeNull()
  })

  it('reports the wrong type before the wrong size', () => {
    // A 40 MB `.exe` must not be reported as "would exceed the ticket budget" —
    // that sends the user off to delete other files to make room for something
    // that was never going to be accepted.
    const rejection = validateAttachmentFile(fileOf('payload.exe', 40 * MB), emptyContext)
    expect(rejection?.code).toBe('extension-not-allowed')
    expect(rejection?.message).toContain('.exe')
  })

  it('rejects a zero-byte file', () => {
    // Almost always a failed drag or a cloud placeholder still syncing.
    expect(validateAttachmentFile(fileOf('empty.txt', 0), emptyContext)?.code).toBe('empty-file')
  })

  it('rejects a file over the 10 MB per-file limit and says both numbers', () => {
    const rejection = validateAttachmentFile(fileOf('capture.mp4', 12 * MB, 'video/mp4'), emptyContext)
    expect(rejection?.code).toBe('file-too-large')
    expect(rejection?.message).toContain('12 MB')
    expect(rejection?.message).toContain('10 MB')
  })

  it('rejects a duplicate name case-insensitively', () => {
    const rejection = validateAttachmentFile(fileOf('Screenshot.png', 1024, 'image/png'), {
      ...emptyContext,
      existingCount: 1,
      existingBytes: 1024,
      existingNames: ['screenshot.png'],
    })
    expect(rejection?.code).toBe('duplicate')
  })

  it('rejects the 21st file', () => {
    const rejection = validateAttachmentFile(fileOf('twenty-first.png', 1024, 'image/png'), {
      ...emptyContext,
      existingCount: ATTACHMENT_DEFAULT_LIMITS.maxFiles,
    })
    expect(rejection?.code).toBe('too-many-files')
  })

  it('rejects a file that would take the ticket past 50 MB', () => {
    const rejection = validateAttachmentFile(fileOf('last.pdf', 5 * MB, 'application/pdf'), {
      ...emptyContext,
      existingCount: 5,
      existingBytes: 46 * MB,
    })
    expect(rejection?.code).toBe('ticket-size-exceeded')
  })
})

describe('selectAttachments', () => {
  it('carries running totals across one drop', () => {
    // The bug this exists to stop: twelve 5 MB files are each under both caps,
    // and the twelve of them are 10 MB over the ticket's. Validating every file
    // against the *original* totals lets all twelve through.
    const files = Array.from({ length: 12 }, (_, i) => fileOf(`shot-${i}.png`, 5 * MB, 'image/png'))
    const { accepted, rejected } = selectAttachments(files, emptyContext)

    expect(accepted).toHaveLength(10)
    expect(rejected).toHaveLength(2)
    expect(rejected.every((r) => r.rejection.code === 'ticket-size-exceeded')).toBe(true)
    expect(accepted.reduce((sum, f) => sum + f.size, 0)).toBeLessThanOrEqual(ATTACHMENT_DEFAULT_LIMITS.maxTotalBytes)
  })

  it('counts names within the same drop, so one drop cannot contain two of a file', () => {
    const { accepted, rejected } = selectAttachments(
      [fileOf('trace.log', 2048), fileOf('trace.log', 2048)],
      emptyContext,
    )
    expect(accepted).toHaveLength(1)
    expect(rejected[0]?.rejection.code).toBe('duplicate')
  })

  it('accepts what it can out of a mixed drop rather than failing the lot', () => {
    // Dragging a folder is normal. Six good files landing and being told about
    // the seventh is what the user expects; rejecting all seven is not.
    const { accepted, rejected } = selectAttachments(
      [fileOf('a.png', 1024, 'image/png'), fileOf('b.exe', 1024), fileOf('c.pdf', 2048, 'application/pdf')],
      emptyContext,
    )
    expect(accepted.map((f) => f.name)).toEqual(['a.png', 'c.pdf'])
    expect(rejected.map((r) => r.file.name)).toEqual(['b.exe'])
  })
})

describe('filesFromDataTransfer', () => {
  /** Minimal stand-in — jsdom has no `DataTransfer` constructor. */
  function transferOf(items: { kind: string; file?: File; isDirectory?: boolean }[]): DataTransfer {
    return {
      items: items.map((item) => ({
        kind: item.kind,
        getAsFile: () => item.file ?? null,
        webkitGetAsEntry: () => (item.isDirectory === undefined ? null : { isDirectory: item.isDirectory }),
      })),
      files: items.map((i) => i.file).filter(Boolean),
    } as unknown as DataTransfer
  }

  it('ignores dragged text, which populates items alongside files', () => {
    const png = fileOf('shot.png', 1024, 'image/png')
    expect(filesFromDataTransfer(transferOf([{ kind: 'string' }, { kind: 'file', file: png }]))).toEqual([png])
  })

  it('skips a dragged directory', () => {
    // §4B.4 asks for files. A directory arrives as a zero-byte typeless entry
    // and would otherwise be uploaded as a nonsense attachment.
    const png = fileOf('shot.png', 1024, 'image/png')
    const folder = fileOf('screenshots', 0)
    const files = filesFromDataTransfer(
      transferOf([
        { kind: 'file', file: folder, isDirectory: true },
        { kind: 'file', file: png, isDirectory: false },
      ]),
    )
    expect(files).toEqual([png])
  })

  it('is empty rather than throwing when there is no transfer at all', () => {
    expect(filesFromDataTransfer(null)).toEqual([])
    expect(filesFromDataTransfer(undefined)).toEqual([])
  })
})
