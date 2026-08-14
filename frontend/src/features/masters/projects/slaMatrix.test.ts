import { describe, expect, it } from 'vitest'

import type { SlaPolicyCell } from '@/api/generated/model/slaPolicyCell'

import {
  buildOverrides,
  cellKey,
  draftFor,
  draftsFor,
  groupByTaskType,
  isDirty,
  parseHours,
  sourceLabel,
  sourceVariant,
  summarise,
  validate,
} from './slaMatrix'

/**
 * B-018 · the SLA tab's arithmetic, with no DOM.
 *
 * The one function here that can cause real damage is {@link buildOverrides},
 * and the damage is silent. If it returns an inherited cell, that cell becomes a
 * `sla_policies` row for the project and the project stops following the
 * default it was displayed as following — with nothing wrong on any screen until
 * somebody changes that default months later and this project does not move. So
 * most of this file is about which cells it does *not* return.
 */

const inherited = (over: Partial<SlaPolicyCell> = {}): SlaPolicyCell => ({
  taskTypeId: 2,
  taskTypeName: 'Production Bug',
  level: 'HIGH',
  responseHrs: 4,
  resolutionHrs: 16,
  escalateToL1: true,
  escalateToL2: false,
  source: 'ORG_DEFAULT',
  isOverride: false,
  ...over,
})

const override = (over: Partial<SlaPolicyCell> = {}): SlaPolicyCell =>
  inherited({ source: 'PROJECT_TASK_TYPE', isOverride: true, responseHrs: 1, resolutionHrs: 6, ...over })

const draftsWith = (cell: SlaPolicyCell, patch: Partial<ReturnType<typeof draftFor>>) => ({
  [cellKey(cell.taskTypeId, cell.level)]: { ...draftFor(cell), ...patch },
})

describe('buildOverrides', () => {
  it('does not send an inherited cell that was only looked at', () => {
    // The defect this whole function exists to prevent. The grid holds a figure
    // for every cell; sending them all back would materialise forty-four
    // project rows and silently detach the project from every default.
    const cells = [inherited()]

    expect(buildOverrides(cells, draftsFor(cells))).toEqual([])
  })

  it('does not treat 16 typed over 16 as a change', () => {
    // A cell somebody clicked into and out of. Compared as parsed numbers so
    // "16" and "16.0" are the same figure — string comparison would turn a
    // stray keystroke into a fresh override.
    const cell = inherited()

    expect(buildOverrides([cell], draftsWith(cell, { resolutionHrs: '16.0' }))).toEqual([])
  })

  it('sends an inherited cell the moment its figure is changed', () => {
    const cell = inherited()

    expect(buildOverrides([cell], draftsWith(cell, { resolutionHrs: '8' }))).toEqual([
      {
        taskTypeId: 2,
        level: 'HIGH',
        responseHrs: 4,
        resolutionHrs: 8,
        escalateToL1: true,
        escalateToL2: false,
      },
    ])
  })

  it('sends an inherited cell whose only change is an escalation flag', () => {
    // Ticking L2 on an inherited row is a decision about this project, and the
    // only place to record it is a row of this project's own.
    const cell = inherited()

    expect(buildOverrides([cell], draftsWith(cell, { escalateToL2: true }))).toHaveLength(1)
  })

  it('sends an untouched override unchanged — leaving it out would delete it', () => {
    // The PUT is a replace: a cell absent from the body goes back to
    // inheriting. An "only send what changed" body would clear every override
    // the user did not touch.
    const cell = override()

    expect(buildOverrides([cell], draftsFor([cell]))).toEqual([
      {
        taskTypeId: 2,
        level: 'HIGH',
        responseHrs: 1,
        resolutionHrs: 6,
        escalateToL1: true,
        escalateToL2: false,
      },
    ])
  })

  it('drops an override whose resolution box was emptied — that is how one is removed', () => {
    const cell = override()

    expect(buildOverrides([cell], draftsWith(cell, { resolutionHrs: '' }))).toEqual([])
  })

  it('sends a null response target rather than omitting the field', () => {
    // Null is a real value: a policy that only targets resolution is a real
    // one. Omitting the key would leave the server's own stored value in place
    // on a re-save, making the field write-once.
    const cell = override()

    expect(buildOverrides([cell], draftsWith(cell, { responseHrs: '' }))[0].responseHrs).toBeNull()
  })

  it('drops a cell with a resolution figure the server would refuse', () => {
    // Zero would be stored as a policy that reads as configured and behaves as
    // absent. validate() is what tells the user; this is what stops it
    // reaching the wire if the button were ever enabled by mistake.
    const cell = override()

    expect(buildOverrides([cell], draftsWith(cell, { resolutionHrs: '0' }))).toEqual([])
    expect(buildOverrides([cell], draftsWith(cell, { resolutionHrs: 'abc' }))).toEqual([])
  })
})

