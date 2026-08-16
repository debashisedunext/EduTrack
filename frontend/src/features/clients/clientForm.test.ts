import { describe, expect, it } from 'vitest'

import type { ClientDetail } from '@/api/generated/model/clientDetail'

import {
  addTag,
  CLIENT_STATUSES,
  CLIENT_TABS,
  clientFieldSchema,
  clientFormSchema,
  emptyClientForm,
  FIELD_TAB,
  isSelectableOnTickets,
  SUPPORT_PLANS,
  tabForErrors,
  toFormValues,
  toWriteRequest,
  type ClientFormValues,
} from './clientForm'

/**
 * B-026 · the S-33 form's translations and its two cross-field rules.
 *
 * These are the parts unreachable behind a rendered page, and the ones where a
 * mistake is silent: a field that never reaches the request, a null that arrives
 * as an empty string, an error routed to a tab nobody opens.
 */

const values = (overrides: Partial<ClientFormValues> = {}): ClientFormValues => ({
  ...emptyClientForm,
  clientCode: 'ACME',
  name: 'Acme Retail Ltd',
  ...overrides,
})

describe('toWriteRequest', () => {
  it('upper-cases the client code, so acme and ACME cannot both be created', () => {
    expect(toWriteRequest(values({ clientCode: 'acme-retail' })).clientCode).toBe('ACME-RETAIL')
  })

  /**
   * The convention the whole module rests on: an untouched optional input holds
   * `''`, and `''` is the form's null. A request carrying empty strings would
   * store them, and a column full of `''` reads as "set to nothing" everywhere
   * a null would have read as "not stated".
   */
  it('turns every blank optional field into null', () => {
    const request = toWriteRequest(values())

    expect(request.shortName).toBeNull()
    expect(request.industry).toBeNull()
    expect(request.billingEmail).toBeNull()
    expect(request.contractStart).toBeNull()
    expect(request.supportPlan).toBeNull()
    expect(request.accountManagerId).toBeNull()
    expect(request.defaultProjectId).toBeNull()
  })

  /**
   * 0 is the form's "nobody" for the two id fields — a controlled `<select>`
   * cannot hold null. Sending it would be sending a foreign key to a row that
   * does not exist.
   */
  it('sends null rather than 0 for an unset account manager and default project', () => {
    const request = toWriteRequest(values({ accountManagerId: 0, defaultProjectId: 0 }))

    expect(request.accountManagerId).toBeNull()
    expect(request.defaultProjectId).toBeNull()
  })

  it('sends real ids through unchanged', () => {
    const request = toWriteRequest(
      values({ accountManagerId: 2, projectIds: [1, 3], defaultProjectId: 3 }),
    )

    expect(request.accountManagerId).toBe(2)
    expect(request.projectIds).toEqual([1, 3])
    expect(request.defaultProjectId).toBe(3)
  })

  /**
   * The server distinguishes absent from empty — absent leaves the mapping
   * alone, empty unmaps everything. This form owns the mapping, so it always
   * states it: an omitted array would make "unmap this client from everything"
   * impossible to express through the only screen that can.
   */
  it('always sends projectIds, including empty', () => {
    const request = toWriteRequest(values({ projectIds: [] }))

    expect(request.projectIds).toEqual([])
    expect('projectIds' in request).toBe(true)
  })

  /** S-33 is a whole-form save, not a dirty-field diff — B-016's call, restated. */
  it('sends every field on every save', () => {
    const request = toWriteRequest(values())
    const sent = new Set(Object.keys(request))

    for (const field of Object.keys(clientFieldSchema.shape)) {
      expect(sent.has(field)).toBe(true)
    }
  })
})

