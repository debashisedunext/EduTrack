import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { uploadImportFile } from './importQueries'

/**
 * B-032 · the regression guard for the multipart header defect, on this feature's
 * side of it.
 *
 * `fetch` is mocked rather than driven through MSW, for the reason
 * `uploadTicketAttachment.test.ts` gives at length and which applies identically
 * here: what is being asserted is the shape of the **outgoing request**, and
 * that is invisible once a handler has answered. It could not be asserted
 * through MSW anyway — under vitest, jsdom supplies `FormData` while Node
 * supplies `Request`, and Node stringifies the foreign object to the literal
 * `[object FormData]`, so no genuine multipart body exists to inspect. The round
 * trip is a real browser's to prove; the header contract is provable here, and
 * the header is the part that regresses.
 */

let fetchMock: ReturnType<typeof vi.fn>

beforeEach(() => {
  fetchMock = vi.fn(
    async () =>
      new Response(JSON.stringify({ data: { uploadId: 'u-1', sheets: ['Clients'] } }), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      }),
  )
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => vi.unstubAllGlobals())

function lastInit(): RequestInit {
  return fetchMock.mock.calls[0][1] as RequestInit
}

function lastUrl(): string {
  return String(fetchMock.mock.calls[0][0])
}

describe('uploadImportFile', () => {
  it('sends no Content-Type at all, so the browser can add the boundary', async () => {
    // ⚠ The whole reason this wrapper exists instead of the generated hook.
    // Orval pins `Content-Type: multipart/form-data`, `api/http.ts` spreads
    // caller headers after its FormData branch, and a multipart body without a
    // `boundary` parameter is unparseable — Spring's @RequestPart answers 400 or
    // 500 for every upload, in a way no test using MSW would notice.
    await uploadImportFile('clients', { file: new File(['x'], 'clients.xlsx') })

    const headers = lastInit().headers as Record<string, string>
    expect(Object.keys(headers).map((key) => key.toLowerCase())).not.toContain('content-type')
  })

  it('posts the file under the contract’s field name', async () => {
    const file = new File(['x'], 'clients.xlsx')
    await uploadImportFile('clients', { file })

    const body = lastInit().body as FormData
    expect(body).toBeInstanceOf(FormData)
    expect(body.get('file')).toBe(file)
  })

  it('hits the path for the schema it was given', async () => {
    await uploadImportFile('clients', { file: new File(['x'], 'clients.xlsx') })

    expect(lastUrl()).toContain('/imports/clients/upload')
    expect(lastInit().method).toBe('POST')
  })

  /**
   * A first upload takes the first sheet and supersedes nothing, so neither
   * parameter is a claim the caller made. `?sheet=undefined` would be — and the
   * server would refuse it as a sheet no workbook contains.
   */
  it('sends no query string on a first upload', async () => {
    await uploadImportFile('clients', { file: new File(['x'], 'clients.xlsx') })

    expect(lastUrl()).not.toContain('?')
  })

  /** The sheet selector: read that sheet, and release the upload it replaces. */
  it('carries the chosen sheet and the upload it supersedes', async () => {
    await uploadImportFile('clients', {
      file: new File(['x'], 'clients.xlsx'),
      sheet: 'Archive',
      replaces: '11111111-2222-3333-4444-555555555555',
    })

    expect(lastUrl()).toContain('sheet=Archive')
    expect(lastUrl()).toContain('replaces=11111111-2222-3333-4444-555555555555')
  })
})
