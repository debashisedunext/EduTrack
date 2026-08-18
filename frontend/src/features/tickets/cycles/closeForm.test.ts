import { describe, expect, it } from 'vitest'

import { CLOSE_FORM_DEFAULTS, closeFormSchema, toCloseRequest } from './closeForm'

describe('closeFormSchema — C-040, S-23', () => {
  it('accepts a bare resolution summary, the smallest valid close', () => {
    const result = closeFormSchema.safeParse({
      ...CLOSE_FORM_DEFAULTS,
      resolutionSummary: 'Fixed',
    })

    expect(result.success).toBe(true)
  })

  it('rejects a resolution summary under 3 characters, matching CloseRequest.resolutionSummary', () => {
    const result = closeFormSchema.safeParse({ ...CLOSE_FORM_DEFAULTS, resolutionSummary: 'Ok' })

    expect(result.success).toBe(false)
  })

  it('rejects a blank resolution summary — the only mandatory field, per S-23', () => {
    const result = closeFormSchema.safeParse(CLOSE_FORM_DEFAULTS)

    expect(result.success).toBe(false)
  })
})

describe('toCloseRequest', () => {
  const base = { ...CLOSE_FORM_DEFAULTS, resolutionSummary: 'Root cause identified and deployed.' }

  it('sends only resolutionSummary when every optional field is blank', () => {
    expect(toCloseRequest(base)).toEqual({
      resolutionSummary: 'Root cause identified and deployed.',
    })
  })

  it('trims the resolution summary', () => {
    expect(toCloseRequest({ ...base, resolutionSummary: '  Fixed the race.  ' })).toEqual({
      resolutionSummary: 'Fixed the race.',
    })
  })

  it('omits, never nulls, a blank root cause category', () => {
    expect(toCloseRequest({ ...base, rootCauseCategory: '   ' })).not.toHaveProperty('rootCauseCategory')
  })

  it('carries a supplied root cause category', () => {
    expect(toCloseRequest({ ...base, rootCauseCategory: 'Configuration drift' })).toMatchObject({
      rootCauseCategory: 'Configuration drift',
    })
  })

  it('converts a datetime-local value to an ISO instant', () => {
    const request = toCloseRequest({ ...base, actualCloseDate: '2026-08-17T18:00' })

    expect(request.actualCloseDate).toBe(new Date('2026-08-17T18:00').toISOString())
  })

  it('omits actualCloseDate when blank — the contract defaults it to now', () => {
    expect(toCloseRequest(base)).not.toHaveProperty('actualCloseDate')
  })

  /**
   * `finalEffortHours: 0` is a genuine claim of zero confirmed hours — a
   * different fact from "not confirmed" — so an explicit zero must survive
   * the mapper rather than being treated the same as blank.
   */
  it('parses a numeric final effort figure, including zero', () => {
    expect(toCloseRequest({ ...base, finalEffortHours: '18.5' })).toMatchObject({ finalEffortHours: 18.5 })
    expect(toCloseRequest({ ...base, finalEffortHours: '0' })).toMatchObject({ finalEffortHours: 0 })
  })

  it('omits finalEffortHours when blank', () => {
    expect(toCloseRequest(base)).not.toHaveProperty('finalEffortHours')
  })

  it('omits requestClientVerification when false — never sent as an explicit false', () => {
    expect(toCloseRequest({ ...base, requestClientVerification: false })).not.toHaveProperty(
      'requestClientVerification',
    )
  })

  it('sends requestClientVerification: true when checked', () => {
    expect(toCloseRequest({ ...base, requestClientVerification: true })).toMatchObject({
      requestClientVerification: true,
    })
  })
})
