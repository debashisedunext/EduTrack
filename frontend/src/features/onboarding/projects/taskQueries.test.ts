import { QueryClient } from '@tanstack/react-query'
import { describe, expect, it } from 'vitest'

import { invalidateAfterTaskWrite } from './taskQueries'

/**
 * Every screen a task write is read back on, asserted by seeding a cache entry
 * for each and checking the write marked it stale. A spy on
 * `invalidateQueries` would pass on a call that matched nothing — which is
 * exactly the failure mode `obDashboardFreshness.ts` documents for the
 * dashboard keys — so this drives a real `QueryClient` instead.
 */

const JOURNEY_ID = 12
const TASK_ID = 1841

/** Seeded so the key exists to be invalidated; the value is never read. */
const ANY = { data: [] }

function invalidated(client: QueryClient, key: readonly unknown[]) {
  return client.getQueryState(key)?.isInvalidated === true
}

function seeded(keys: readonly (readonly unknown[])[]) {
  const client = new QueryClient()
  for (const key of keys) client.setQueryData(key, ANY)
  return client
}

const JOURNEY_KEY = [`/onboarding/journeys/${JOURNEY_ID}`] as const
const MY_TASKS_KEY = ['/onboarding/my-tasks', { limit: 200 }] as const
const MY_TASK_KEY = [`/onboarding/my-tasks/${TASK_ID}`] as const
const REVIEW_SUMMARY_KEY = ['/onboarding/dashboard/review-summary'] as const
const DASHBOARD_SUMMARY_KEY = ['/onboarding/dashboard/summary'] as const
const CLIENTS_KEY = ['/onboarding/clients', { limit: 50 }] as const

describe('invalidateAfterTaskWrite', () => {
  it('re-reads the journey and both My Tasks shapes', () => {
    const client = seeded([JOURNEY_KEY, MY_TASKS_KEY, MY_TASK_KEY])

    invalidateAfterTaskWrite(client, JOURNEY_ID, TASK_ID)

    expect(invalidated(client, JOURNEY_KEY)).toBe(true)
    expect(invalidated(client, MY_TASKS_KEY)).toBe(true)
    expect(invalidated(client, MY_TASK_KEY)).toBe(true)
  })

  it('re-reads the dashboard, so a verification moves the review cards', () => {
    /*
      The reported bug: approving a check-list row left "Your verifications"
      serving the answer it had cached before the press. The card's key sits
      under the dashboard prefix, so what has to hold is that this function
      reaches it at all.
    */
    const client = seeded([REVIEW_SUMMARY_KEY, DASHBOARD_SUMMARY_KEY, CLIENTS_KEY])

    invalidateAfterTaskWrite(client, JOURNEY_ID, TASK_ID)

    expect(invalidated(client, REVIEW_SUMMARY_KEY)).toBe(true)
    expect(invalidated(client, DASHBOARD_SUMMARY_KEY)).toBe(true)
    expect(invalidated(client, CLIENTS_KEY)).toBe(true)
  })

  it('leaves one task’s own page alone when no task is named', () => {
    const client = seeded([MY_TASKS_KEY, MY_TASK_KEY, REVIEW_SUMMARY_KEY])

    invalidateAfterTaskWrite(client, JOURNEY_ID)

    /*
      The two My Tasks shapes do not share a first key element, so the list
      prefix does not reach the focused page — which is the point of the
      optional argument: a caller that moved several rows does not claim to
      know which one. The queue and the board still move without it.
    */
    expect(invalidated(client, MY_TASK_KEY)).toBe(false)
    expect(invalidated(client, MY_TASKS_KEY)).toBe(true)
    expect(invalidated(client, REVIEW_SUMMARY_KEY)).toBe(true)
  })
})
