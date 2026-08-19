import { describe, expect, it } from 'vitest'
import type { Module } from '@/api/generated/model/module'

import { isUnchanged, moduleName, moduleOptions, normalizeRichText, normalizeText } from './whereItHappened'

const MODULES: Module[] = [
  { id: 1, code: 'STUDENT', name: 'Student', isActive: true },
  { id: 3, code: 'FEES', name: 'Fees', isActive: true },
  { id: 9, code: 'TRANSPORT', name: 'Transport', isActive: false },
]

describe('moduleName', () => {
  it('names a retired module, which is the whole reason the endpoint returns them', () => {
    // A ticket raised last year against Transport still has to show "Transport".
    // Filtering to active rows here leaves the cell blank, which reads as
    // missing data rather than as a retirement.
    expect(moduleName(MODULES, 9)).toBe('Transport')
  })

  it('gives nothing to render rather than a raw id when the master cannot answer', () => {
    expect(moduleName(MODULES, null)).toBeUndefined()
    expect(moduleName(MODULES, 404)).toBeUndefined()
    expect(moduleName(undefined, 3)).toBeUndefined()
  })
})

describe('moduleOptions', () => {
  it('offers the active rows and keeps this ticket’s retired one', () => {
    // The case the create form's flat filter cannot cover: a ticket already on
    // Transport, opened to fix a typo in the screen name, would find its Module
    // trigger empty — and saving would look like it had dropped the module.
    expect(moduleOptions(MODULES, 9).map((m) => m.name)).toEqual(['Student', 'Fees', 'Transport'])
  })

  it('does not offer a retired module to a ticket that is not already on one', () => {
    // Off, never onto. `ModuleGuard` refuses a deactivated module on write, so
    // offering one here would be offering a 400.
    expect(moduleOptions(MODULES, 3).map((m) => m.name)).toEqual(['Student', 'Fees'])
    expect(moduleOptions(MODULES, null).map((m) => m.name)).toEqual(['Student', 'Fees'])
  })
})

describe('normalizeText', () => {
  it('turns an emptied field into null so it can actually be cleared', () => {
    // `''` would store a second, indistinguishable kind of blank alongside NULL,
    // and every report downstream would have to know about both.
    expect(normalizeText('')).toBeNull()
    expect(normalizeText('   ')).toBeNull()
    expect(normalizeText('  Fee Receipt Print  ')).toBe('Fee Receipt Print')
  })
})

describe('normalizeRichText', () => {
  it('sanitises on the way out — §3.9 applies to whatever the client handles', () => {
    expect(normalizeRichText('<p>Open Fees<script>alert(1)</script></p>')).toBe('<p>Open Fees</p>')
  })

  it('treats an editor that was focused and left empty as nothing', () => {
    // 13 characters of nothing. A truthiness check reads it as present and the
    // detail page then renders an empty Steps block for every ticket whose
    // author clicked into the field and thought better of it.
    expect(normalizeRichText('<p><br></p>')).toBeNull()
    expect(normalizeRichText('')).toBeNull()
  })
})

describe('isUnchanged', () => {
  it('treats a missing value and an explicit null as the same state', () => {
    expect(isUnchanged(undefined, null)).toBe(true)
    expect(isUnchanged(null, null)).toBe(true)
  })

  it('is what stops a no-op edit writing an irreversible history row', () => {
    expect(isUnchanged('Fees', 'Fees')).toBe(true)
    expect(isUnchanged('Fees', 'Admission')).toBe(false)
    expect(isUnchanged('Fees', null)).toBe(false)
  })
})
