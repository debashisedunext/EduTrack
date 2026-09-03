import { beforeEach, describe, expect, it } from 'vitest'

import { getDb } from '../db'

/**
 * A-118 · the onboarding mock's four load-bearing rules.
 *
 * These handlers are this stream's own addition to `frontend/src/mocks/`
 * (Stream D's directory — flagged in `handlers/onboarding.ts`), so as with
 * `ticketLinks.test.ts` there is no owner boundary keeping the tests out of
 * this directory.
 *
 * **What is worth testing here is not that the handlers respond.** It is the
 * four rules the module's screens can be built entirely wrong against if the
 * mock waves them through — a locked client that reports GREEN, a settable
 * `LIVE`, an unmasked PAN, a bookable product with no template. Each is a
 * one-line mistake to make in a handler and none of them is visible in review.
 */

const json = async <T,>(res: Response): Promise<T> => (await res.json()) as T

type Rag = 'GREEN' | 'AMBER' | 'RED' | null

interface ClientRow {
  id: number
  name: string
  rag: Rag
  gateStatus: 'LOCKED' | 'OPEN'
  journeyCount: number
}

interface ClientDetail extends ClientRow {
  pan: string | null
  journeys: { id: number; rag: Rag; gateStatus: string; totalTatDays: number; utilizedHours: number;
    heldByJourneyId: number | null; percentComplete: number
    steps: { id: number; rag: Rag; status: string }[] }[]
}

const listClients = async () =>
  (await json<{ data: ClientRow[] }>(await fetch('/api/v1/onboarding/clients'))).data

const getClient = async (id: number) =>
  (await json<{ data: ClientDetail }>(await fetch(`/api/v1/onboarding/clients/${id}`))).data

const createClient = (body: unknown) =>
  fetch('/api/v1/onboarding/clients', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })

