import { describe, expect, it } from 'vitest'

import { validateNewProject, type NewProjectDraft } from './newProjectForm'

/** A draft that would be accepted, so each test can spoil exactly one thing. */
function draft(over: Partial<NewProjectDraft> = {}): NewProjectDraft {
  return {
    name: 'Dataport rollout — Sunrise Trust',
    clientId: 12,
    productId: 3,
    startDate: '2026-09-21',
    salesPersonId: 41,
    implementorUserId: 42,
    implementorManagerUserId: 43,
    serviceCount: 4,
    selectedCount: 4,
    servicesPending: false,
    ...over,
  }
}

describe('validateNewProject', () => {
  it('accepts a complete draft', () => {
    expect(validateNewProject(draft())).toEqual({})
  })

  /**
   * The implementor is what an ownerless task falls to, so a project without
   * one boards a client onto journeys whose unpinned tasks belong to nobody.
   */
  it('refuses a draft with no implementor', () => {
    const errors = validateNewProject(draft({ implementorUserId: null }))

    expect(errors.implementorUserId).toMatch(/Choose the implementor/)
  })

  it('refuses a draft with no sales person', () => {
    const errors = validateNewProject(draft({ salesPersonId: null }))

    expect(errors.salesPersonId).toMatch(/sales person/)
  })

  /**
   * Required for the sales person's reason rather than the implementor's:
   * nothing falls back to the manager, but an engagement with no recorded
   * escalation path is one somebody has to reconstruct at the worst moment.
   */
  it('refuses a draft with no implementor manager', () => {
    const errors = validateNewProject(draft({ implementorManagerUserId: null }))

    expect(errors.implementorManagerUserId).toMatch(/implementor manager/)
  })

  /**
   * The manager is a field of its own, not the implementor read a second way.
   * A draft naming an implementor and no manager is incomplete — which is the
   * whole point of adding the field rather than deriving it from a reporting
   * line.
   */
  it('does not accept the implementor standing in for their manager', () => {
    const errors = validateNewProject(
      draft({ implementorUserId: 42, implementorManagerUserId: null }),
    )

    expect(errors.implementorUserId).toBeUndefined()
    expect(errors.implementorManagerUserId).toBeDefined()
  })

  /**
   * The field says "leave blank to name it after the client", so blank with a
   * client chosen is how it is meant to be used — the page sends that client's
   * name. Blank with no client still fails, because nothing would name it.
   */
  it('accepts a blank name once a client is chosen', () => {
    expect(validateNewProject(draft({ name: '   ' })).name).toBeUndefined()
  })

  it('refuses a blank name when no client is chosen either', () => {
    const errors = validateNewProject(draft({ name: '', clientId: null }))

    expect(errors.name).toMatch(/choose the client to name it after/)
  })

  it('names every empty field at once rather than one at a time', () => {
    const errors = validateNewProject(
      draft({
        name: '   ',
        clientId: null,
        salesPersonId: null,
        implementorUserId: null,
        implementorManagerUserId: null,
      }),
    )

    expect(Object.keys(errors).sort()).toEqual([
      'clientId',
      'implementorManagerUserId',
      'implementorUserId',
      'name',
      'salesPersonId',
    ])
  })

  /**
   * A product that publishes nothing has nothing to board the client through,
   * and the message goes on the product rather than on the service list — the
   * list is empty because of what was chosen above it.
   */
  it('refuses a product with no active module service', () => {
    const errors = validateNewProject(draft({ serviceCount: 0, selectedCount: 0 }))

    expect(errors.productId).toMatch(/publishes no active module service/)
    expect(errors.moduleServiceIds).toBeUndefined()
  })

  /** An empty list mid-read means "not loaded", not "this product has none". */
  it('waits for the catalogue rather than refusing while it loads', () => {
    const errors = validateNewProject(
      draft({ serviceCount: 0, selectedCount: 0, servicesPending: true }),
    )

    expect(errors).toEqual({})
  })

  it('refuses a draft with every module service unpicked', () => {
    const errors = validateNewProject(draft({ selectedCount: 0 }))

    expect(errors.moduleServiceIds).toMatch(/at least one module service/)
  })

  it('does not ask for services before a product is chosen', () => {
    const errors = validateNewProject(draft({ productId: null, serviceCount: 0, selectedCount: 0 }))

    expect(errors.productId).toBe('Choose the product bought.')
    expect(errors.moduleServiceIds).toBeUndefined()
  })
})
