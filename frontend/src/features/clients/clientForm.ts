import { z } from 'zod'

import { isWellFormedEmail } from '@/lib/email'
import type { ClientDetail } from '@/api/generated/model/clientDetail'
import type { ClientStatus } from '@/api/generated/model/clientStatus'
import type { ClientSupportPlan } from '@/api/generated/model/clientSupportPlan'
import type { ClientWriteRequest } from '@/api/generated/model/clientWriteRequest'

/**
 * B-026 · the S-33 form's shape, its validation and its two translations.
 *
 * Kept out of the component for the reason `projectForm.ts` and `resourceForm.ts`
 * both give: the mapping between a form and a request is the part worth testing
 * on its own, and it is unreachable behind a rendered page.
 *
 * <h2>Empty string is the form's null</h2>
 *
 * Every optional text input holds `''` when untouched, never `undefined` — React
 * would otherwise switch the input from uncontrolled to controlled on the first
 * keystroke and warn. {@link toWriteRequest} is the only place that turns `''`
 * back into the `null` the contract means by "clear it", so no component has to
 * remember the convention.
 *
 * <h2>Which tab a field belongs to lives here, not in the page</h2>
 *
 * {@link FIELD_TAB} maps every field to one of S-33's four tabs. The page uses it
 * for one thing that matters: **when the server refuses a save, the form opens
 * the tab the first bad field is on.** Without it a 400 naming `timezone` while
 * the admin is looking at Commercial marks an input on a tab they cannot see, and
 * the save appears to fail silently — which is the specific failure a four-tab
 * form makes easy and a one-page form does not.
 */

export type ClientTab = 'Identity' | 'Commercial' | 'Contacts' | 'Projects & SLA'

export const CLIENT_TABS: readonly ClientTab[] = [
  'Identity',
  'Commercial',
  'Contacts',
  'Projects & SLA',
] as const

export const CLIENT_STATUSES: readonly { value: ClientStatus; label: string; hint: string }[] = [
  {
    value: 'ACTIVE',
    label: 'Active',
    hint: 'A contracted client. Selectable on the ticket form.',
  },
  {
    value: 'PROSPECT',
    label: 'Prospect',
    hint: 'Not contracted yet. Still selectable — a pre-sales ticket is a real thing to raise.',
  },
  {
    value: 'INACTIVE',
    label: 'Inactive',
    hint: 'Blocks new tickets. Historical ones are never hidden.',
  },
] as const

/**
 * §4B.2's Commercial group names Standard / Premium / Enterprise; the seeded
 * fixture has clients on Basic. The picker offers all four — see
 * `ClientSupportPlan` on the server for the argument.
 *
 * **Codes upper-case, labels title-case.** The server has always stored the
 * upper-case form and only the MSW mock ever wrote `'Premium'`; a `<Select>`
 * bound to these values would have rendered nothing selected in `npm run dev`
 * and been perfectly fine against a real backend, which is the worst way round
 * for a bug to sit.
 */
export const SUPPORT_PLANS: readonly { value: ClientSupportPlan; label: string }[] = [
  { value: 'BASIC', label: 'Basic' },
  { value: 'STANDARD', label: 'Standard' },
  { value: 'PREMIUM', label: 'Premium' },
  { value: 'ENTERPRISE', label: 'Enterprise' },
] as const

/** Matches `ClientWriteRequest.clientCode`'s pattern, and the server's. */
export const CLIENT_CODE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9_-]*$/

const MAX_TAGS = 20

/**
 * The field rules, before the cross-field one.
 *
 * Exported separately because {@link clientFormSchema} is a `ZodEffects` once
 * `.refine()` is applied and a `ZodEffects` has no `.shape` — so the page's "is
 * this server error key a field on this form?" check would have to reach into
 * `_def`, which breaks on a zod bump. `projectForm.ts` documents the same split.
 */