describe('validate', () => {
  it('passes an untouched grid', () => {
    const cells = [inherited(), override({ taskTypeId: 3 })]

    expect(validate(cells, draftsFor(cells))).toEqual([])
  })

  it('refuses a response target longer than the resolution target', () => {
    // Not a policy — a transposition. Nothing downstream would reject it: the
    // scanner would warn about a first response overdue after the ticket was
    // already due to close.
    const cell = override()

    expect(validate([cell], draftsWith(cell, { responseHrs: '9' })))
      .toEqual([{ key: '2/HIGH', message: 'The response target cannot be longer than the resolution target.' }])
  })

  it('allows equal response and resolution targets', () => {
    // "Respond and resolve within the hour" is a real commitment; the rule is
    // about ordering, not margin.
    const cell = override()

    expect(validate([cell], draftsWith(cell, { responseHrs: '6' }))).toEqual([])
  })

  it('refuses a resolution figure that is not a usable number', () => {
    const cell = override()

    expect(validate([cell], draftsWith(cell, { resolutionHrs: '0' }))).toHaveLength(1)
    expect(validate([cell], draftsWith(cell, { resolutionHrs: '99999' }))).toHaveLength(1)
  })

  it('does not refuse an emptied resolution box — that is a removal, not a mistake', () => {
    const cell = override()

    expect(validate([cell], draftsWith(cell, { resolutionHrs: '' }))).toEqual([])
  })

  it('ignores an inherited cell nobody touched, whatever it holds', () => {
    // Inherited figures come from the server and are not this screen's to
    // refuse. A grid that reported problems on cells the user never edited
    // would be unsaveable for reasons outside their control.
    const cells = [inherited({ responseHrs: 99, resolutionHrs: 16 })]

    expect(validate(cells, draftsFor(cells))).toEqual([])
  })
})

describe('isDirty', () => {
  it('is false for the draft a cell starts in', () => {
    const cell = override()

    expect(isDirty(cell, draftFor(cell))).toBe(false)
  })

  it('treats an emptied box as a change, and two empty boxes as no change', () => {
    // Emptying an override is the removal request, so it has to count as dirty
    // or the Save button would stay disabled and the cell would be unremovable.
    const withFigure = override()
    const withoutFigure = override({ responseHrs: null })

    expect(isDirty(withFigure, { ...draftFor(withFigure), responseHrs: '' })).toBe(true)
    expect(isDirty(withoutFigure, { ...draftFor(withoutFigure), responseHrs: '' })).toBe(false)
  })
})

describe('parseHours', () => {
  it('rejects the figures the DECIMAL(6,2) column and the SLA ladder cannot hold', () => {
    expect(parseHours('')).toBeNull()
    expect(parseHours('  ')).toBeNull()
    expect(parseHours('0')).toBeNull()
    expect(parseHours('-1')).toBeNull()
    expect(parseHours('10000')).toBeNull()
    expect(parseHours('abc')).toBeNull()
    expect(parseHours('Infinity')).toBeNull()
  })

  it('accepts a fractional figure — quarter-hour SLAs are real', () => {
    expect(parseHours('0.5')).toBe(0.5)
    expect(parseHours(' 9999.99 ')).toBe(9999.99)
  })
})

describe('groupByTaskType', () => {
  it('preserves the server’s order in both directions', () => {
    // The server orders by the masters' own seq, which is the order an
    // administrator configured them in. Re-sorting by name or id here would
    // discard that for an order that means nothing to anybody.
    const cells = [
      inherited({ taskTypeId: 5, taskTypeName: 'Internal Bug', level: 'LOW' }),
      inherited({ taskTypeId: 5, taskTypeName: 'Internal Bug', level: 'CRITICAL' }),
      inherited({ taskTypeId: 1, taskTypeName: 'Change Request', level: 'LOW' }),
    ]

    const groups = groupByTaskType(cells)

    expect(groups.map((g) => g.taskTypeName)).toEqual(['Internal Bug', 'Change Request'])
    expect(groups[0].cells.map((c) => c.level)).toEqual(['LOW', 'CRITICAL'])
  })
})

describe('summarise', () => {
  it('counts overrides after the pending edits, not before them', () => {
    // The sentence on screen has to describe what Save would produce, not what
    // was loaded — otherwise it contradicts the button beside it.
    const cell = inherited()

    expect(summarise([cell], draftsFor([cell]))).toMatchObject({ overrideCount: 0, dirtyCount: 0 })
    expect(summarise([cell], draftsWith(cell, { resolutionHrs: '8' })))
      .toMatchObject({ overrideCount: 1, dirtyCount: 1 })
  })

  it('counts the cells nothing in the product has a figure for', () => {
    // The one state with a consequence elsewhere: a ticket raised there gets no
    // planned close date and drops out of the breach sweep.
    const cells = [inherited(), inherited({ level: 'LOW', source: 'NONE', resolutionHrs: null })]

    expect(summarise(cells, draftsFor(cells)).unsetCount).toBe(1)
  })
})

describe('source presentation', () => {
  it('chips only the override and the nothing-at-all', () => {
    // Four coloured chips for four rungs would turn the one distinction this
    // screen exists to make into a palette.
    expect(sourceVariant('PROJECT_TASK_TYPE')).toBe('info')
    expect(sourceVariant('NONE')).toBe('warning')
    expect(sourceVariant('ORG_DEFAULT')).toBe('neutral')
    expect(sourceVariant('PROJECT_LEVEL')).toBe('neutral')
  })

  it('labels every source the contract can send', () => {
    // A missing label renders as "undefined" in a table cell, which is the sort
    // of thing a seventh enum value ships quietly.
    for (const source of [
      'PROJECT_TASK_TYPE', 'PROJECT_LEVEL', 'ORG_DEFAULT',
      'PRIORITY_DEFAULT', 'TASK_TYPE_DEFAULT', 'NONE',
    ] as const) {
      expect(sourceLabel(source)).toBeTruthy()
    }
  })
})
