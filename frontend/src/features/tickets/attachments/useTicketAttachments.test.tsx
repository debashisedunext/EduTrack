import { beforeEach, describe, expect, it } from 'vitest'
import { act, renderHook, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { server } from '@/mocks/server'
import { getDb } from '@/mocks/db'
import { useTicketAttachments } from './useTicketAttachments'

/**
 * ## Why the upload handler is overridden here
 *
 * The seeded handler in `mocks/handlers/tickets.ts` calls `request.formData()`,
 * and under vitest **no genuine multipart body can reach it**. jsdom supplies
 * `FormData`, `Blob` and `File`; Node supplies `fetch` and `Request`. Node
 * refuses to serialise a foreign `FormData` and stringifies it instead, so the
 * request arrives as the literal `[object FormData]` under
 * `Content-Type: text/plain` and the handler dies inside undici with
 * "Content-Type was not one of multipart/form-data…".
 *
 * That is not a defect in the code under test, and it is not fixable from here:
 * undici brand-checks its own `Blob`/`File` and rejects both `node:buffer`'s and
 * the one off `Response.blob()`. Bridging it properly means replacing three
 * globals in `test/setup.ts` — shared infrastructure this stream does not own,
 * and which already carries `makeAbortSignalsCrossRealmSafe` for the identical
 * class of problem, so that is where it would belong.
 *
 * The override mirrors the real handler's *contract* — the 201 envelope,
 * `PENDING` scan status, one new ID per call — without reading the body. It
 * therefore cannot know a file's name or size, so nothing below asserts that the
 * server echoed either; what the server cannot see, the test does not claim.
 *
 * What the override cannot prove, `uploadTicketAttachment.test.ts` proves
 * directly: the outgoing request carries a `FormData` body and **no**
 * `Content-Type`, which is the part that actually regressed. The full round trip
 * belongs to a real browser and is recorded as an open verification step.
 */

function fileOf(name: string, size = 1024, type = 'image/png'): File {
  const file = new File(['x'], name, { type })
  Object.defineProperty(file, 'size', { value: size })
  return file
}

let uploadPaths: string[] = []
let nextAttachmentId = 100

/** The happy path, installed for every test; individual tests override it. */
beforeEach(() => {
  uploadPaths = []
  nextAttachmentId = 100
  server.use(http.post('*/tickets/:ticketId/attachments', ({ params }) => created(String(params.ticketId))))
})

function created(ticketId: string) {
  uploadPaths.push(ticketId)
  const ticket = getDb().tickets.find((t) => t.ticketId === ticketId)
  if (!ticket) return HttpResponse.json({ title: 'Not found', status: 404 }, { status: 404 })
  return HttpResponse.json(
    {
      data: {
        id: nextAttachmentId++,
        fileName: `uploaded-${nextAttachmentId}`,
        contentType: 'application/octet-stream',
        sizeBytes: 1024,
        // §4B.4: not downloadable until the scan passes. The real handler flips
        // this to CLEAN a moment later; here it stays PENDING, which is the
        // state the UI has to render honestly.
        scanStatus: 'PENDING',
        isClientVisible: false,
        isDeleted: false,
        cycleNo: ticket.cycleNo,
        createdAt: new Date().toISOString(),
      },
    },
    { status: 201 },
  )
}

/** Fail the *nth* upload with a 413, pass the rest. Uploads run in order. */
function failUploadNumber(n: number) {
  let call = 0
  server.use(
    http.post('*/tickets/:ticketId/attachments', ({ params }) => {
      call += 1
      if (call === n) {
        uploadPaths.push(String(params.ticketId))
        return HttpResponse.json(
          { type: 'about:blank', title: 'file-too-large', detail: 'File exceeds the 10 MB limit', status: 413 },
          { status: 413 },
        )
      }
      return created(String(params.ticketId))
    }),
  )
}

/**
 * A ticket the mock's signed-in user can actually reach.
 *
 * The real attachment handlers go through `findTicket`, which is scoped — the
 * default user is Ravi, a Developer, so `assigned_to = me` is his whole world
 * and the §14 walkthrough ticket (Meera's) 404s. Reading the ticket out of the
 * db keeps this from breaking the day the fixtures are renumbered.
 */
function inScopeTicketId(): string {
  const db = getDb()
  const mine = db.tickets.find((t) => t.assigneeId === db.currentUserId)
  if (!mine) throw new Error('No seeded ticket is in the mock user’s scope')
  return mine.ticketId
}

describe('immediate mode — the ticket already exists', () => {
  it('uploads on add and exposes the server ID for attachmentIds', async () => {
    const ticketId = inScopeTicketId()
    const { result } = renderHook(() => useTicketAttachments({ ticketId }))

    act(() => result.current.add([fileOf('gateway-500.png', 184_320)]))

    // In flight first — the row exists immediately so the user can see the file
    // was taken, rather than the UI going quiet until the round trip finishes.
    expect(result.current.items).toHaveLength(1)
    expect(result.current.items[0].status).toBe('uploading')
    expect(result.current.isUploading).toBe(true)

    await waitFor(() => expect(result.current.attachmentIds).toHaveLength(1))
    expect(result.current.isUploading).toBe(false)
    expect(uploadPaths).toEqual([ticketId])
  })

  it('shows the file as scanning until the scan passes, never as ready', async () => {
    // §4B.4: a file becomes visible only after the AV scan. A PENDING row that
    // rendered as ready would offer a download the server will refuse.
    const ticketId = inScopeTicketId()
    const { result } = renderHook(() => useTicketAttachments({ ticketId }))

    act(() => result.current.add([fileOf('report.pdf', 4096, 'application/pdf')]))

    await waitFor(() => expect(result.current.items[0].status).toBe('scanning'))
  })

  it('does not lose a successful upload when the surface passes no `existing` list', async () => {
    // Quick update holds a `Ticket`, not the aggregated `/full` payload, so it
    // has no attachment list to merge with. Dropping the local row on success
    // without keeping the server row would make the file vanish the instant it
    // uploaded — which reads exactly like a failure.
    const ticketId = inScopeTicketId()
    const { result } = renderHook(() => useTicketAttachments({ ticketId }))

    act(() => result.current.add([fileOf('trace.log', 2048, 'text/plain')]))

    await waitFor(() => expect(result.current.attachmentIds).toHaveLength(1))
    expect(result.current.items).toHaveLength(1)
  })

  it('uploads a multi-file add one at a time, not as a parallel burst', async () => {
    // The per-ticket 50 MB cap can only be enforced against a settled total, and
    // twenty concurrent multipart requests arrive in an order no server can
    // reconcile.
    const ticketId = inScopeTicketId()
    const { result } = renderHook(() => useTicketAttachments({ ticketId }))

    act(() => result.current.add([fileOf('a.png'), fileOf('b.png'), fileOf('c.png')]))

    await waitFor(() => expect(result.current.attachmentIds).toHaveLength(3))
    expect(result.current.items).toHaveLength(3)
    // Three distinct IDs, not one replayed three times.
    expect(new Set(result.current.attachmentIds).size).toBe(3)
  })

  it('surfaces a 413 on the row rather than dropping the file silently', async () => {
    failUploadNumber(1)
    const ticketId = inScopeTicketId()
    const { result } = renderHook(() => useTicketAttachments({ ticketId }))

    act(() => result.current.add([fileOf('huge.zip', 11 * 1024 * 1024, 'application/zip')]))

    await waitFor(() => expect(result.current.items[0].status).toBe('failed'))
    expect(result.current.items[0].error).toMatch(/10 MB/i)
    expect(result.current.items[0].name).toBe('huge.zip')
    expect(result.current.attachmentIds).toHaveLength(0)
  })

  it('keeps going after one file fails, rather than abandoning the rest of the add', async () => {
    failUploadNumber(1)
    const ticketId = inScopeTicketId()
    const { result } = renderHook(() => useTicketAttachments({ ticketId }))

    act(() => result.current.add([fileOf('huge.zip', 11 * 1024 * 1024), fileOf('fine.png')]))

    await waitFor(() => expect(result.current.attachmentIds).toHaveLength(1))
    expect(result.current.items.filter((i) => i.status === 'failed')).toHaveLength(1)
  })
})

describe('deferred mode — the create form, before a ticket exists', () => {
  it('stages files without uploading anything', () => {
    const { result } = renderHook(() => useTicketAttachments({ ticketId: null }))

    act(() => result.current.add([fileOf('shot.png'), fileOf('log.txt', 512, 'text/plain')]))

    expect(result.current.pendingCount).toBe(2)
    expect(result.current.items).toHaveLength(2)
    // Shown as ready, not as a spinner: nothing is happening to a staged file,
    // and a permanent spinner on a create form reads as a stuck upload.
    expect(result.current.items.every((i) => i.status === 'ready')).toBe(true)
    expect(uploadPaths).toEqual([])
  })

  it('uploads everything staged once flush is given the new ticket ID', async () => {
    const ticketId = inScopeTicketId()
    const { result } = renderHook(() => useTicketAttachments({ ticketId: null }))

    act(() => result.current.add([fileOf('one.png'), fileOf('two.png')]))

    let outcome!: { uploaded: number; failed: string[] }
    await act(async () => {
      outcome = await result.current.flush(ticketId)
    })

    expect(outcome).toEqual({ uploaded: 2, failed: [] })
    expect(uploadPaths).toEqual([ticketId, ticketId])
    await waitFor(() => expect(result.current.pendingCount).toBe(0))
  })

  it('reports per-file outcomes instead of throwing, so a created ticket is never reported as failed', async () => {
    // The ticket is real by the time flush runs. A rejected file must not be
    // allowed to surface as "the ticket was not created" — the user would go
    // looking for a ticket that exists.
    failUploadNumber(2)
    const ticketId = inScopeTicketId()
    const { result } = renderHook(() => useTicketAttachments({ ticketId: null }))

    act(() => result.current.add([fileOf('fine.png'), fileOf('huge.zip', 11 * 1024 * 1024)]))

    let outcome!: { uploaded: number; failed: string[] }
    await act(async () => {
      outcome = await result.current.flush(ticketId)
    })

    expect(outcome.uploaded).toBe(1)
    // The *local* file name — the server never saw one, and the user needs to be
    // told which of their files to attach again.
    expect(outcome.failed).toEqual(['huge.zip'])
  })

  it('retries a previously failed file on a second flush', async () => {
    // The failed entry is kept rather than discarded, so a transient failure
    // does not quietly lose the file.
    failUploadNumber(1)
    const ticketId = inScopeTicketId()
    const { result } = renderHook(() => useTicketAttachments({ ticketId: null }))

    act(() => result.current.add([fileOf('shot.png')]))
    await act(async () => {
      await result.current.flush(ticketId)
    })
    expect(result.current.pendingCount).toBe(1)

    let second!: { uploaded: number; failed: string[] }
    await act(async () => {
      second = await result.current.flush(ticketId)
    })
    expect(second).toEqual({ uploaded: 1, failed: [] })
    await waitFor(() => expect(result.current.pendingCount).toBe(0))
  })

  it('reset clears staged files, so Save & Create Another does not re-upload them', () => {
    const { result } = renderHook(() => useTicketAttachments({ ticketId: null }))

    act(() => result.current.add([fileOf('shot.png')]))
    expect(result.current.pendingCount).toBe(1)

    act(() => result.current.reset())

    expect(result.current.pendingCount).toBe(0)
    expect(result.current.items).toHaveLength(0)
  })
})

describe('existing rows the surface already loaded', () => {
  const existing = [
    {
      id: 7,
      fileName: 'seeded.png',
      contentType: 'image/png',
      sizeBytes: 1024,
      scanStatus: 'CLEAN' as const,
      isDeleted: false,
    },
  ]

  it('merges them into the list and into attachmentIds', () => {
    const { result } = renderHook(() => useTicketAttachments({ ticketId: 'X-26-00001', existing }))
    expect(result.current.items).toHaveLength(1)
    expect(result.current.items[0].name).toBe('seeded.png')
    expect(result.current.attachmentIds).toEqual([7])
  })

  it('hides a soft-deleted row', () => {
    // §4B.4's delete after 15 minutes leaves a tombstone: the row survives so
    // the record of the file existing survives, but the file is gone.
    const { result } = renderHook(() =>
      useTicketAttachments({ ticketId: 'X-26-00001', existing: [{ ...existing[0], isDeleted: true }] }),
    )
    expect(result.current.items).toHaveLength(0)
  })

  it('marks an infected file failed rather than offering it', () => {
    const { result } = renderHook(() =>
      useTicketAttachments({
        ticketId: 'X-26-00001',
        existing: [{ ...existing[0], scanStatus: 'INFECTED' as const }],
      }),
    )
    expect(result.current.items[0].status).toBe('failed')
    expect(result.current.items[0].error).toMatch(/virus/i)
  })

  it('shows a scanning row for anything still PENDING', () => {
    const { result } = renderHook(() =>
      useTicketAttachments({
        ticketId: 'X-26-00001',
        existing: [{ ...existing[0], scanStatus: 'PENDING' as const }],
      }),
    )
    expect(result.current.items[0].status).toBe('scanning')
  })

  it('removes a server row optimistically and keeps it out of attachmentIds', async () => {
    const ticketId = inScopeTicketId()
    const { result } = renderHook(() => useTicketAttachments({ ticketId, existing }))

    act(() => result.current.remove('7'))

    await waitFor(() => expect(result.current.items).toHaveLength(0))
    expect(result.current.attachmentIds).toEqual([])
  })
})

/**
 * C-028 · what the hook does with a removal — §4B.4's deletion rule.
 *
 * Two behaviours the surfaces depend on and neither of which existed before:
 * tombstones arrive separately from live files, and a refusal is reported rather
 * than swallowed. The second is the one that changed character: until §4B.4's
 * rule was enforced a delete could only fail on a network fault, and silently
 * putting the row back was the whole of the right answer.
 */
describe('C-028 · removals', () => {
  const attachment = (overrides: Record<string, unknown> = {}) =>
    ({
      id: 1,
      fileName: 'gateway-500.png',
      contentType: 'image/png',
      sizeBytes: 1024,
      scanStatus: 'CLEAN',
      downloadUrl: 'https://minio.example/a?sig=x',
      isDeleted: false,
      ...overrides,
    }) as never

  it('keeps tombstones out of items and offers them separately', () => {
    // A tombstone is not a file in any sense the picker cares about — it cannot
    // be previewed, downloaded or counted against §4B.4's caps — so it must not
    // arrive in the array the picker validates.
    const { result } = renderHook(() =>
      useTicketAttachments({
        ticketId: 'CRM-26-00347',
        existing: [
          attachment(),
          attachment({ id: 2, fileName: 'debug-log.txt', isDeleted: true, deletedAt: '2026-08-16T14:22:00Z' }),
        ],
      }),
    )

    expect(result.current.items.map((i) => i.name)).toEqual(['gateway-500.png'])
    expect(result.current.tombstones.map((t) => t.fileName)).toEqual(['debug-log.txt'])
  })

  it('does not count a tombstone towards the ids sent with a comment or handoff', () => {
    // `attachmentIds` rides on QuickUpdateRequest, CommentWriteRequest and
    // HandoffRequest. Naming a removed file there would ask the server to attach
    // something whose bytes are gone.
    const { result } = renderHook(() =>
      useTicketAttachments({
        ticketId: 'CRM-26-00347',
        existing: [attachment(), attachment({ id: 2, isDeleted: true })],
      }),
    )

    expect(result.current.attachmentIds).toEqual([1])
  })

  it('puts the row back and says why when the server refuses', async () => {
    server.use(
      http.delete('*/tickets/:ticketId/attachments/:attachmentId', () =>
        HttpResponse.json(
          {
            type: 'https://edutrack/errors/attachment-delete-refused',
            title: 'That attachment cannot be removed',
            detail: 'Only the person who attached this file can remove it in the first few minutes.',
            status: 403,
          },
          { status: 403, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )

    const { result } = renderHook(() =>
      useTicketAttachments({ ticketId: 'CRM-26-00347', existing: [attachment()] }),
    )

    act(() => result.current.remove('1'))

    // Both halves matter. The row coming back without an explanation reads as a
    // broken button, and the user's response to that is to click it again.
    await waitFor(() => expect(result.current.removeError).toContain('person who attached this file'))
    expect(result.current.items.map((i) => i.name)).toEqual(['gateway-500.png'])
  })

  it('clears a previous refusal when another removal is tried', async () => {
    let refuse = true
    server.use(
      http.delete('*/tickets/:ticketId/attachments/:attachmentId', () =>
        refuse
          ? HttpResponse.json(
              { type: 'x', title: 'no', detail: 'Not yours to remove.', status: 403 },
              { status: 403, headers: { 'Content-Type': 'application/problem+json' } },
            )
          : new HttpResponse(null, { status: 204 }),
      ),
    )

    const { result } = renderHook(() =>
      useTicketAttachments({ ticketId: 'CRM-26-00347', existing: [attachment(), attachment({ id: 2 })] }),
    )

    act(() => result.current.remove('1'))
    await waitFor(() => expect(result.current.removeError).toBe('Not yours to remove.'))

    // A stale message beside a removal that worked is worse than none — it
    // describes the previous click.
    refuse = false
    act(() => result.current.remove('2'))
    await waitFor(() => expect(result.current.removeError).toBeNull())
  })
})