export const clientFieldSchema = z.object({
  // ── Identity ────────────────────────────────────────────────────────────
  clientCode: z
    .string()
    .trim()
    .min(2, 'At least 2 characters')
    .max(20, 'At most 20 characters')
    .refine((v) => CLIENT_CODE_PATTERN.test(v), 'Letters, digits, hyphens and underscores only'),
  name: z.string().trim().min(1, 'Client name is required').max(150, 'At most 150 characters'),
  shortName: z.string().trim().max(60, 'At most 60 characters'),
  logoUrl: z.string().trim().max(500, 'At most 500 characters'),
  industry: z.string().trim().max(80, 'At most 80 characters'),
  status: z.enum(['ACTIVE', 'INACTIVE', 'PROSPECT']),
  domain: z.string().trim().max(120, 'At most 120 characters'),
  primaryEmail: z.string().trim().max(150).refine(isBlankOrEmail, 'Not a valid email address'),
  supportEmail: z.string().trim().max(150).refine(isBlankOrEmail, 'Not a valid email address'),
  phone: z.string().trim().max(30, 'At most 30 characters'),
  addressLine1: z.string().trim().max(150, 'At most 150 characters'),
  addressLine2: z.string().trim().max(150, 'At most 150 characters'),
  city: z.string().trim().max(80, 'At most 80 characters'),
  state: z.string().trim().max(80, 'At most 80 characters'),
  country: z.string().trim().max(80, 'At most 80 characters'),
  postalCode: z.string().trim().max(20, 'At most 20 characters'),
  timezone: z.string().trim().max(50, 'At most 50 characters'),

  // ── Commercial ──────────────────────────────────────────────────────────
  // 0 is "nobody", which is a real state: the column is nullable and a freshly
  // imported client has no account manager. `positive()` is deliberately NOT
  // applied — unlike a project manager, which S-10 requires.
  accountManagerId: z.number().int().min(0),
  contractStart: z.string(),
  contractEnd: z.string(),
  // '' is "not stated", which the column allows.
  supportPlan: z.enum(['', 'BASIC', 'STANDARD', 'PREMIUM', 'ENTERPRISE']),
  billingReference: z.string().trim().max(60, 'At most 60 characters'),
  billingEmail: z.string().trim().max(150).refine(isBlankOrEmail, 'Not a valid email address'),
  notes: z.string().trim().max(5000, 'At most 5000 characters'),
  tags: z.array(z.string().trim().min(1).max(40)).max(MAX_TAGS, `At most ${MAX_TAGS} tags`),

  // ── Projects & SLA ──────────────────────────────────────────────────────
  projectIds: z.array(z.number().int().positive()),
  // 0 rather than null, for the reason `projectForm` gives about controlled
  // selects. The cross-field rule below is what makes an orphaned default a
  // validation failure rather than a 400 from the server.
  defaultProjectId: z.number().int().min(0),
})

/**
 * The two cross-field rules, both of which the server also checks — a
 * client-side comparison is a convenience, never the guarantee.
 */
export const clientFormSchema = clientFieldSchema
  .refine((v) => !v.contractStart || !v.contractEnd || v.contractEnd >= v.contractStart, {
    path: ['contractEnd'],
    message: 'The contract cannot end before it starts',
  })
  .refine((v) => v.defaultProjectId === 0 || v.projectIds.includes(v.defaultProjectId), {
    path: ['defaultProjectId'],
    message: 'The default project must be one this client is mapped to',
  })

export type ClientFormValues = z.infer<typeof clientFieldSchema>

/**
 * Which of S-33's tabs each field lives on.
 *
 * Every key of {@link clientFieldSchema} appears exactly once — asserted in
 * `clientForm.test.ts`, because a field added to the schema and forgotten here
 * would land its server error on no tab at all and the save would look silent.
 */
