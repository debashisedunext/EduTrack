import { describe, expect, it } from 'vitest'
import type { Priority } from '@/api/generated/model/priority'
import {
  CONTRACT_LEVELS,
  LEVEL_CHIP_COLOURS,
  PRIORITY_PALETTE,
  emptyPriorityForm,
  priorityFormErrors,
  toFormValues,
  toPatchRequest,
  toWriteRequest,
  type PriorityFormValues,
} from './priorityForm'

/**
 * B-021 · the S-12 form's rules and its two mappers, without rendering
 * anything.
 *
 * The mappers are where a quiet mistake shows up as a save that silently did
 * nothing — and on this form one of them carries a rule that is genuinely
 * unobvious: `autoEscalates` is sent only when true.
 */

const high: Priority = {
  id: 3,
  level: 'HIGH',
  name: 'High',
  colour: '#F59E0B',
  defaultSlaHrs: 16,
  autoEscalates: false,
  seq: 30,
  isActive: true,
  ticketCount: 12,
  taskTypeCount: 3,
  slaPolicyCount: 1,
}

const values = (patch: Partial<PriorityFormValues> = {}): PriorityFormValues => ({
  ...emptyPriorityForm,
  name: 'High',
  colour: '#F59E0B',
  ...patch,
})

describe('the palette', () => {
  it('leads with §12.1’s four level chips, in severity order', () => {
    // These are the colours the blueprint states for exactly these four rows,
    // and what B-002 seeds. The MSW mock disagreed with them until B-021.
    expect(LEVEL_CHIP_COLOURS).toEqual(['#10B981', '#3B82F6', '#F59E0B', '#EF4444'])
    expect(PRIORITY_PALETTE.slice(0, 4)).toEqual(LEVEL_CHIP_COLOURS)
  })

  it('offers only §12.1 tokens — no free-text colour reaches the server', () => {
    expect(PRIORITY_PALETTE.every((c) => /^#[0-9A-F]{6}$/.test(c))).toBe(true)
  })
})

describe('the level vocabulary', () => {
  it('is the four the contract’s Level enum can carry, and no more', () => {
    // S-12 promises an Admin can add further levels without a release. This
    // array is why that is not yet true, and the test is what will fail on the
    // day the enum opens — pointing at the decision rather than letting a
    // screen discover it.
    expect(CONTRACT_LEVELS).toEqual(['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'])
  })
})

describe('toFormValues', () => {
  it('renders the stored row, with numbers as the strings an input gives back', () => {
    expect(toFormValues(high)).toEqual({
      level: 'HIGH',
      name: 'High',
      colour: '#F59E0B',
      defaultSlaHrs: '16',
      autoEscalates: false,
      seq: '30',
      isActive: true,
    })
  })

  it('renders a cleared default SLA as empty, not as "null"', () => {
    // Blank is a real state — the level contributes no rung 4 to the §6 ladder.
    expect(toFormValues({ ...high, defaultSlaHrs: null }).defaultSlaHrs).toBe('')
  })

  it('renders a zero default SLA as "0", not as empty', () => {
    // Zero and blank mean different things and must not collapse: one is "no
    // time at all", the other is "this level adds no default".
    expect(toFormValues({ ...high, defaultSlaHrs: 0 }).defaultSlaHrs).toBe('0')
  })
})

describe('validation', () => {
  it('accepts a well-formed level', () => {
    expect(priorityFormErrors(values())).toEqual({})
  })

  it('requires a name', () => {
    expect(priorityFormErrors(values({ name: '   ' })).name).toBeDefined()
  })

  it('bounds the name at the VARCHAR(40) it is stored in', () => {
    expect(priorityFormErrors(values({ name: 'x'.repeat(41) })).name).toBeDefined()
  })

  it('refuses a colour that is not a hex token', () => {
    expect(priorityFormErrors(values({ colour: 'amber' })).colour).toBeDefined()
  })

  it('accepts a blank default SLA and refuses a negative one', () => {
    expect(priorityFormErrors(values({ defaultSlaHrs: '' })).defaultSlaHrs).toBeUndefined()
    expect(priorityFormErrors(values({ defaultSlaHrs: '-1' })).defaultSlaHrs).toBeDefined()
    expect(priorityFormErrors(values({ defaultSlaHrs: 'soon' })).defaultSlaHrs).toBeDefined()
  })

  it('bounds seq to the SMALLINT it is stored in', () => {
    expect(priorityFormErrors(values({ seq: '0' })).seq).toBeUndefined()
    expect(priorityFormErrors(values({ seq: '32767' })).seq).toBeUndefined()
    expect(priorityFormErrors(values({ seq: '40000' })).seq).toBeDefined()
    expect(priorityFormErrors(values({ seq: '1.5' })).seq).toBeDefined()
  })

  /**
   * The rule the browser can usefully duplicate, because the combination is
   * visible on the form: §6 promotes an overdue ticket *to* this level, and a
   * level no picker offers is one nobody could then change.
   */
  it('refuses a retired level that is also the escalation target', () => {
    expect(
      priorityFormErrors(values({ isActive: false, autoEscalates: true })).autoEscalates,
    ).toBeDefined()
    expect(
      priorityFormErrors(values({ isActive: false, autoEscalates: false })).autoEscalates,
    ).toBeUndefined()
  })
})

describe('toWriteRequest', () => {
  it('sends a blank default SLA as null, not as an empty string or NaN', () => {
    expect(toWriteRequest(values({ defaultSlaHrs: '' })).defaultSlaHrs).toBeNull()
    expect(toWriteRequest(values({ defaultSlaHrs: '16' })).defaultSlaHrs).toBe(16)
  })

  it('sends a blank seq as null, so the server sorts the level to the end', () => {
    expect(toWriteRequest(values({ seq: '' })).seq).toBeNull()
    expect(toWriteRequest(values({ seq: '0' })).seq).toBe(0)
  })

  it('trims the name', () => {
    expect(toWriteRequest(values({ name: '  High  ' })).name).toBe('High')
  })
})

describe('toPatchRequest', () => {
  it('sends the whole form, level included, so a changed one can be refused', () => {
    // Resending the stored code is a deliberate no-op on the server. Sending it
    // is what makes a *changed* one refusable, which is the point: a caller who
    // believed they had renamed the code must not be told the save succeeded.
    expect(toPatchRequest(values({ level: 'HIGH' })).level).toBe('HIGH')
  })

  it('sends an explicit null to clear the default SLA', () => {
    // The distinction the server's patch DTO is a POJO in order to keep:
    // omitted means "leave alone", explicit null means "clear it".
    const patch = toPatchRequest(values({ defaultSlaHrs: '' }))
    expect('defaultSlaHrs' in patch).toBe(true)
    expect(patch.defaultSlaHrs).toBeNull()
  })

  /**
   * The unobvious one, and the reason this file exists.
   *
   * Sending `autoEscalates: false` on every save would make an ordinary rename
   * of the flagged level a 409 — the server refuses a clear, because clearing
   * the last flag leaves §6 with no target. Omitting it when false is what lets
   * every other field on that row still be edited.
   */
  it('omits autoEscalates when false, so a rename does not carry a refused clear', () => {
    expect('autoEscalates' in toPatchRequest(values({ autoEscalates: false }))).toBe(false)
  })

  it('sends autoEscalates only when it is being turned on', () => {
    expect(toPatchRequest(values({ autoEscalates: true })).autoEscalates).toBe(true)
  })
})
