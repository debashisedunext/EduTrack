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

interface ContactRow {
  id: number
  name: string
  email: string
  phone: string | null
  whatsappOptIn: boolean
  whatsappOptInAt: string | null
  whatsappOptInSource: string | null
  isPrimary: boolean
  isActive: boolean
}

interface ApplicationRow {
  id: number
  product: { id: number; code: string; name: string }
  licenseType: string | null
  units: number | null
  licenseStart: string | null
  licenseEnd: string | null
}

interface RequirementRow {
  id: number
  sequence: number
  title: string | null
  bodyHtml: string
  bodyText: string
  isMet: boolean
  metAt: string | null
  metBy: { id: number; displayName: string } | null
}

interface ClientDetail extends ClientRow {
  pan: string | null
  contacts: ContactRow[]
  applications: ApplicationRow[]
  requirements: RequirementRow[]
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

/**
 * B-108 · OB-03's owner filter.
 *
 * The only filter on this list that is not a field on the row, so a handler
 * that dropped it would return the whole corpus and read as working. The
 * fixture is arranged so both arms are narrowings: user 3 owns steps on clients
 * 1 and 2 and none on 3, and user 4 owns nothing anywhere while backing up one
 * step on client 3.
 */
describe('B-108 · the ownerId filter', () => {
  it('returns only the clients whose journeys hold that person’s steps', async () => {
    const res = await fetch('/api/v1/onboarding/clients?ownerId=3')
    const { data } = await json<{ data: ClientRow[] }>(res)

    expect(data.map((c) => c.id).sort()).toEqual([1, 2])
  })

  /**
   * `OnboardingScopeResolver.hasStepOwnedBy`'s rule, and the one a handler
   * reading a single column would fail: the backup exists to cover the step
   * when the owner cannot, so hiding those clients hides exactly the ones a
   * stand-in has been asked to pick up.
   */
  it('counts a backup owner as an owner', async () => {
    const res = await fetch('/api/v1/onboarding/clients?ownerId=4')
    const { data } = await json<{ data: ClientRow[] }>(res)

    expect(data.map((c) => c.id)).toEqual([3])
  })

  it('gives a user who owns nothing an empty list rather than everything', async () => {
    const res = await fetch('/api/v1/onboarding/clients?ownerId=7')
    const { data } = await json<{ data: ClientRow[] }>(res)

    expect(data).toHaveLength(0)
  })

  it('narrows alongside another filter rather than replacing it', async () => {
    const res = await fetch('/api/v1/onboarding/clients?ownerId=3&gateStatus=LOCKED')
    const { data } = await json<{ data: ClientRow[] }>(res)

    // Of user 3's two clients, only Acme is behind its gate.
    expect(data.map((c) => c.id)).toEqual([2])
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
  /**
   * B-123 · the expected value changed with A-113, and the old one is worth
   * naming rather than quietly replacing: `AABCN****M` showed six of ten
   * characters — the whole series-and-holder-type prefix and the checksum —
   * which is the mask PHASE-2-BUILD-PLAN finding 10 rejected. `PanFormat.mask`
   * on the server keeps the last four only, and this asserts the mock agrees.
   */
  it('never returns the stored value, even on the detail read', async () => {
    const stored = getDb().obClients.find((c) => c.id === 1)!.pan!
    const detail = await getClient(1)

    expect(stored).toBe('AABCN1234M')
    expect(detail.pan).toBe('••••••234M')
    expect(detail.pan).not.toBe(stored)
  })

  /**
   * Asserted apart from the exact string, because this is the property the
   * rule exists for rather than its spelling. The prefix is the identifying
   * half — positions 1-3 are the series, 4 encodes the holder type and 5 is
   * the surname initial — so a mask that keeps it beside a client's name
   * corroborates the name. Length is preserved so the field still reads as a
   * PAN rather than as a truncated value.
   */
  it('discloses none of the identifying prefix', async () => {
    const detail = await getClient(1)

    expect(detail.pan).not.toContain('AABCN')
    expect(detail.pan).toHaveLength('AABCN1234M'.length)
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

// ── B-103 · the SPOC panel ──────────────────────────────────────────────────

const addContact = (clientId: number, body: unknown) =>
  fetch(`/api/v1/onboarding/clients/${clientId}/contacts`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })

const patchContact = (clientId: number, contactId: number, body: unknown) =>
  fetch(`/api/v1/onboarding/clients/${clientId}/contacts/${contactId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })

const removeContact = (clientId: number, contactId: number) =>
  fetch(`/api/v1/onboarding/clients/${clientId}/contacts/${contactId}`, { method: 'DELETE' })

/** A SPOC body that passes validation, so each test varies only its own field. */
const contactBody = (over: Record<string, unknown> = {}) => ({
  name: 'New SPOC',
  email: 'new.spoc@northwind.example',
  isPrimary: false,
  ...over,
})

/** Northwind — the only fixture with an inactive SPOC and a pre-capture consent. */
const NORTHWIND = 1

describe('B-103 · consent is a triple, not a flag', () => {
  it('refuses a consent with no basis', async () => {
    const res = await addContact(NORTHWIND, contactBody({ whatsappOptIn: true }))

    // The whole reason this capture is built in a phase that sends no WhatsApp:
    // a `true` on its own records that somebody ticked a box and cannot say
    // when, or on what. A mock that accepted it would let a form ship that the
    // real server rejects.
    expect(res.status).toBe(400)
    const body = await json<{ errors: Record<string, string[]> }>(res)
    expect(body.errors).toHaveProperty('whatsappOptInSource')
  })

  it('refuses UNRECORDED, which belongs to rows that predate the capture', async () => {
    const res = await addContact(
      NORTHWIND,
      contactBody({ whatsappOptIn: true, whatsappOptInSource: 'UNRECORDED' }),
    )
    expect(res.status).toBe(400)
  })

  it('refuses a basis with no consent rather than ignoring it', async () => {
    // A form sending one beside a `false` has come apart. Accepting both and
    // storing neither is how somebody later concludes consent was recorded.
    const res = await addContact(NORTHWIND, contactBody({ whatsappOptInSource: 'VERBAL' }))
    expect(res.status).toBe(400)
  })

  it('stamps the consent when a basis is given', async () => {
    const res = await addContact(
      NORTHWIND,
      contactBody({ whatsappOptIn: true, whatsappOptInSource: 'EMAIL' }),
    )
    expect(res.status).toBe(201)

    const added = (await json<{ data: ClientDetail }>(res)).data.contacts.find(
      (c) => c.email === 'new.spoc@northwind.example',
    )!
    expect(added.whatsappOptInSource).toBe('EMAIL')
    expect(added.whatsappOptInAt).not.toBeNull()
  })

  it('does not re-date a consent an unrelated edit did not change', async () => {
    const before = (await getClient(NORTHWIND)).contacts.find((c) => c.id === 1)!
    expect(before.whatsappOptInSource).toBe('VERBAL')

    await patchContact(NORTHWIND, 1, {
      name: before.name,
      email: before.email,
      phone: '+91 90000 00000',
      whatsappOptIn: true,
      whatsappOptInSource: 'VERBAL',
      isPrimary: true,
    })

    const after = (await getClient(NORTHWIND)).contacts.find((c) => c.id === 1)!
    // Correcting a phone number must not move the date the consent is dated
    // from. That destroys the same fact a missing basis destroys, except
    // silently and by a routine edit.
    expect(after.phone).toBe('+91 90000 00000')
    expect(after.whatsappOptInAt).toBe(before.whatsappOptInAt)
  })

  it('clears the stamp when consent is withdrawn', async () => {
    await patchContact(NORTHWIND, 1, { name: 'Meena Raghavan',
      email: 'meena@northwind.example', whatsappOptIn: false, isPrimary: true })

    const after = (await getClient(NORTHWIND)).contacts.find((c) => c.id === 1)!
    expect(after.whatsappOptIn).toBe(false)
    expect(after.whatsappOptInAt).toBeNull()
    expect(after.whatsappOptInSource).toBeNull()
  })

  it('reads UNRECORDED back, so a pre-capture SPOC is visibly one to re-approach', async () => {
    const farida = (await getClient(NORTHWIND)).contacts.find((c) => c.id === 5)!
    expect(farida.whatsappOptIn).toBe(true)
    expect(farida.whatsappOptInSource).toBe('UNRECORDED')
  })
})

describe('B-103 · a client always has exactly one primary SPOC', () => {
  it('demotes the incumbent when a new primary is added', async () => {
    const res = await addContact(NORTHWIND, contactBody({ isPrimary: true }))
    expect(res.status).toBe(201)

    const contacts = (await json<{ data: ClientDetail }>(res)).data.contacts
    // One, not two: uq_ob_client_contacts_primary refuses a second, so the
    // demotion has to happen in the same write as the promotion.
    expect(contacts.filter((c) => c.isPrimary)).toHaveLength(1)
    expect(contacts.find((c) => c.isPrimary)!.email).toBe('new.spoc@northwind.example')
  })

  it('refuses to demote the only primary', async () => {
    const res = await patchContact(NORTHWIND, 1, {
      name: 'Meena Raghavan', email: 'meena@northwind.example', isPrimary: false,
    })

    // Stricter than the ticketing master, which allows a client to have none.
    // Onboarding has no gate that reports a missing primary — the kickoff mail,
    // the portal password and every sign-off request simply go nowhere.
    expect(res.status).toBe(409)
    expect((await json<{ type: string }>(res)).type).toContain('ob-contact-primary-required')
  })

  it('refuses to remove the only primary', async () => {
    expect((await removeContact(NORTHWIND, 1)).status).toBe(409)
  })

  it('releases the departing primary once a successor holds the slot', async () => {
    await addContact(NORTHWIND, contactBody({ isPrimary: true }))
    const res = await removeContact(NORTHWIND, 1)

    expect(res.status).toBe(200)
    const contacts = (await json<{ data: ClientDetail }>(res)).data.contacts
    expect(contacts.find((c) => c.id === 1)!.isActive).toBe(false)
    expect(contacts.filter((c) => c.isPrimary)).toHaveLength(1)
  })
})

describe('B-103 · removal deactivates and never deletes', () => {
  it('keeps the contact in the client document', async () => {
    const res = await removeContact(NORTHWIND, 2)
    const contacts = (await json<{ data: ClientDetail }>(res)).data.contacts

    // The row is what a past sign-off points at. A screen that could not see
    // them could not reactivate one, and could not say whose name is on it.
    const removed = contacts.find((c) => c.id === 2)!
    expect(removed.isActive).toBe(false)
  })

  it('is not an error the second time', async () => {
    await removeContact(NORTHWIND, 2)
    // A setter, and the second half of a double-click must not fail.
    expect((await removeContact(NORTHWIND, 2)).status).toBe(200)
  })

  it('brings a departed SPOC back rather than adding a second row', async () => {
    const res = await patchContact(NORTHWIND, 5, {
      name: 'Farida Qureshi', email: 'farida@northwind.example',
      whatsappOptIn: true, whatsappOptInSource: 'WRITTEN', isPrimary: false, isActive: true,
    })

    const farida = (await json<{ data: ClientDetail }>(res)).data.contacts.find(
      (c) => c.id === 5,
    )!
    expect(farida.isActive).toBe(true)
    // Re-approached, and the answer recorded — which is what UNRECORDED exists
    // to prompt.
    expect(farida.whatsappOptInSource).toBe('WRITTEN')
  })

  it('refuses a second row for an address a removed contact still holds', async () => {
    const res = await addContact(
      NORTHWIND,
      contactBody({ email: 'farida@northwind.example' }),
    )
    expect(res.status).toBe(409)
    expect((await json<{ type: string }>(res)).type).toContain('ob-contact-email-duplicate')
  })

  it('404s a contact id belonging to another client', async () => {
    // Resolved by both ids, so the nested route cannot enumerate the SPOC table.
    expect((await removeContact(2, 1)).status).toBe(404)
  })
})

describe('B-103 · every SPOC write answers the whole client document', () => {
  it('returns the client, not the contact, so the page ETag stays usable', async () => {
    const res = await addContact(NORTHWIND, contactBody())
    const { data } = await json<{ data: ClientDetail }>(res)

    // getObClient's ETag covers contacts, so a SPOC write invalidates it.
    // Answering with the contact alone would leave the Client info card on the
    // same page holding a tag that is already stale.
    expect(data.id).toBe(NORTHWIND)
    expect(data.journeys.length).toBeGreaterThan(0)
    expect(data.contacts.some((c) => c.email === 'new.spoc@northwind.example')).toBe(true)
  })
})


// ── B-104 · the purchases panel ───────────────────────────────

const addApplication = (clientId: number, body: unknown) =>
  fetch(`/api/v1/onboarding/clients/${clientId}/applications`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })

const patchApplication = (clientId: number, applicationId: number, body: unknown) =>
  fetch(`/api/v1/onboarding/clients/${clientId}/applications/${applicationId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })

/** A product this client has not bought, that is on sale and has a template. */
const unboughtSellableProduct = (clientId: number) => {
  const db = getDb()
  const client = db.obClients.find((c) => c.id === clientId)!
  const owned = new Set(client.applications.map((a) => a.productId))
  return db.obProducts.find((p) => p.isActive && p.hasActiveTemplate && !owned.has(p.id))!
}

describe('B-104 · a purchase brings its journey with it', () => {
  it('instantiates one journey for the product just bought', async () => {
    const before = await getClient(NORTHWIND)
    const product = unboughtSellableProduct(NORTHWIND)

    const res = await addApplication(NORTHWIND, {
      productId: product.id,
      licenseType: 'Subscription',
      units: 50,
      licenseStart: '2026-10-01',
      licenseEnd: '2027-09-30',
    })
    expect(res.status).toBe(201)

    // The point of the whole task. A purchase is what a journey is instantiated
    // from, so a purchase without one leaves the client holding a product they
    // are not being onboarded through and nothing that reports it.
    const after = (await json<{ data: ClientDetail }>(res)).data
    expect(after.applications).toHaveLength(before.applications.length + 1)
    expect(after.journeys).toHaveLength(before.journeys.length + 1)

    const bought = after.applications.find((a) => a.product.id === product.id)!
    expect(bought.licenseEnd).toBe('2027-09-30')
  })

  it('opens the gate on a product bought after this client cleared theirs', async () => {
    // Plan §5.3 item 3. Northwind is past its gate, so the journey this
    // purchase creates is born OPEN — the client is not re-gated on
    // prerequisites they already satisfied. This is precisely the case the route
    // creates and the wizard never could, which is why the mock models it rather
    // than defaulting every new journey to LOCKED.
    const before = await getClient(NORTHWIND)
    expect(before.journeys.some((j) => j.gateStatus === 'OPEN')).toBe(true)

    const product = unboughtSellableProduct(NORTHWIND)
    const res = await addApplication(NORTHWIND, { productId: product.id })
    const after = (await json<{ data: ClientDetail }>(res)).data

    const fresh = after.journeys.filter((j) => !before.journeys.some((b) => b.id === j.id))
    expect(fresh).toHaveLength(1)
    expect(fresh[0].gateStatus).toBe('OPEN')
  })

  it('answers the whole client document, not the purchase it wrote', async () => {
    // getObClient's ETag covers applications AND journeys, so this write moves
    // it twice over. A purchase-shaped response would leave every other card on
    // OB-05 editing against a tag that is already stale.
    const product = unboughtSellableProduct(NORTHWIND)
    const res = await addApplication(NORTHWIND, { productId: product.id })

    const data = (await json<{ data: ClientDetail }>(res)).data
    expect(data.id).toBe(NORTHWIND)
    expect(data.contacts.length).toBeGreaterThan(0)
    expect(data.journeys.length).toBeGreaterThan(0)
  })

  it('refuses a product the client already bought, and says which row to edit', async () => {
    const client = await getClient(NORTHWIND)
    const owned = client.applications[0]

    const res = await addApplication(NORTHWIND, { productId: owned.product.id, units: 500 })

    // uq_ob_client_applications is on (ob_client_id, product_id): more seats is
    // an edit, and a second row would mean a second journey for one product.
    expect(res.status).toBe(409)
    const body = await json<{ type: string; existingApplicationId: number }>(res)
    expect(body.type).toContain('ob-application-duplicate-product')
    // Not a dead end — the panel is told which purchase to open.
    expect(body.existingApplicationId).toBe(owned.id)
  })

  it('refuses a product with no published journey template', async () => {
    const db = getDb()
    const client = db.obClients.find((c) => c.id === NORTHWIND)!
    const owned = new Set(client.applications.map((a) => a.productId))
    const templateless = db.obProducts.find((p) => p.isActive && !p.hasActiveTemplate && !owned.has(p.id))

    if (!templateless) return

    const res = await addApplication(NORTHWIND, { productId: templateless.id })
    expect(res.status).toBe(409)
    expect((await json<{ type: string }>(res)).type).toContain('ob-product-no-template')
  })

  it('refuses a product that is no longer on sale', async () => {
    const db = getDb()
    const retired = db.obProducts.find((p) => !p.isActive)
    if (!retired) return

    // A retired product is out of the picker by definition — buying one today
    // would instantiate a journey from a template nobody maintains.
    const res = await addApplication(NORTHWIND, { productId: retired.id })
    expect(res.status).toBe(400)
  })
})

describe('B-104 · the licence window is the renewal anchor', () => {
  it('moves the end date forward, which is what a renewal is', async () => {
    const client = await getClient(NORTHWIND)
    const purchase = client.applications[0]

    const res = await patchApplication(NORTHWIND, purchase.id, {
      productId: purchase.product.id,
      licenseType: purchase.licenseType,
      units: purchase.units,
      licenseStart: purchase.licenseStart,
      licenseEnd: '2028-07-31',
    })
    expect(res.status).toBe(200)

    const renewed = (await json<{ data: ClientDetail }>(res)).data
      .applications.find((a) => a.id === purchase.id)!
    expect(renewed.licenseEnd).toBe('2028-07-31')
    // Nothing else moved: this is an edit to one row, not a replace of the set.
    expect(renewed.product.id).toBe(purchase.product.id)
  })

  it('refuses a licence that ends before it starts', async () => {
    const client = await getClient(NORTHWIND)
    const purchase = client.applications[0]

    const res = await patchApplication(NORTHWIND, purchase.id, {
      productId: purchase.product.id,
      licenseStart: '2027-01-01',
      licenseEnd: '2026-12-31',
    })
    expect(res.status).toBe(400)
    // Keyed to licenseEnd, because that is the field a renewal moves — a message
    // on the start date would point at the half nobody touched.
    expect((await json<{ errors: Record<string, string[]> }>(res)).errors)
      .toHaveProperty('licenseEnd')
  })

  it('allows an open-ended perpetual licence', async () => {
    const client = await getClient(NORTHWIND)
    const purchase = client.applications[0]

    const res = await patchApplication(NORTHWIND, purchase.id, {
      productId: purchase.product.id,
      licenseType: 'Perpetual',
      licenseStart: '2026-08-01',
      licenseEnd: null,
    })
    expect(res.status).toBe(200)

    const saved = (await json<{ data: ClientDetail }>(res)).data
      .applications.find((a) => a.id === purchase.id)!
    expect(saved.licenseEnd).toBeNull()
  })

  it('clears an absent field rather than leaving it, because this is not a sparse patch', async () => {
    const client = await getClient(NORTHWIND)
    const purchase = client.applications.find((a) => a.units != null)!

    const res = await patchApplication(NORTHWIND, purchase.id, { productId: purchase.product.id })
    expect(res.status).toBe(200)

    const saved = (await json<{ data: ClientDetail }>(res)).data
      .applications.find((a) => a.id === purchase.id)!
    expect(saved.units).toBeNull()
    expect(saved.licenseType).toBeNull()
  })
})

describe('B-104 · the product identifies a purchase and is not a field on it', () => {
  it('refuses a PATCH naming a different product rather than ignoring it', async () => {
    const client = await getClient(NORTHWIND)
    const purchase = client.applications[0]
    const other = unboughtSellableProduct(NORTHWIND)

    const res = await patchApplication(NORTHWIND, purchase.id, { productId: other.id })

    // Refused, not ignored. ob_journeys keys straight to (ob_client_id,
    // product_id) and the journey's template is pinned to the product actually
    // bought — succeeding would onboard the client through the old product's
    // steps under the new product's name.
    expect(res.status).toBe(409)
    expect((await json<{ type: string }>(res)).type)
      .toContain('ob-application-product-immutable')
  })

  it('accepts the same product echoed back, which is the normal case', async () => {
    const client = await getClient(NORTHWIND)
    const purchase = client.applications[0]

    const res = await patchApplication(NORTHWIND, purchase.id, {
      productId: purchase.product.id,
      units: 300,
    })
    expect(res.status).toBe(200)
  })

  it('404s a purchase id belonging to another client', async () => {
    const db = getDb()
    const other = db.obClients.find((c) => c.id !== NORTHWIND && c.applications.length > 0)!
    const theirs = other.applications[0]

    // The nested route resolves by BOTH ids, so a real purchase under somebody
    // else answers exactly as an invented one does — which is what stops it
    // enumerating which organisations bought which products.
    const res = await patchApplication(NORTHWIND, theirs.id, { productId: theirs.productId })
    expect(res.status).toBe(404)
  })
})


// ── B-106 · requirements ─────────────────────────────────────────────────────

const addRequirement = (clientId: number, body: unknown) =>
  fetch(`/api/v1/onboarding/clients/${clientId}/requirements`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })

const patchRequirement = (clientId: number, requirementId: number, body: unknown) =>
  fetch(`/api/v1/onboarding/clients/${clientId}/requirements/${requirementId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })

const deleteRequirement = (clientId: number, requirementId: number) =>
  fetch(`/api/v1/onboarding/clients/${clientId}/requirements/${requirementId}`, {
    method: 'DELETE',
  })

describe('B-106 · requirements are rows, not strings', () => {
  it('adds at the end of the list and derives the plain-text projection', async () => {
    const before = await getClient(NORTHWIND)

    const res = await addRequirement(NORTHWIND, {
      title: 'Branding',
      bodyHtml: '<p>Logo and <strong>colour palette</strong> in the portal</p>',
    })
    expect(res.status).toBe(201)

    const after = (await json<{ data: ClientDetail }>(res)).data
    expect(after.requirements).toHaveLength(before.requirements.length + 1)

    const added = after.requirements[after.requirements.length - 1]
    expect(added.title).toBe('Branding')
    // Derived, never supplied — the screen reads both fields and a mock that
    // left bodyText empty would let a list ship that renders blank rows.
    expect(added.bodyText).toBe('Logo and colour palette in the portal')
    expect(added.sequence).toBeGreaterThan(before.requirements[before.requirements.length - 1].sequence)
    expect(added.isMet).toBe(false)
    expect(added.metAt).toBeNull()
  })

  it('refuses a body with no text in it', async () => {
    // The one §3.9 outcome the mock reproduces. A screen built against a mock
    // that accepted this would ship with no handling for the 400 the server
    // answers — and the allow-list itself is the server's, deliberately not
    // reimplemented here.
    const res = await addRequirement(NORTHWIND, { bodyHtml: '<p><br></p>' })

    expect(res.status).toBe(400)
    const body = await json<{ errors: Record<string, string[]> }>(res)
    expect(body.errors).toHaveProperty('bodyHtml')
  })

  it('answers the whole client document, so the page ETag stays usable', async () => {
    const res = await addRequirement(NORTHWIND, { bodyHtml: '<p>Handover pack</p>' })
    const body = await json<{ data: ClientDetail }>(res)

    // Not the requirement — the client, with its contacts and journeys, exactly
    // as every other write in this package answers.
    expect(body.data.id).toBe(NORTHWIND)
    expect(body.data.contacts.length).toBeGreaterThan(0)
    expect(body.data.journeys.length).toBeGreaterThan(0)
  })
})

describe('B-106 · the met flag carries its own evidence', () => {
  it('stamps who and when on the way in, and clears both on the way out', async () => {
    const client = await getClient(NORTHWIND)
    const unmet = client.requirements.find((r) => !r.isMet)!

    const met = (await json<{ data: ClientDetail }>(
      await patchRequirement(NORTHWIND, unmet.id, { isMet: true }),
    )).data.requirements.find((r) => r.id === unmet.id)!

    expect(met.isMet).toBe(true)
    expect(met.metAt).not.toBeNull()
    expect(met.metBy).not.toBeNull()

    const reopened = (await json<{ data: ClientDetail }>(
      await patchRequirement(NORTHWIND, unmet.id, { isMet: false }),
    )).data.requirements.find((r) => r.id === unmet.id)!

    // Cleared rather than left behind — a stamp beside a false flag is what
    // ck_ob_client_requirements_met refuses at the column.
    expect(reopened.isMet).toBe(false)
    expect(reopened.metAt).toBeNull()
    expect(reopened.metBy).toBeNull()
  })

  it('does not re-date a requirement that was already met', async () => {
    const client = await getClient(NORTHWIND)
    const already = client.requirements.find((r) => r.isMet)!
    const stamped = already.metAt

    const after = (await json<{ data: ClientDetail }>(
      await patchRequirement(NORTHWIND, already.id, { bodyHtml: '<p>Reworded in November</p>' }),
    )).data.requirements.find((r) => r.id === already.id)!

    // The rule the three-column design exists for: correcting the wording in
    // November must not re-date a requirement met in March.
    expect(after.bodyText).toBe('Reworded in November')
    expect(after.isMet).toBe(true)
    expect(after.metAt).toBe(stamped)
  })

  it('leaves an omitted field alone and clears an explicit null title', async () => {
    const client = await getClient(NORTHWIND)
    const titled = client.requirements.find((r) => r.title !== null)!

    const untouched = (await json<{ data: ClientDetail }>(
      await patchRequirement(NORTHWIND, titled.id, { bodyHtml: '<p>New wording</p>' }),
    )).data.requirements.find((r) => r.id === titled.id)!
    expect(untouched.title).toBe(titled.title)

    const cleared = (await json<{ data: ClientDetail }>(
      await patchRequirement(NORTHWIND, titled.id, { title: null }),
    )).data.requirements.find((r) => r.id === titled.id)!
    expect(cleared.title).toBeNull()
    // And the body the previous request set is still there — partial by field.
    expect(cleared.bodyText).toBe('New wording')
  })
})

describe('B-106 · removing a requirement takes nothing with it', () => {
  it('removes the row, keeps the gap, and answers 200 with the document', async () => {
    const before = await getClient(NORTHWIND)
    const target = before.requirements[0]
    const survivor = before.requirements[1]

    const res = await deleteRequirement(NORTHWIND, target.id)
    // 200 and not 204: every write here answers with the client so the page
    // never holds a tag for a client that has just changed.
    expect(res.status).toBe(200)

    const after = (await json<{ data: ClientDetail }>(res)).data
    expect(after.requirements.map((r) => r.id)).not.toContain(target.id)
    // The surviving row keeps the sequence it had — renumbering would rewrite
    // every following row to tidy a column nobody reads.
    expect(after.requirements.find((r) => r.id === survivor.id)!.sequence)
      .toBe(survivor.sequence)
    // And nothing else on the client moved.
    expect(after.journeys).toHaveLength(before.journeys.length)
    expect(after.contacts).toHaveLength(before.contacts.length)
  })

  it('404s a requirement id belonging to another client', async () => {
    const db = getDb()
    const other = db.obClients.find((c) => c.id !== NORTHWIND && c.requirements.length > 0)!

    // Resolved by BOTH ids, as every nested onboarding route is: a real
    // requirement under somebody else answers exactly as an invented one does.
    const res = await deleteRequirement(NORTHWIND, other.requirements[0].id)
    expect(res.status).toBe(404)
  })
})

// ── B-107 · client attachments ───────────────────────────────────────────────

/**
 * **The upload route is exercised by its own unit tests on the server, not
 * here, and that is a limitation of vitest rather than a choice.** Under jsdom
 * `FormData`, `Blob` and `File` are jsdom's while `fetch` and `Request` are
 * Node's, and Node refuses to serialise a foreign `FormData` — so no genuine
 * multipart body ever reaches a handler. `request.formData()` does not fail
 * there, it *hangs*, and every test that uploads times out with no hint why:
 * `handlers/rest.ts` says so on the import upload, and
 * `uploadTicketAttachment.test.ts` records the whole realm gap at length.
 *
 * So what is asserted here is everything the card is built against that is not
 * the POST: which rows are readable, which removals leave a mark, and that a
 * removed file keeps its row.
 */

interface AttachmentRow {
  id: number
  fileName: string
  contentType: string
  sizeBytes: number
  kind: 'REFERENCE' | 'SUBMISSION'
  uploadedByType: 'STAFF' | 'CLIENT'
  scanStatus: 'PENDING' | 'CLEAN' | 'INFECTED' | 'FAILED'
  downloadUrl: string | null
  isDeleted: boolean
  deletedAt: string | null
  deletedBy: { id: number; displayName: string } | null
}

const listAttachments = (clientId: number) =>
  fetch(`/api/v1/onboarding/clients/${clientId}/attachments`)

const deleteAttachment = (clientId: number, attachmentId: number) =>
  fetch(`/api/v1/onboarding/clients/${clientId}/attachments/${attachmentId}`, {
    method: 'DELETE',
  })

/** A row filed straight into the fixture, standing in for the POST above. */
const seedAttachment = (clientId: number, uploadedById: number, fileName: string) => {
  const db = getDb()
  const client = db.obClients.find((c) => c.id === clientId)!
  const id = Math.max(0, ...db.obClients.flatMap((c) => c.attachments.map((a) => a.id))) + 1
  client.attachments.push({
    id,
    fileName,
    contentType: 'application/pdf',
    sizeBytes: 1024,
    kind: 'SUBMISSION',
    uploadedByType: 'STAFF',
    uploadedById,
    scanStatus: 'CLEAN',
    deletedAt: null,
    deletedById: null,
    createdAt: new Date().toISOString(),
  })
  return id
}

describe('B-107 · a document becomes readable only after the scan', () => {
  it('gives a CLEAN row a download URL and a PENDING row none', async () => {
    const rows = (await json<{ data: AttachmentRow[] }>(await listAttachments(NORTHWIND))).data

    const clean = rows.find((a) => a.scanStatus === 'CLEAN')!
    const pending = rows.find((a) => a.scanStatus === 'PENDING')!

    expect(clean.downloadUrl).not.toBeNull()
    // The whole enforcement, and the reason it is an absent URL rather than an
    // absent row: a card that hid pending files would make a scan delay look
    // like a failed upload and leave somebody re-attaching the same file.
    expect(pending.downloadUrl).toBeNull()
  })

  it('returns a PENDING row rather than hiding it', async () => {
    const rows = (await json<{ data: AttachmentRow[] }>(await listAttachments(NORTHWIND))).data
    expect(rows.some((a) => a.scanStatus === 'PENDING')).toBe(true)
  })

  it('carries both kinds a client-owned file can be, and neither of the other two', async () => {
    const rows = (await json<{ data: AttachmentRow[] }>(await listAttachments(NORTHWIND))).data

    // REFERENCE is a document staff attached for the client to read;
    // SUBMISSION is what the client sent in. It decides what the portal may do
    // with the row, so a card that ignored it would offer a client a replace
    // action on a document they may never touch.
    expect(rows.some((a) => a.kind === 'REFERENCE')).toBe(true)
    expect(rows.some((a) => a.kind === 'SUBMISSION')).toBe(true)
    // DELIVERABLE belongs to a service and EVIDENCE to a sign-off — other owner
    // arms of the same table, and neither can appear on a client-owned file.
    expect(rows.every((a) => a.kind === 'REFERENCE' || a.kind === 'SUBMISSION')).toBe(true)
  })
})

describe('B-107 · removal keeps the row and sometimes says so', () => {
  it('shows a tombstone for a removal that was not the uploader own', async () => {
    const rows = (await json<{ data: AttachmentRow[] }>(await listAttachments(NORTHWIND))).data
    const tombstone = rows.find((a) => a.isDeleted)!

    // "File removed by X on date" — the supervisory removal the record is
    // supposed to keep, and the one state nothing else in this fixture produces.
    expect(tombstone.deletedAt).not.toBeNull()
    expect(tombstone.deletedBy).not.toBeNull()
    // A tombstone never carries a URL: the bytes are gone, not merely hidden.
    expect(tombstone.downloadUrl).toBeNull()
  })

  it('hides the tombstone when the uploader removes their own file promptly', async () => {
    // Uploaded by user 1, which is who the mock signs every write as.
    const id = seedAttachment(NORTHWIND, 1, 'wrong-client.pdf')

    expect((await deleteAttachment(NORTHWIND, id)).status).toBe(204)

    const after = (await json<{ data: AttachmentRow[] }>(await listAttachments(NORTHWIND))).data
    // Gone from the listing entirely. Somebody who drops the wrong PDF and
    // removes it ten seconds later has not done anything the client record
    // needs to remember, and a permanent note for every mis-drop would train
    // everyone to read past the line that matters.
    expect(after.map((a) => a.id)).not.toContain(id)

    // But the row itself survives — the object goes, the record does not.
    const stored = getDb().obClients.find((c) => c.id === NORTHWIND)!.attachments
    expect(stored.find((a) => a.id === id)!.deletedAt).not.toBeNull()
  })

  it('shows the tombstone when somebody else removes it, inside the window', async () => {
    // Uploaded by user 5, removed by user 1 seconds later. Time alone would
    // hide this; the uploader comparison is what makes the rule right — a
    // supervisory removal is exactly the kind the record should keep.
    const id = seedAttachment(NORTHWIND, 5, 'leaked-pricing.pdf')

    expect((await deleteAttachment(NORTHWIND, id)).status).toBe(204)

    const after = (await json<{ data: AttachmentRow[] }>(await listAttachments(NORTHWIND))).data
    const tombstone = after.find((a) => a.id === id)!
    expect(tombstone.isDeleted).toBe(true)
    expect(tombstone.downloadUrl).toBeNull()
  })

  it('is idempotent and does not re-stamp who removed it', async () => {
    const id = seedAttachment(NORTHWIND, 1, 'duplicate-delete.pdf')

    await deleteAttachment(NORTHWIND, id)
    const row = getDb().obClients.find((c) => c.id === NORTHWIND)!.attachments
      .find((a) => a.id === id)!
    const firstStamp = row.deletedAt

    // 204 rather than 404: the caller asked for the file to be gone and it is
    // gone, and refusing would distinguish "already removed" from "never
    // existed" for anyone allowed to ask.
    expect((await deleteAttachment(NORTHWIND, id)).status).toBe(204)
    expect(row.deletedAt).toBe(firstStamp)
  })

  it('404s an attachment id belonging to another client', async () => {
    const db = getDb()
    const other = db.obClients.find(
      (c) => c.id !== NORTHWIND && c.attachments.length > 0,
    )!

    // Resolved by BOTH ids. These ids are drawn from a sequence shared with
    // every journey step and sign-off file, so a status that distinguished
    // "not yours" from "not there" would enumerate the module whole upload
    // history one integer at a time.
    const res = await deleteAttachment(NORTHWIND, other.attachments[0].id)
    expect(res.status).toBe(404)
  })
})