export const FIELD_TAB: Record<keyof ClientFormValues, ClientTab> = {
  clientCode: 'Identity',
  name: 'Identity',
  shortName: 'Identity',
  logoUrl: 'Identity',
  industry: 'Identity',
  status: 'Identity',
  domain: 'Identity',
  primaryEmail: 'Identity',
  supportEmail: 'Identity',
  phone: 'Identity',
  addressLine1: 'Identity',
  addressLine2: 'Identity',
  city: 'Identity',
  state: 'Identity',
  country: 'Identity',
  postalCode: 'Identity',
  timezone: 'Identity',
  accountManagerId: 'Commercial',
  contractStart: 'Commercial',
  contractEnd: 'Commercial',
  supportPlan: 'Commercial',
  billingReference: 'Commercial',
  billingEmail: 'Commercial',
  notes: 'Commercial',
  tags: 'Commercial',
  projectIds: 'Projects & SLA',
  defaultProjectId: 'Projects & SLA',
}

/**
 * The tab a server error should open, or null when nothing matches.
 *
 * Takes the **first field in schema order** rather than the first key the
 * server happened to serialise. `ClientWriteService` collects failures into a
 * `LinkedHashMap` in its own validation order, which is not the form's reading
 * order — so trusting it would sometimes open Commercial for a form whose first
 * visible problem is the client code.
 */
export function tabForErrors(fields: readonly string[]): ClientTab | null {
  const ordered = Object.keys(clientFieldSchema.shape) as (keyof ClientFormValues)[]
  const first = ordered.find((field) => fields.includes(field))
  return first ? FIELD_TAB[first] : null
}

export const emptyClientForm: ClientFormValues = {
  clientCode: '',
  name: '',
  shortName: '',
  logoUrl: '',
  industry: '',
  status: 'ACTIVE',
  domain: '',
  primaryEmail: '',
  supportEmail: '',
  phone: '',
  addressLine1: '',
  addressLine2: '',
  city: '',
  state: '',
  country: '',
  postalCode: '',
  // The column default, and not a guess made here — the server applies the same
  // value when the field is absent.
  timezone: 'Asia/Kolkata',
  accountManagerId: 0,
  contractStart: '',
  contractEnd: '',
  supportPlan: '',
  billingReference: '',
  billingEmail: '',
  notes: '',
  tags: [],
  projectIds: [],
  defaultProjectId: 0,
}

/** The edit form's load. Every null becomes `''`; see the note at the top. */
export function toFormValues(client: ClientDetail): ClientFormValues {
  return {
    clientCode: client.clientCode ?? '',
    name: client.name ?? '',
    shortName: client.shortName ?? '',
    logoUrl: client.logoUrl ?? '',
    industry: client.industry ?? '',
    status: client.status ?? 'ACTIVE',
    domain: client.domain ?? '',
    primaryEmail: client.primaryEmail ?? '',
    supportEmail: client.supportEmail ?? '',
    phone: client.phone ?? '',
    addressLine1: client.addressLine1 ?? '',
    addressLine2: client.addressLine2 ?? '',
    city: client.city ?? '',
    state: client.state ?? '',
    country: client.country ?? '',
    postalCode: client.postalCode ?? '',
    timezone: client.timezone ?? 'Asia/Kolkata',
    accountManagerId: client.accountManager?.id ?? 0,
    contractStart: client.contractStart ?? '',
    contractEnd: client.contractEnd ?? '',
    supportPlan: (client.supportPlan as ClientSupportPlan | null | undefined) ?? '',
    billingReference: client.billingReference ?? '',
    billingEmail: client.billingEmail ?? '',
    notes: client.notes ?? '',
    tags: client.tags ?? [],
    // The mapping the grid already carries — reused rather than re-fetched, so
    // the form and the row it was opened from cannot disagree.
    projectIds: (client.projects ?? []).map((p) => p.id!).filter((id) => id != null),
    defaultProjectId: client.defaultProjectId ?? 0,
  }
}