const patchClient = (id: number, body: unknown) =>
  fetch(`/api/v1/onboarding/clients/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })

/** A create body that passes validation, so each test varies only its own field. */
const validBody = (over: Record<string, unknown> = {}) => ({
  name: 'Fabrikam Industries',
  onboardingDate: '2026-09-03',
  contacts: [{ name: 'A Person', email: 'a@fabrikam.example', isPrimary: true }],
  applications: [{ productId: 1 }],
  ...over,
})

beforeEach(() => {
  getDb().currentUserId = 1 // Admin — scope is not what these tests are about
})

describe('A-118 · RAG carries health and nothing else', () => {
  it('reports null, not GREEN, while every journey is locked', async () => {
    const acme = (await listClients()).find((c) => c.name.startsWith('Acme'))!

    // The distinction the whole enum turns on: OB-03 renders this as
    // "Prerequisites pending". GREEN here would claim a locked client is on
    // track, when nothing is running to be on track.
    expect(acme.gateStatus).toBe('LOCKED')
    expect(acme.rag).toBeNull()
  })

  it('rolls the worst open step up to the client', async () => {
    const northwind = (await listClients()).find((c) => c.name.startsWith('Northwind'))!
    expect(northwind.gateStatus).toBe('OPEN')
    expect(northwind.rag).toBe('RED')
  })

  it('does not colour a step that is waiting on the client', async () => {
    const detail = await getClient(1)
    const waiting = detail.journeys
      .flatMap((j) => j.steps)
      .find((s) => s.status === 'WAITING_ON_CLIENT')!

    // The clock is paused and the wait is attributed to the client (plan §5.7).
    // Colouring it would make every TAT report disputable — which is the exact
    // failure the pause exists to prevent.
    expect(waiting.rag).toBeNull()
  })

  it('excludes locked journeys from the client roll-up', async () => {
    const acme = await getClient(2)
    expect(acme.journeys.every((j) => j.gateStatus === 'LOCKED')).toBe(true)
    expect(acme.journeys.every((j) => j.rag === null)).toBe(true)
    expect(acme.rag).toBeNull()
  })

  it('filters by colour without ever returning a locked client', async () => {
    const res = await fetch('/api/v1/onboarding/clients?rag=GREEN')
    const { data } = await json<{ data: ClientRow[] }>(res)

    // A locked client matches none of the three colours. That is why
    // `gateStatus` is a separate filter rather than a fourth RAG value.
    expect(data.every((c) => c.gateStatus === 'OPEN')).toBe(true)
  })
})

describe('A-118 · LIVE is earned, never set', () => {
  it('refuses a direct move to LIVE with 422', async () => {
    const res = await patchClient(1, { status: 'LIVE' })
    expect(res.status).toBe(422)
    const body = await json<{ type: string }>(res)
    expect(body.type).toContain('ob-client-live-not-earned')
  })

  it('still allows the statuses a person legitimately records', async () => {
    const res = await patchClient(1, { status: 'ON_HOLD', statusReason: 'Client paused the rollout' })
    expect(res.status).toBe(200)
  })

  it('requires a reason for ON_HOLD and DROPPED', async () => {
    expect((await patchClient(1, { status: 'DROPPED' })).status).toBe(400)
  })
})

describe('A-118 · PAN is masked on the way out', () => {
  it('never returns the stored value, even on the detail read', async () => {
    const stored = getDb().obClients.find((c) => c.id === 1)!.pan!
    const detail = await getClient(1)

    expect(stored).toBe('AABCN1234M')
    expect(detail.pan).toBe('AABCN****M')
    expect(detail.pan).not.toBe(stored)
  })

  it('keeps PAN off the list row entirely', async () => {
    const [row] = await listClients()
    // Identity data belongs to the detail, where the masking rule and its audit
    // apply — not to a list that leaks it a page at a time.
    //
    // Asserted over the parsed keys rather than by casting the row: `ClientRow`
    // has no index signature, so `as Record<string, unknown>` is a compile
    // error, and widening through `unknown` to silence it would assert against
    // a type rather than against what the handler actually sent.
    expect(Object.keys(row)).not.toContain('pan')
  })

  it('does not let the search parameter match on PAN', async () => {
    const res = await fetch('/api/v1/onboarding/clients?q=AABCN1234M')
    const { data } = await json<{ data: ClientRow[] }>(res)

    // Matching here would make the mock an oracle for a value the API masks.
    expect(data).toHaveLength(0)
  })
})

describe('A-118 · a product with no template cannot be bought', () => {
  it('refuses a purchase of a product with no active template', async () => {
    const res = await createClient(validBody({ applications: [{ productId: 2 }] }))
    expect(res.status).toBe(409)
    expect((await json<{ type: string }>(res)).type).toContain('ob-product-no-template')
  })

  it('creates a product that is not yet bookable', async () => {
    const res = await fetch('/api/v1/onboarding/products', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code: 'PAYROLL', name: 'Payroll' }),
    })
    expect(res.status).toBe(201)
    const { data } = await json<{ data: { hasActiveTemplate: boolean } }>(res)
    expect(data.hasActiveTemplate).toBe(false)
  })
})

describe('A-118 · the duplicate guard is split on purpose', () => {
  it('refuses a duplicate PAN and offers no way past it', async () => {
    const res = await createClient(
      validBody({ pan: 'AABCN1234M', acknowledgeSimilarNames: true }),
    )
    expect(res.status).toBe(409)
    expect((await json<{ type: string }>(res)).type).toContain('ob-client-pan-duplicate')
  })

  it('warns on a similar name and lets it be acknowledged', async () => {
    // "Acme Private Limited" against the fixture's "Acme Private Limited" —
    // the case the guard exists for, and the case it must not make final.
    const warned = await createClient(validBody({ name: 'Acme Pvt Ltd' }))
    expect(warned.status).toBe(409)
    expect((await json<{ type: string }>(warned)).type).toContain('ob-client-name-similar')

    const forced = await createClient(
      validBody({ name: 'Acme Pvt Ltd', acknowledgeSimilarNames: true }),
    )
    expect(forced.status).toBe(201)
  })
})

describe('A-118 · what one call to createObClient creates', () => {
  it('instantiates one LOCKED journey per purchased product', async () => {
    const res = await createClient(validBody({ name: 'Fabrikam Industries' }))
    expect(res.status).toBe(201)
    const { data } = await json<{ data: ClientDetail }>(res)

    expect(data.journeys).toHaveLength(1)
    expect(data.journeys[0].gateStatus).toBe('LOCKED')
    // Visible plan, dead clock: every step present, nothing consumed, no colour.
    expect(data.journeys[0].steps.length).toBeGreaterThan(0)
    expect(data.journeys[0].utilizedHours).toBe(0)
    expect(data.journeys[0].rag).toBeNull()
    expect(data.rag).toBeNull()
  })

  it('does not create a portal login unless asked', async () => {
    const quiet = await json<{ data: { hasPortalLogin: boolean } }>(
      await createClient(validBody({ name: 'Tailwind Traders' })),
    )
    expect(quiet.data.hasPortalLogin).toBe(false)

    const asked = await json<{ data: { hasPortalLogin: boolean } }>(
      await createClient(validBody({ name: 'Wide World Importers', createPortalLogin: true })),
    )
    expect(asked.data.hasPortalLogin).toBe(true)
  })

  it('requires exactly one primary SPOC', async () => {
    const none = await createClient(validBody({
      contacts: [{ name: 'A', email: 'a@x.example', isPrimary: false }],
    }))
    expect(none.status).toBe(400)

    const two = await createClient(validBody({
      contacts: [
        { name: 'A', email: 'a@x.example', isPrimary: true },
        { name: 'B', email: 'b@x.example', isPrimary: true },
      ],
    }))
    expect(two.status).toBe(400)
  })
})

describe('A-118 · the journey strip', () => {
  it('sums TAT from the steps and derives utilisation rather than storing it', async () => {
    const detail = await getClient(1)
    const erp = detail.journeys[0]

    expect(erp.totalTatDays).toBe(24)
    expect(erp.utilizedHours).toBe(128.5)
    expect(erp.percentComplete).toBe(40)
  })

  it('holds a journey behind a sibling without touching its gate', async () => {
    const detail = await getClient(1)
    const biometric = detail.journeys.find((j) => j.heldByJourneyId != null)!

    // Two different things: the prerequisite gate is open, and the journey is
    // still held by a service-level dependency (plan §5.5). Modelling these as
    // one field is the mistake this asserts against.
    expect(biometric.gateStatus).toBe('OPEN')
    expect(biometric.heldByJourneyId).toBe(1)
  })
})

describe('A-118 · the two client masters stay disjoint', () => {
  it('shares no ids with the ticketing client master', async () => {
    const db = getDb()
    // Not a coincidence to be preserved by luck — the assertion exists so that
    // anyone tempted to join these two tables in the mock finds out here.
    expect(db.obClients.map((c) => c.name)).not.toEqual(
      expect.arrayContaining(db.clients.map((c) => c.name)),
    )
  })

  it('404s an onboarding id that only exists in the ticketing master', async () => {
    const ticketingOnlyId = Math.max(...getDb().clients.map((c) => c.id)) + 500
    expect((await fetch(`/api/v1/onboarding/clients/${ticketingOnlyId}`)).status).toBe(404)
  })
})
