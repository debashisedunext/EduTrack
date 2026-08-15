import { describe, expect, it } from 'vitest'
import {
  CHART_PALETTE,
  emptyTaskTypeForm,
  taskTypeFormErrors,
  toFormValues,
  toPatchRequest,
  toWriteRequest,
  type TaskTypeFormValues,
} from './taskTypeForm'

/**
 * B-020 · the rules that are worth testing without rendering anything.
 *
 * The two mappers are where a quiet mistake shows up as a save that looked like
 * it worked and did not — which is the failure mode B-016 named when it took
 * `allowedTaskTypeIds` off the project write request rather than accepting it
 * and dropping it.
 */
const valid: TaskTypeFormValues = {
  ...emptyTaskTypeForm,
  code: 'DATA_FIX',
  name: 'Data Fix',
  icon: 'database',
  colour: CHART_PALETTE[2],
  defaultLevel: 'HIGH',
  defaultSlaHrs: '24',
  seq: '120',
}

describe('taskTypeFormErrors', () => {
  it('accepts a complete form', () => {
    expect(taskTypeFormErrors(valid)).toEqual({})
  })

  it('refuses a code with a hyphen', () => {
    expect(taskTypeFormErrors({ ...valid, code: 'DATA-FIX' }).code).toBeDefined()
  })

  it('refuses a code starting with a digit', () => {
    expect(taskTypeFormErrors({ ...valid, code: '2FA_ISSUE' }).code).toBeDefined()
  })

  it('refuses a colour outside the palette shape', () => {
    expect(taskTypeFormErrors({ ...valid, colour: 'cornflowerblue' }).colour).toBeDefined()
  })

  it('every palette swatch is a #RRGGBB token', () => {
    // CLAUDE.md: never introduce a colour that is not a blueprint §12.1 token.
    // The server only checks the shape, because it has no palette — so if this
    // list drifted, nothing else would catch it.
    expect(CHART_PALETTE).toHaveLength(8)
    for (const colour of CHART_PALETTE) {
      expect(colour).toMatch(/^#[0-9A-F]{6}$/)
    }
  })

  it('accepts a blank default SLA — no default is a real state', () => {
    // The type then falls through to the priority default on the §6 ladder.
    expect(taskTypeFormErrors({ ...valid, defaultSlaHrs: '' })).toEqual({})
  })

  it('refuses a negative default SLA', () => {
    expect(taskTypeFormErrors({ ...valid, defaultSlaHrs: '-1' }).defaultSlaHrs).toBeDefined()
  })

  it('accepts a zero order — it is a position, not an absence', () => {
    expect(taskTypeFormErrors({ ...valid, seq: '0' })).toEqual({})
  })

  it('refuses an order beyond what the SMALLINT column holds', () => {
    // (short) 40000 is negative, and a type that silently sorted to the front
    // of every picker is a display bug nobody traces back to this save.
    expect(taskTypeFormErrors({ ...valid, seq: '40000' }).seq).toBeDefined()
  })

  it('refuses a fractional order', () => {
    expect(taskTypeFormErrors({ ...valid, seq: '1.5' }).seq).toBeDefined()
  })
})

describe('toWriteRequest', () => {
  it('upper-cases the code, as the server will', () => {
    expect(toWriteRequest({ ...valid, code: 'data_fix' }).code).toBe('DATA_FIX')
  })

  it('sends a blank icon and SLA as null, not as empty strings', () => {
    const request = toWriteRequest({ ...valid, icon: '  ', defaultSlaHrs: '' })

    expect(request.icon).toBeNull()
    expect(request.defaultSlaHrs).toBeNull()
  })

  it('sends a blank order as null, which the server reads as "add at the end"', () => {
    expect(toWriteRequest({ ...valid, seq: '' }).seq).toBeNull()
  })

  it('sends zero as zero, not as null', () => {
    expect(toWriteRequest({ ...valid, seq: '0' }).seq).toBe(0)
    expect(toWriteRequest({ ...valid, defaultSlaHrs: '0' }).defaultSlaHrs).toBe(0)
  })
})

describe('toPatchRequest', () => {
  it('sends the code back, so a changed one can be refused', () => {
    // Resending the stored code is a deliberate no-op on the server. Omitting
    // it would mean a caller who believed they had renamed it is told the save
    // succeeded — RolePatch carries `code` for exactly this reason.
    expect(toPatchRequest(valid).code).toBe('DATA_FIX')
  })

  it('sends explicit nulls to clear the icon and the default SLA', () => {
    // Absent and null mean different things to the server's patch DTO. Omitting
    // these would make clearing them impossible from this screen.
    const request = toPatchRequest({ ...valid, icon: '', defaultSlaHrs: '' })

    expect(request).toHaveProperty('icon', null)
    expect(request).toHaveProperty('defaultSlaHrs', null)
  })

  it('carries isActive, which is how a type is retired', () => {
    expect(toPatchRequest({ ...valid, isActive: false }).isActive).toBe(false)
  })
})

describe('toFormValues', () => {
  it('renders a null SLA as blank rather than as "null"', () => {
    const values = toFormValues({
      id: 1, code: 'OTHER', name: 'Other', icon: null, colour: '#10B981',
      defaultLevel: 'LOW', defaultSlaHrs: null, seq: 110, isActive: true, ticketCount: 0,
    })

    expect(values.defaultSlaHrs).toBe('')
    expect(values.icon).toBe('')
  })

  it('round-trips a stored row through the patch mapper unchanged', () => {
    // The edit dialog submits the whole form on every save, so anything this
    // pair loses is silently dropped from the row on the first unrelated edit.
    const stored = {
      id: 2, code: 'PRODUCTION_BUG', name: 'Production Bug', icon: 'flame',
      colour: '#06B6D4', defaultLevel: 'HIGH' as const, defaultSlaHrs: 8,
      seq: 20, isActive: true, ticketCount: 31,
    }

    expect(toPatchRequest(toFormValues(stored))).toEqual({
      code: 'PRODUCTION_BUG',
      name: 'Production Bug',
      icon: 'flame',
      colour: '#06B6D4',
      defaultLevel: 'HIGH',
      defaultSlaHrs: 8,
      seq: 20,
      isActive: true,
    })
  })
})
