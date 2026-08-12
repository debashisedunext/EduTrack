import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { uploadTicketAttachment } from './uploadTicketAttachment'

/**
 * The regression guard for the multipart header defect.
 *
 * `fetch` is mocked rather than driven through MSW, deliberately and for the
 * same reason `api/http.test.ts` mocks it: what is being asserted is the shape
 * of the *outgoing request*, and that is invisible once a handler has answered.
 *
 * It cannot be asserted through MSW here anyway. Under vitest's jsdom
 * environment `FormData`, `Blob` and `File` are jsdom's while `fetch` and
 * `Request` are Node's, and Node refuses to serialise a foreign `FormData` —
 * it stringifies it to the literal `[object FormData]` under
 * `Content-Type: text/plain`. Every attempt to bridge that realm gap fails on
 * undici's internal brand checks, which reject `node:buffer`'s `Blob` and the
 * one off `Response.blob()` alike. So the multipart *round trip* is a real
 * browser's to prove; the header contract is provable right here, and it is the
 * part that actually regressed.
 */

let fetchMock: ReturnType<typeof vi.fn>

beforeEach(() => {
  fetchMock = vi.fn(
    async () =>
      new Response(JSON.stringify({ data: { id: 1, fileName: 'a.png', scanStatus: 'PENDING' } }), {
        status: 201,
        headers: { 'content-type': 'application/json' },
      }),
  )
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => vi.unstubAllGlobals())

function lastInit(): RequestInit {
  return fetchMock.mock.calls[0][1] as RequestInit
}

describe('uploadTicketAttachment', () => {
  it('sends no Content-Type at all, so the browser can add the boundary', async () => {
    // ⚠ This is the whole reason this wrapper exists. The generated
    // `uploadAttachment` pins `Content-Type: multipart/form-data`, and
    // `api/http.ts` spreads caller headers *after* its FormData branch, so that
    // header wins. A multipart body without a `boundary` parameter is
    // unparseable — Spring answers 400 or 500 for every upload.
    await uploadTicketAttachment('CRM-26-00347', { file: new File(['x'], 'a.png', { type: 'image/png' }) })

    const headers = lastInit().headers as Record<string, string>
    expect(Object.keys(headers).map((k) => k.toLowerCase())).not.toContain('content-type')
  })

  it('posts a FormData body carrying the file under the contract’s field name', async () => {
    const file = new File(['x'], 'gateway-500.png', { type: 'image/png' })
    await uploadTicketAttachment('CRM-26-00347', { file })

    const body = lastInit().body as FormData
    expect(body).toBeInstanceOf(FormData)
    expect(body.get('file')).toBe(file)
  })

  it('hits the contract’s path for the given ticket', async () => {
    await uploadTicketAttachment('CRM-26-00347', { file: new File(['x'], 'a.png') })
    expect(String(fetchMock.mock.calls[0][0])).toContain('/tickets/CRM-26-00347/attachments')
    expect(lastInit().method).toBe('POST')
  })

  it('omits the optional parts rather than sending them empty', async () => {
    // `isClientVisible` has a server-side default of false and `commentId` is
    // meaningless outside the comment box. Sending either blank is a claim the
    // caller did not make.
    await uploadTicketAttachment('CRM-26-00347', { file: new File(['x'], 'a.png') })

    const body = lastInit().body as FormData
    expect(body.has('isClientVisible')).toBe(false)
    expect(body.has('commentId')).toBe(false)
  })

  it('sends isClientVisible when the caller states it, including false', async () => {
    // §4B.4's per-attachment flag. An explicit `false` is a decision — the
    // comment box's "internal" default — and is not the same as silence.
    await uploadTicketAttachment('CRM-26-00347', {
      file: new File(['x'], 'a.png'),
      isClientVisible: false,
    })
    expect((lastInit().body as FormData).get('isClientVisible')).toBe('false')
  })

  it('carries commentId, which is how C-029 will bind a file to its comment', async () => {
    await uploadTicketAttachment('CRM-26-00347', { file: new File(['x'], 'a.png'), commentId: 42 })
    expect((lastInit().body as FormData).get('commentId')).toBe('42')
  })
})