describe('toFormValues', () => {
  it('turns every null back into the empty string the inputs hold', () => {
    const client = {
      id: 1,
      clientCode: 'ACME',
      name: 'Acme Retail Ltd',
      shortName: null,
      industry: null,
      notes: null,
      contractStart: null,
      supportPlan: null,
      tags: undefined,
      projects: [],
      defaultProjectId: null,
    } as unknown as ClientDetail

    const form = toFormValues(client)

    expect(form.shortName).toBe('')
    expect(form.industry).toBe('')
    expect(form.supportPlan).toBe('')
    expect(form.tags).toEqual([])
    expect(form.defaultProjectId).toBe(0)
  })

  it('round-trips a fully populated client without losing a field', () => {
    const client = {
      id: 1,
      clientCode: 'ACME',
      name: 'Acme Retail Ltd',
      shortName: 'Acme',
      logoUrl: 'https://acme.example/logo.png',
      industry: 'Retail',
      status: 'PROSPECT',
      domain: 'acme.example',
      primaryEmail: 'hello@acme.example',
      supportEmail: 'support@acme.example',
      phone: '+91 98200 11111',
      addressLine1: '14 Linking Road',
      addressLine2: 'Bandra West',
      city: 'Mumbai',
      state: 'Maharashtra',
      country: 'India',
      postalCode: '400050',
      timezone: 'Asia/Kolkata',
      accountManager: { id: 2, displayName: 'Meera Iyer' },
      contractStart: '2025-04-01',
      contractEnd: '2027-03-31',
      supportPlan: 'PREMIUM',
      billingReference: 'PO-2025-0142',
      billingEmail: 'accounts@acme.example',
      notes: 'Quarterly review every January.',
      tags: ['retail', 'strategic'],
      projects: [{ id: 1, projectCode: 'CRM', name: 'CRM' }],
      defaultProjectId: 1,
    } as unknown as ClientDetail

    const request = toWriteRequest(toFormValues(client))

    expect(request).toMatchObject({
      clientCode: 'ACME',
      shortName: 'Acme',
      status: 'PROSPECT',
      supportPlan: 'PREMIUM',
      accountManagerId: 2,
      billingReference: 'PO-2025-0142',
      tags: ['retail', 'strategic'],
      projectIds: [1],
      defaultProjectId: 1,
    })
  })

  /**
   * The mapping the grid already carries, reused rather than re-fetched — so the
   * form and the row it was opened from cannot disagree about which projects a
   * client is on.
   */
  it('takes projectIds from the projects the detail already carries', () => {
    const client = {
      clientCode: 'A',
      name: 'A',
      projects: [
        { id: 3, projectCode: 'WEB', name: 'Web' },
        { id: 1, projectCode: 'CRM', name: 'CRM' },
      ],
    } as unknown as ClientDetail

    expect(toFormValues(client).projectIds).toEqual([3, 1])
  })
})

describe('validation', () => {
  it('refuses a contract that ends before it starts', () => {
    const result = clientFormSchema.safeParse(
      values({ contractStart: '2026-06-01', contractEnd: '2026-05-01' }),
    )

    expect(result.success).toBe(false)
    expect(result.error?.issues[0]?.path).toEqual(['contractEnd'])
  })

  it('allows a contract that starts and ends on the same day', () => {
    expect(
      clientFormSchema.safeParse(values({ contractStart: '2026-06-01', contractEnd: '2026-06-01' }))
        .success,
    ).toBe(true)
  })

  /**
   * A default the client is not mapped to is a row §4B.2's ticket form can never
   * offer — configuration that silently does nothing. Caught here as well as on
   * the server, because the message belongs next to the control.
   */
  it('refuses a default project the client is not mapped to', () => {
    const result = clientFormSchema.safeParse(
      values({ projectIds: [1, 2], defaultProjectId: 3 }),
    )

    expect(result.success).toBe(false)
    expect(result.error?.issues[0]?.path).toEqual(['defaultProjectId'])
  })

  it('allows no default at all', () => {
    expect(
      clientFormSchema.safeParse(values({ projectIds: [1, 2], defaultProjectId: 0 })).success,
    ).toBe(true)
  })

  /** These are optional fields; `.email()` on an untouched box is a false refusal. */
  it('accepts a blank email but refuses a malformed one', () => {
    expect(clientFormSchema.safeParse(values({ billingEmail: '' })).success).toBe(true)
    expect(clientFormSchema.safeParse(values({ billingEmail: 'not-an-email' })).success).toBe(false)
  })

  it('refuses a client code with a space or a slash in it', () => {
    expect(clientFormSchema.safeParse(values({ clientCode: 'AC ME' })).success).toBe(false)
    expect(clientFormSchema.safeParse(values({ clientCode: 'AC/ME' })).success).toBe(false)
    expect(clientFormSchema.safeParse(values({ clientCode: 'AC-ME_1' })).success).toBe(true)
  })

  /** An account manager is optional here, unlike a project manager on S-10. */
  it('does not require an account manager', () => {
    expect(clientFormSchema.safeParse(values({ accountManagerId: 0 })).success).toBe(true)
  })
})

