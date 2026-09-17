import { describe, expect, it } from 'vitest'

import type { Me } from '@/api/generated/model/me'

import { isMineOnly, obViewerScope } from './viewerScope'

/**
 * Every row of the role table, including the two that are easy to get wrong:
 * a session whose module role has not arrived, and an implementor whose id has
 * not either.
 */

const ME = 41

function me(role: string | undefined): Me {
  return {
    id: ME,
    displayName: 'Priya Nair',
    username: 'priya.nair',
    email: 'priya.nair@example.com',
    permissions: [],
    projectIds: [],
    reporteeIds: [],
    timezone: 'Asia/Kolkata',
    modules: ['ONBOARDING'],
    moduleRoles: role ? { ONBOARDING: role } : {},
  }
}

describe('obViewerScope', () => {
  it('filters the page to an implementor’s own work', () => {
    const scope = obViewerScope(me('OB_STEP_OWNER'))

    expect(scope.kind).toBe('IMPLEMENTOR')
    expect(isMineOnly(scope)).toBe(true)
    expect(scope.meId).toBe(ME)
  })

  it('gives an admin the whole project, with the per-implementor accordion', () => {
    const scope = obViewerScope(me('OB_ADMIN'))

    expect(scope.kind).toBe('ALL')
    expect(scope.showsBreakdown).toBe(true)
  })

  it('gives a manager the same reading as an admin', () => {
    expect(obViewerScope(me('OB_MANAGER'))).toMatchObject({ kind: 'ALL', showsBreakdown: true })
  })

  /**
   * A salesperson chasing a project needs every Step. The workload split is a
   * management reading and has a screen of its own in the dashboard.
   */
  it.each(['OB_SALES', 'OB_VIEWER'])('gives %s every Step and no breakdown', (role) => {
    expect(obViewerScope(me(role))).toMatchObject({ kind: 'ALL', showsBreakdown: false })
  })

  /**
   * `moduleRoles` is advisory and up to fifteen minutes stale, and a platform
   * ADMIN holding no onboarding role is an ordinary session. The unfiltered
   * view is the safe direction: it can only ever show a reader more of a
   * project they were already served.
   */
  it('gives a session with no onboarding role the unfiltered view', () => {
    expect(obViewerScope(me(undefined))).toMatchObject({
      kind: 'ALL',
      showsBreakdown: false,
    })
  })

  it('gives the unfiltered view while /me is still in flight', () => {
    const scope = obViewerScope(undefined)

    expect(scope.kind).toBe('ALL')
    expect(scope.meId).toBeNull()
  })

  /**
   * Filtering to nobody would empty the page. The unfiltered view is the honest
   * intermediate state and corrects itself on the next render, rather than
   * flashing an empty ribbon at somebody who owns half the project.
   */
  it('does not filter an implementor whose id has not arrived', () => {
    const noId = { ...me('OB_STEP_OWNER'), id: undefined } as unknown as Me

    expect(obViewerScope(noId).kind).toBe('ALL')
  })

  /**
   * **Show all Steps** used to widen this from `IMPLEMENTOR` to `ALL`, and it
   * is gone: the project page's switch chooses between a reader's outstanding
   * tasks and all of *their* tasks, never between their work and everybody's.
   * Who the rows belong to is settled here and by nothing else on the screen.
   */
  it('has no widening control — an implementor is always filtered to their own work', () => {
    expect(obViewerScope(me('OB_STEP_OWNER')).kind).toBe('IMPLEMENTOR')
    expect(obViewerScope(me('OB_STEP_OWNER'))).not.toHaveProperty('canShowAll')
  })
})
