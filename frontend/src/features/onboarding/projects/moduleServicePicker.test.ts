import { describe, expect, it } from 'vitest'
import type { ObJourneyTemplateSummary } from '@/api/generated/model/obJourneyTemplateSummary'

import { activeServicesOf, categoryOf } from './moduleServicePicker'

function service(over: Partial<ObJourneyTemplateSummary> = {}): ObJourneyTemplateSummary {
  return {
    id: 1,
    productId: 1,
    name: 'Core ERP Setup',
    version: 1,
    isActive: true,
    sequence: 1,
    dependsOnTemplateIds: [],
    publishedAt: '2026-08-01T00:00:00.000Z',
    stepCount: 11,
    totalTatDays: 18,
    serviceJourneyCount: 0,
    stages: [],
    ...over,
  } as ObJourneyTemplateSummary
}

describe('activeServicesOf', () => {
  it('offers nothing until a product is chosen', () => {
    expect(activeServicesOf([service()], null)).toHaveLength(0)
  })

  it('offers only the active version of each service', () => {
    // The catalogue read returns every version, because OB-07 draws version
    // history. Boarding a client onto a retired one would pin them to a
    // template the admin has already replaced.
    const rows = [
      service({ id: 1, version: 1, isActive: false }),
      service({ id: 2, version: 2, isActive: true }),
    ]
    expect(activeServicesOf(rows, 1).map((s) => s.id)).toEqual([2])
  })

  it('offers nothing from a service that has never been published', () => {
    // Drafts only. The server refuses these anyway; the form says "publish one
    // first", which is the actionable fact.
    expect(activeServicesOf([service({ isActive: false })], 1)).toHaveLength(0)
  })

  it('drops rows belonging to another product', () => {
    // The list is fetched with ?productId=, but React Query serves the previous
    // product's data for one render while the new request is in flight — and a
    // picker that briefly lists the wrong services is one somebody can check a
    // row in.
    const rows = [service({ id: 1, productId: 1 }), service({ id: 2, productId: 2 })]
    expect(activeServicesOf(rows, 1).map((s) => s.id)).toEqual([1])
  })

  it('orders by catalogue sequence, breaking ties on id', () => {
    const rows = [
      service({ id: 3, sequence: 2 }),
      service({ id: 1, sequence: 1 }),
      service({ id: 2, sequence: 1 }),
    ]
    expect(activeServicesOf(rows, 1).map((s) => s.id)).toEqual([1, 2, 3])
  })
})

describe('categoryOf', () => {
  it('lists the stages in the designer order', () => {
    const row = service({
      stages: [
        { id: 1, name: 'Kickoff', sequence: 1, implementationStageId: 1 },
        { id: 2, name: 'Configuration', sequence: 2, implementationStageId: 2 },
      ],
    })
    expect(categoryOf(row)).toEqual(['Kickoff', 'Configuration'])
  })

  it('folds two groups that carry the same name', () => {
    // A group's name is copied from the master at creation — the copy is what
    // stops a rename next year re-labelling a template published this year — so
    // two groups can legitimately read the same. Two identical chips in a
    // Category column are noise.
    const row = service({
      stages: [
        { id: 1, name: 'Configuration', sequence: 1, implementationStageId: 2 },
        { id: 2, name: 'Configuration', sequence: 2, implementationStageId: 9 },
      ],
    })
    expect(categoryOf(row)).toEqual(['Configuration'])
  })

  it('shows Ungrouped rather than hiding it', () => {
    // A real bucket — tasks written before the stage master existed — and a
    // service carrying one is a service somebody should tidy. Hiding it would
    // make the column disagree with the task list underneath it.
    const row = service({
      stages: [{ id: 1, name: 'Ungrouped', sequence: 9999, implementationStageId: null }],
    })
    expect(categoryOf(row)).toEqual(['Ungrouped'])
  })

  it('answers an empty list for a service with no stages', () => {
    expect(categoryOf(service({ stages: undefined }))).toEqual([])
  })
})