/**
 * The save, for both verbs.
 *
 * **Every field on every save**, not a dirty-field diff. S-33 is a whole-form
 * save and a partial patch assembled from React state is the source of "the field
 * I did not touch got cleared" bugs that only appear under a race — B-016 made
 * the same call, and the server's `apply` is written to match ("an absent field
 * is a cleared field").
 *
 * **`projectIds` is always sent**, including empty. The server distinguishes
 * absent from empty — absent leaves the mapping alone, empty unmaps everything —
 * and this form is the screen that owns the mapping, so it always states it. The
 * caller that must send *absent* is B-035's import, which does not go through
 * here.
 */
export function toWriteRequest(values: ClientFormValues): ClientWriteRequest {
  return {
    clientCode: values.clientCode.trim().toUpperCase(),
    name: values.name.trim(),
    shortName: blankToNull(values.shortName),
    logoUrl: blankToNull(values.logoUrl),
    industry: blankToNull(values.industry),
    status: values.status,
    domain: blankToNull(values.domain),
    primaryEmail: blankToNull(values.primaryEmail),
    supportEmail: blankToNull(values.supportEmail),
    phone: blankToNull(values.phone),
    addressLine1: blankToNull(values.addressLine1),
    addressLine2: blankToNull(values.addressLine2),
    city: blankToNull(values.city),
    state: blankToNull(values.state),
    country: blankToNull(values.country),
    postalCode: blankToNull(values.postalCode),
    timezone: blankToNull(values.timezone),
    accountManagerId: values.accountManagerId > 0 ? values.accountManagerId : null,
    contractStart: blankToNull(values.contractStart),
    contractEnd: blankToNull(values.contractEnd),
    supportPlan: values.supportPlan === '' ? null : values.supportPlan,
    billingReference: blankToNull(values.billingReference),
    billingEmail: blankToNull(values.billingEmail),
    notes: blankToNull(values.notes),
    tags: values.tags,
    projectIds: values.projectIds,
    defaultProjectId: values.defaultProjectId > 0 ? values.defaultProjectId : null,
  }
}

/**
 * A tag as it will be stored — trimmed, and rejected if it duplicates one
 * already there.
 *
 * The server de-duplicates too, and silently. Doing it here as well is what
 * makes the *refusal visible*: an admin who types `retail` twice and sees the
 * chip count stay at four has been told nothing, and the honest answer is that
 * the tag is already on the client.
 */
export function addTag(tags: readonly string[], raw: string): readonly string[] {
  const tag = raw.trim()
  if (tag === '' || tags.length >= MAX_TAGS) return tags
  if (tags.some((t) => t.toLowerCase() === tag.toLowerCase())) return tags
  return [...tags, tag]
}

export function isTagLimitReached(tags: readonly string[]): boolean {
  return tags.length >= MAX_TAGS
}

/**
 * Whether B-028's gate is satisfied — reported, never enforced from here.
 *
 * A client without a primary contact is not selectable on a ticket. The form
 * says so on the Contacts tab rather than blocking the save: the save is what
 * creates the client the contact will hang off, so refusing it would make the
 * rule impossible to satisfy.
 */
export function isSelectableOnTickets(client: ClientDetail | null): boolean {
  return client?.hasPrimaryContact === true
}

function blankToNull(value: string): string | null {
  const trimmed = value.trim()
  return trimmed === '' ? null : trimmed
}

/**
 * Blank passes — all three of these fields are optional, and a client that
 * raises everything by phone genuinely has no support address. Reporting "not a
 * valid email" for a box nobody filled in would make the three of them feel
 * required when only the shape is checked.
 *
 * B-028 · the shape itself is `isWellFormedEmail`, the same rule `EmailFormat`
 * applies on the server, rather than zod's `.email()` — which accepted
 * `accounts@acme` and would have let the form pass an address the save now
 * refuses and B-035's import would reject.
 */
function isBlankOrEmail(value: string): boolean {
  return value.trim() === '' || isWellFormedEmail(value)
}