describe('tab routing', () => {
  /**
   * A field added to the schema and forgotten here would land its server error
   * on no tab at all, and the save would look silent — which is the specific
   * failure a four-tab form makes easy.
   */
  it('assigns every schema field to exactly one tab', () => {
    const fields = Object.keys(clientFieldSchema.shape)

    expect(Object.keys(FIELD_TAB).sort()).toEqual(fields.sort())
    for (const tab of Object.values(FIELD_TAB)) {
      expect(CLIENT_TABS).toContain(tab)
    }
  })

  /**
   * Schema order, not the order the server serialised. `ClientWriteService`
   * collects failures in its own validation order, which is not the form's
   * reading order — so trusting it would sometimes open Commercial for a form
   * whose first visible problem is on Identity.
   */
  it('opens the tab of the first bad field in schema order, not in server order', () => {
    expect(tabForErrors(['timezone', 'clientCode'])).toBe('Identity')
    expect(tabForErrors(['supportPlan', 'accountManagerId'])).toBe('Commercial')
    expect(tabForErrors(['defaultProjectId'])).toBe('Projects & SLA')
  })

  it('has no tab to open for an error naming nothing on the form', () => {
    expect(tabForErrors(['somethingElse'])).toBeNull()
    expect(tabForErrors([])).toBeNull()
  })
})

describe('tags', () => {
  it('trims and refuses a duplicate, case-insensitively', () => {
    expect(addTag(['retail'], '  vip ')).toEqual(['retail', 'vip'])
    expect(addTag(['retail'], 'Retail')).toEqual(['retail'])
    expect(addTag(['retail'], '   ')).toEqual(['retail'])
  })

  it('stops at twenty', () => {
    const full = Array.from({ length: 20 }, (_, i) => `tag-${i}`)

    expect(addTag(full, 'one-more')).toHaveLength(20)
  })
})

describe('the vocabularies', () => {
  /**
   * The seam with the server's `ClientStatus` and `ck_clients_status`. Nothing
   * re-checks a TypeScript union against a MySQL CHECK, so a fourth value added
   * to one and not the others would mean the form offering a status the database
   * refuses.
   */
  it("offers exactly blueprint §4B.2's three statuses", () => {
    expect(CLIENT_STATUSES.map((s) => s.value)).toEqual(['ACTIVE', 'PROSPECT', 'INACTIVE'])
  })

  it('offers the four support plans as stored codes, not as labels', () => {
    expect(SUPPORT_PLANS.map((p) => p.value)).toEqual([
      'BASIC',
      'STANDARD',
      'PREMIUM',
      'ENTERPRISE',
    ])
    expect(SUPPORT_PLANS.map((p) => p.label)).toEqual([
      'Basic',
      'Standard',
      'Premium',
      'Enterprise',
    ])
  })
})

describe("B-028's gate", () => {
  it('is reported off the detail read and is false for a client with no contacts', () => {
    expect(isSelectableOnTickets(null)).toBe(false)
    expect(isSelectableOnTickets({ hasPrimaryContact: false } as ClientDetail)).toBe(false)
    expect(isSelectableOnTickets({ hasPrimaryContact: true } as ClientDetail)).toBe(true)
  })
})
