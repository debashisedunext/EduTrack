import { describe, expect, it } from 'vitest'

import { codeFromName } from './productCode'

describe('codeFromName', () => {
  it('upper-cases and collapses everything that is not a letter or digit', () => {
    expect(codeFromName('Biometric Attendance')).toBe('BIOMETRIC_ATTENDANCE')
    expect(codeFromName('HR & Payroll')).toBe('HR_PAYROLL')
    expect(codeFromName('  lms  ')).toBe('LMS')
  })

  it('is deterministic, so a duplicate name is a duplicate code', () => {
    expect(codeFromName('EduTrack ERP')).toBe(codeFromName('edutrack erp'))
  })

  it('stays within the 32-character limit without a trailing underscore', () => {
    const code = codeFromName('A very long product name that keeps going on and on')
    expect(code.length).toBeLessThanOrEqual(32)
    expect(code).toMatch(/^[A-Z0-9_-]+$/)
    expect(code.endsWith('_')).toBe(false)
  })

  it('still produces a valid code for a name with nothing to slug', () => {
    expect(codeFromName('विद्यालय')).toMatch(/^[A-Z0-9_-]+$/)
  })
})
