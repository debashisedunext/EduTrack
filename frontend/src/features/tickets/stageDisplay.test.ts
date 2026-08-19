import { describe, expect, it } from 'vitest'

import { titleCase } from './stageDisplay'

describe('titleCase', () => {
  it('title-cases the ordinary stage codes', () => {
    expect(titleCase('DEVELOPMENT')).toBe('Development')
    expect(titleCase('DEV')).toBe('Dev')
    expect(titleCase('SIGNOFF')).toBe('Signoff')
  })

  it('keeps QA an acronym rather than rendering it as Qa', () => {
    // `QA` is a real stage code in the seeded vocabulary, and five components
    // call this — My Tasks, Quick Update, History, Attachments and Journey all
    // rendered "Qa" before this was fixed.
    expect(titleCase('QA')).toBe('QA')
  })

  it('handles multi-segment codes, acronym or not', () => {
    expect(titleCase('CODE_REVIEW')).toBe('Code Review')
    expect(titleCase('QA_REVIEW')).toBe('QA Review')
  })

  it('is indifferent to the case it is given', () => {
    expect(titleCase('qa')).toBe('QA')
    expect(titleCase('development')).toBe('Development')
  })
})
