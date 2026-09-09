import * as React from 'react'
import { useNavigate } from 'react-router-dom'

import { useCreateObClient } from '@/api/generated/onboarding/onboarding'
import { useListObProducts } from '@/api/generated/onboarding-masters/onboarding-masters'
import { useListUsers } from '@/api/generated/users/users'
import type { ObClientCreateRequest } from '@/api/generated/model/obClientCreateRequest'
import { ApiError } from '@/api/http'

import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { SearchableDropdown } from '@/components/ui/searchable-dropdown'

import { FormField } from '@/features/masters/resources/FormField'

import {
  OB_CLIENT_NO_PREREQ_MASTER,
  OB_CLIENT_NAME_SIMILAR,
  OB_CLIENT_PAN_DUPLICATE,
  OB_CLIENT_PORTAL_LOGIN_UNAVAILABLE,
  OB_PRODUCT_NO_TEMPLATE,
} from './obClientWizardProblems'

const PAN_PATTERN = /^[A-Z]{5}[0-9]{4}[A-Z]$/

/** The mockup's four steps, verbatim — `vWizard()` in `docs/prototype/onboarding.html`. */
const STEPS = [
  { number: 1, label: 'Client basics' },
  { number: 2, label: 'SPOC contacts' },
  { number: 3, label: 'Commercials' },
  { number: 4, label: 'Requirements & journeys' },
] as const

type Step = (typeof STEPS)[number]['number']

/** The mockup's client-level license select. `licenseType` is a free string in the contract; these are its offered values. */
const CLIENT_LICENSE_TYPES = [
  'Starter · Annual',
  'Professional · Annual',
  'Enterprise · Annual',
  'Enterprise · 3-year',
] as const

/** The mockup's per-application license select. */
const APPLICATION_LICENSE_TYPES = ['Starter', 'Professional', 'Enterprise', 'Add-on'] as const

interface ContactDraft {
  key: string
  name: string
  email: string
  phone: string
}

interface ApplicationDraft {
  key: string
  productId: number
  licenseType: string
  units: string
}

let draftKeySeq = 0
function draftKey(): string {
  draftKeySeq += 1
  return `draft-${draftKeySeq}`
}

function blankContact(): ContactDraft {
  return { key: draftKey(), name: '', email: '', phone: '' }
}

/** Which step a server-reported field belongs to, so a 400/409 can jump the wizard there. */
function stepForField(field: string): Step {
  if (field.startsWith('contacts')) return 2
  if (field.startsWith('applications')) return 3
  if (field.startsWith('requirements') || field === 'createPortalLogin') return 4
  return 1
}

const HTML_ESCAPES: Record<string, string> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;',
}

/**
 * The mockup's requirements capture is one plain textarea; the contract's is
 * rich-text HTML run through the server's sanitiser. Plain text goes out as
 * escaped paragraphs — never interpolated raw, because a requirement
 * containing `<script>` is user input like any other.
 */
function textToHtml(text: string): string {
  return text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => `<p>${line.replace(/[&<>"']/g, (c) => HTML_ESCAPES[c])}</p>`)
    .join('')
}

/**
 * B-109 · OB-04, the four-step new client wizard — `/onboarding/clients/new`,
 * aligned to the authoritative mockup (`docs/prototype/onboarding.html`,
 * `vWizard()`).
 *
 * ## The mockup's four steps over the contract's one call
 *
 * "Client basics", "SPOC contacts", "Commercials", "Requirements & journeys".
 * Nothing is written until the finish button — the first three steps are
 * local form state with nothing to resume, so the current step is an explicit
 * index rather than derived from anything server-side.
 *
 * ## Commercials and the step-4 checkboxes share one state
 *
 * The mockup keeps an applications table (step 3) and a products-bought
 * checkbox list (step 4) as two structures. The contract has one:
 * `applications[]`, each of which **is** a purchased product and instantiates
 * a locked journey. So both screens edit the same `applications` draft — a
 * row added on Commercials shows checked on step 4, and unchecking there
 * removes the row. Two views, one truth, no way to submit a purchase the
 * commercials step never saw.
 *
 * ## Validation is the mockup's: a banner on Continue, not a disabled button
 *
 * The mockup's Continue is always pressable and answers an invalid step with
 * a `role="alert"` banner naming the first problem. A disabled button tells a
 * keyboard or screen-reader user nothing about *why*.
 *
 * ## What the mockup shows that the contract cannot (reported, not faked)
 *
 * - **City** — no such field on `ObClientCreateRequest`.
 * - **Attachments on step 4** — attachments are per-client endpoints that
 *   exist only after the client does.
 * - **Template name/version/step-count captions** per product — the product
 *   read carries `hasActiveTemplate` and `totalTatDays` only.
 *
 * ## The duplicate-PAN guard is "inline" by where the error lands
 *
 * There is no standalone "does this PAN exist" endpoint. The 409's
 * field-keyed error lands on step 1's PAN input via `stepForField`.
 *
 * ## `createPortalLogin` is real UI over a refusal that is still real
 *
 * Until B-126 lands, checking it and submitting comes back
 * `409 ob-client-portal-login-unavailable` before anything is written.
 * `handleSubmit` unchecks it and explains, rather than pretending the box
 * does nothing.
 */
export function NewObClientWizardPage() {
  const navigate = useNavigate()

  const [step, setStep] = React.useState<Step>(1)
  const [furthestStep, setFurthestStep] = React.useState<Step>(1)

  // ── step 1 · client basics ──────────────────────────────────────────────
  const [name, setName] = React.useState('')
  const [pan, setPan] = React.useState('')
  const [onboardingDate, setOnboardingDate] = React.useState('')
  const [licenseType, setLicenseType] = React.useState('')
  const [address, setAddress] = React.useState('')
  const [salesPersonId, setSalesPersonId] = React.useState<number | null>(null)
  const [description, setDescription] = React.useState('')

  // ── step 2 · SPOC contacts ──────────────────────────────────────────────
  const [contacts, setContacts] = React.useState<ContactDraft[]>(() => [blankContact()])

  // ── steps 3+4 · one purchase list behind two views ──────────────────────
  const [applications, setApplications] = React.useState<ApplicationDraft[]>([])

  // ── step 4 · requirements & journeys ────────────────────────────────────
  const [requirements, setRequirements] = React.useState('')
  const [createPortalLogin, setCreatePortalLogin] = React.useState(false)
  const [acknowledgeSimilarNames, setAcknowledgeSimilarNames] = React.useState(false)
  const [similarNameCandidates, setSimilarNameCandidates] =
    React.useState<{ id: number; name: string }[] | null>(null)
  const [portalLoginNotice, setPortalLoginNotice] = React.useState(false)

  /** The mockup's one `w.err` banner — validation and server refusals both land here. */
  const [bannerError, setBannerError] = React.useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = React.useState<Record<string, string[]>>({})

  const { data: productsData } = useListObProducts({ isActive: true })
  const products = React.useMemo(() => productsData?.data ?? [], [productsData])

  const { data: usersData } = useListUsers({ isActive: true, limit: 200 })
  const users = React.useMemo(() => usersData?.data ?? [], [usersData])

  const create = useCreateObClient()

  function goTo(target: Step) {
    setStep(target)
    setFurthestStep((current) => (target > current ? target : current))
  }

  /** The mockup's per-step guards, its copy included. */
  function stepErrors(target: Step): string[] {
    const errors: string[] = []
    if (target === 1) {
      if (!name.trim()) errors.push('Client name is required.')
      if (!PAN_PATTERN.test(pan.trim().toUpperCase())) errors.push('PAN must look like ABCDE1234F.')
      if (!onboardingDate) errors.push('The onboarding date is required.')
    }
    if (target === 2) {
      const primary = contacts[0]
      if (!primary || !primary.name.trim() || !primary.email.trim()) {
        errors.push('At least one SPOC with name and email is required.')
      }
    }
    return errors
  }

  function finishErrors(): string[] {
    const errors: string[] = []
    if (!requirements.trim()) errors.push('Capture at least one line of client requirements.')
    if (applications.length === 0) {
      errors.push('Select at least one product — each product gets its own journey.')
    }
    return errors
  }

  function handleContinue() {
    const errors = stepErrors(step)
    if (errors.length > 0) {
      setBannerError(errors[0])
      return
    }
    setBannerError(null)
    goTo((step + 1) as Step)
  }

  function handleBack() {
    setBannerError(null)
    goTo((step - 1) as Step)
  }

  const selectable = products.filter((p) => p.hasActiveTemplate !== false)

  function addApplication() {
    const next = selectable.find((p) => !applications.some((a) => a.productId === p.id))
    if (!next) return
    setApplications((current) => [
      ...current,
      { key: draftKey(), productId: next.id, licenseType: '', units: '' },
    ])
  }

  function toggleProduct(productId: number, selected: boolean) {
    setApplications((current) =>
      selected
        ? [...current, { key: draftKey(), productId, licenseType: '', units: '' }]
        : current.filter((a) => a.productId !== productId),
    )
  }

  function updateApplication(key: string, patch: Partial<ApplicationDraft>) {
    setApplications((current) => current.map((a) => (a.key === key ? { ...a, ...patch } : a)))
  }

  function buildRequestBody(overrides?: Partial<ObClientCreateRequest>): ObClientCreateRequest {
    return {
      name: name.trim(),
      description: description.trim() || undefined,
      onboardingDate,
      pan: pan.trim().toUpperCase(),
      address: address.trim() || undefined,
      salesPersonId: salesPersonId ?? undefined,
      licenseType: licenseType || undefined,
      contacts: contacts
        // An untouched "Additional contact" fieldset is not a contact.
        .filter((c, i) => i === 0 || c.name.trim() || c.email.trim())
        .map((c, i) => ({
          name: c.name.trim(),
          email: c.email.trim(),
          phone: c.phone.trim() || undefined,
          // The first fieldset is the mockup's "Primary SPOC *" — position is
          // the declaration, exactly one by construction.
          isPrimary: i === 0,
        })),
      applications: applications.map((a) => ({
        productId: a.productId,
        licenseType: a.licenseType || undefined,
        units: a.units.trim() ? Number(a.units) : undefined,
      })),
      requirements: requirements.trim() ? [{ bodyHtml: textToHtml(requirements) }] : [],
      createPortalLogin,
      acknowledgeSimilarNames,
      ...overrides,
    }
  }

  /**
   * Takes an explicit override for `acknowledgeSimilarNames` rather than
   * reading it off state, because the "create anyway" button's `onClick`
   * calls `setAcknowledgeSimilarNames(true)` and this in the same tick — a
   * setter's new value is not visible through this closure until the next
   * render, so a resubmit that trusted the state variable would send the
   * *previous* `false` and repeat the same 409 forever.
   */
  async function handleSubmit(overrides?: { acknowledgeSimilarNames?: boolean }) {
    const errors = finishErrors()
    if (errors.length > 0) {
      setBannerError(errors[0])
      return
    }

    setBannerError(null)
    setFieldErrors({})
    setSimilarNameCandidates(null)
    setPortalLoginNotice(false)

    try {
      const created = await create.mutateAsync({ data: buildRequestBody(overrides) })
      navigate(`/onboarding/clients/${created.data.id}`)
    } catch (error) {
      if (!(error instanceof ApiError)) {
        setBannerError('Something went wrong. Try again.')
        return
      }

      if (error.is(OB_CLIENT_PORTAL_LOGIN_UNAVAILABLE)) {
        // Nothing was written — this guard fires before the client row does.
        // Uncheck and explain, rather than leave a box ticked that will fail
        // the same way every time it is resubmitted.
        setCreatePortalLogin(false)
        setPortalLoginNotice(true)
        goTo(4)
        return
      }

      if (error.is(OB_CLIENT_NAME_SIMILAR)) {
        // Deliberately not `goTo(1)`. The confirmation this needs — "these
        // are different companies, create anyway" — is a decision made about
        // the submit that just happened, not a field to go back and retype,
        // so it renders on step 4 beside the button that triggered it.
        const candidates = (error.problem as { candidates?: { id: number; name: string }[] }).candidates ?? []
        setSimilarNameCandidates(candidates)
        return
      }

      if (error.is(OB_CLIENT_NO_PREREQ_MASTER)) {
        setBannerError(
          'No prerequisites checklist is published yet. An onboarding admin needs to publish '
          + 'one on the Prerequisites master (OB-14) before a client can be boarded.',
        )
        return
      }

      if (
        error.is(OB_CLIENT_PAN_DUPLICATE)
        || error.is(OB_PRODUCT_NO_TEMPLATE)
        || Object.keys(error.fieldErrors).length > 0
      ) {
        setFieldErrors(error.fieldErrors)
        const [firstField] = Object.keys(error.fieldErrors)
        goTo(firstField ? stepForField(firstField) : 1)
        return
      }

      setBannerError(error.problem.detail ?? error.message)
    }
  }

  const inputClassName =
    'rounded-control border border-border bg-surface px-3 py-2 text-sm text-content ' +
    'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary'

  return (
    <div className="mx-auto flex h-full w-full max-w-3xl flex-col gap-5 p-6">
      {/* ── page head — h1 + the steps indicator row ───────────────────── */}
      <div>
        <h1 className="text-h1 text-content">Board a new client</h1>
        <StepsIndicator current={step} furthest={furthestStep} onSelect={goTo} />
      </div>

      {/* ── the mockup's one error banner ──────────────────────────────── */}
      {bannerError && (
        <div role="alert" className="rounded-control bg-danger-soft px-3 py-2 text-sm text-danger-text">
          {bannerError}
        </div>
      )}

      <div className="rounded-card border border-border bg-surface p-5">
        {step === 1 && (
          <section aria-labelledby="step-1-heading" className="grid gap-x-4 gap-y-4 sm:grid-cols-2">
            <h2 id="step-1-heading" className="sr-only">Client basics</h2>

            <div className="sm:col-span-2">
              <FormField id="wizard-name" label="Client name" required error={fieldErrors.name?.[0]}>
                {(aria) => (
                  <Input {...aria} value={name} placeholder="e.g. Meadowbrook High School"
                    onChange={(e) => setName(e.target.value)} />
                )}
              </FormField>
            </div>

            <FormField
              id="wizard-pan"
              label="PAN"
              required
              hint="Checked for duplicates · encrypted at rest · masked for non-finance roles"
              error={fieldErrors.pan?.[0]}
            >
              {(aria) => (
                <Input {...aria} value={pan} className="uppercase"
                  onChange={(e) => setPan(e.target.value.toUpperCase())}
                  placeholder="ABCDE1234F" maxLength={10} />
              )}
            </FormField>

            <FormField id="wizard-onboarding-date" label="Date of onboarding" required
              error={fieldErrors.onboardingDate?.[0]}>
              {(aria) => (
                <Input {...aria} type="date" value={onboardingDate}
                  onChange={(e) => setOnboardingDate(e.target.value)} />
              )}
            </FormField>

            {/* The mockup also has a City input here. `ObClientCreateRequest`
                carries no such field — a contract gap, reported rather than
                sent to be dropped. */}

            <FormField id="wizard-license-type" label="License type">
              {(aria) => (
                <select {...aria} value={licenseType} className={inputClassName + ' w-full'}
                  onChange={(e) => setLicenseType(e.target.value)}>
                  <option value="">Select…</option>
                  {CLIENT_LICENSE_TYPES.map((o) => (
                    <option key={o} value={o}>{o}</option>
                  ))}
                </select>
              )}
            </FormField>

            <FormField id="wizard-sales-person" label="Sales person" error={fieldErrors.salesPersonId?.[0]}>
              {(aria) => (
                <SearchableDropdown
                  {...aria}
                  options={users}
                  value={users.find((u) => u.id === salesPersonId) ?? null}
                  onChange={(u) => setSalesPersonId(u.id)}
                  getKey={(u) => String(u.id)}
                  getLabel={(u) => u.displayName}
                  getSearchable={(u) => [u.email ?? '']}
                  placeholder="Search people…"
                />
              )}
            </FormField>

            <div className="sm:col-span-2">
              <FormField id="wizard-address" label="Registered address">
                {(aria) => <Input {...aria} value={address} onChange={(e) => setAddress(e.target.value)} />}
              </FormField>
            </div>

            <div className="sm:col-span-2">
              <FormField id="wizard-description" label="Client description">
                {(aria) => (
                  <Input {...aria} value={description} placeholder="One line about the client"
                    onChange={(e) => setDescription(e.target.value)} />
                )}
              </FormField>
            </div>
          </section>
        )}

        {step === 2 && (
          <section aria-labelledby="step-2-heading" className="flex flex-col gap-4">
            <h2 id="step-2-heading" className="sr-only">SPOC contacts</h2>
            {fieldErrors.contacts && (
              <p role="alert" className="text-caption text-danger-text">{fieldErrors.contacts[0]}</p>
            )}
            {contacts.map((contact, index) => (
              <fieldset key={contact.key} className="rounded-card border border-border p-4">
                <legend className="px-1.5 text-caption font-semibold uppercase tracking-wide text-content-muted">
                  {index === 0 ? 'Primary SPOC *' : 'Additional contact'}
                </legend>
                <div className="grid gap-3 sm:grid-cols-3">
                  <label className="flex flex-col gap-1">
                    <span className="text-caption font-medium text-content-muted">Name</span>
                    <Input
                      value={contact.name}
                      onChange={(e) =>
                        setContacts((current) =>
                          current.map((c, i) => (i === index ? { ...c, name: e.target.value } : c)))
                      }
                    />
                  </label>
                  <label className="flex flex-col gap-1">
                    <span className="text-caption font-medium text-content-muted">Email</span>
                    <Input
                      type="email"
                      value={contact.email}
                      onChange={(e) =>
                        setContacts((current) =>
                          current.map((c, i) => (i === index ? { ...c, email: e.target.value } : c)))
                      }
                    />
                  </label>
                  <label className="flex flex-col gap-1">
                    <span className="text-caption font-medium text-content-muted">Phone / WhatsApp</span>
                    <Input
                      value={contact.phone}
                      placeholder="+91 …"
                      onChange={(e) =>
                        setContacts((current) =>
                          current.map((c, i) => (i === index ? { ...c, phone: e.target.value } : c)))
                      }
                    />
                    <span className="text-caption text-content-muted">
                      WhatsApp opt-in is recorded at first send
                    </span>
                  </label>
                </div>
                {index > 0 && (
                  <Button
                    type="button" variant="ghost" size="sm" className="mt-2"
                    onClick={() => setContacts((current) => current.filter((_, i) => i !== index))}
                  >
                    Remove
                  </Button>
                )}
              </fieldset>
            ))}
            <Button
              type="button" variant="secondary" size="sm" className="self-start"
              onClick={() => setContacts((current) => [...current, blankContact()])}
            >
              + Add another contact
            </Button>
          </section>
        )}

        {step === 3 && (
          <section aria-labelledby="step-3-heading" className="flex flex-col gap-3">
            <h2 id="step-3-heading" className="sr-only">Commercials</h2>
            {fieldErrors.applications && (
              <p role="alert" className="text-caption text-danger-text">{fieldErrors.applications[0]}</p>
            )}
            {applications.map((application) => {
              const chosen = products.find((p) => p.id === application.productId)
              // The row keeps its own product and offers the unused rest — two
              // rows for one product would be two journeys from one purchase.
              const options = selectable.filter(
                (p) => p.id === application.productId
                  || !applications.some((a) => a.productId === p.id),
              )
              return (
                <div key={application.key} className="grid items-end gap-3 sm:grid-cols-[2fr_1fr_1fr_auto]">
                  <label className="flex flex-col gap-1">
                    <span className="text-caption font-medium text-content-muted">Application</span>
                    <select
                      className={inputClassName}
                      value={application.productId}
                      onChange={(e) => updateApplication(application.key, { productId: Number(e.target.value) })}
                    >
                      {options.map((p) => (
                        <option key={p.id} value={p.id}>{p.name}</option>
                      ))}
                    </select>
                  </label>
                  <label className="flex flex-col gap-1">
                    <span className="text-caption font-medium text-content-muted">License</span>
                    <select
                      className={inputClassName}
                      value={application.licenseType}
                      onChange={(e) => updateApplication(application.key, { licenseType: e.target.value })}
                    >
                      <option value="">Select…</option>
                      {APPLICATION_LICENSE_TYPES.map((o) => (
                        <option key={o} value={o}>{o}</option>
                      ))}
                    </select>
                  </label>
                  <label className="flex flex-col gap-1">
                    <span className="text-caption font-medium text-content-muted">Units / seats</span>
                    <Input
                      type="number" min={1}
                      value={application.units}
                      onChange={(e) => updateApplication(application.key, { units: e.target.value })}
                    />
                  </label>
                  <Button
                    type="button" variant="ghost" size="sm"
                    aria-label={`Remove ${chosen?.name ?? 'application'}`}
                    onClick={() => toggleProduct(application.productId, false)}
                  >
                    ✕
                  </Button>
                </div>
              )
            })}
            <Button
              type="button" variant="secondary" size="sm" className="self-start"
              onClick={addApplication}
              disabled={selectable.every((p) => applications.some((a) => a.productId === p.id))}
            >
              + Add application
            </Button>
            <p className="text-caption text-content-muted">
              No financial capture here by design — commercials live in the sales/billing system,
              not the onboarding module.
            </p>
          </section>
        )}

        {step === 4 && (
          <section aria-labelledby="step-4-heading" className="flex flex-col gap-5">
            <h2 id="step-4-heading" className="sr-only">Requirements &amp; journeys</h2>

            {similarNameCandidates && similarNameCandidates.length > 0 && (
              <div role="alert" className="rounded-card border border-warning bg-warning-soft p-4 text-sm">
                <p className="font-medium text-content">
                  A client with a very similar name already exists:
                </p>
                <ul className="ml-4 mt-1 list-disc text-content-muted">
                  {similarNameCandidates.map((c) => (
                    <li key={c.id}>{c.name}</li>
                  ))}
                </ul>
                <Button
                  type="button" size="sm" className="mt-3"
                  onClick={() => {
                    setAcknowledgeSimilarNames(true)
                    setSimilarNameCandidates(null)
                    void handleSubmit({ acknowledgeSimilarNames: true })
                  }}
                >
                  These are different companies — create anyway
                </Button>
              </div>
            )}

            <FormField id="wizard-requirements" label="Client requirements" required
              error={fieldErrors.requirements?.[0]}>
              {(aria) => (
                <textarea
                  {...aria}
                  rows={4}
                  value={requirements}
                  placeholder="What did we promise? Migration scope, integrations, languages, timelines…"
                  onChange={(e) => setRequirements(e.target.value)}
                  className={inputClassName + ' w-full'}
                />
              )}
            </FormField>

            <div>
              <p className="mb-1 text-sm font-medium text-content">
                Products bought — one journey per product
              </p>
              {fieldErrors.applications && (
                <p role="alert" className="mb-1 text-caption text-danger-text">
                  {fieldErrors.applications[0]}
                </p>
              )}
              <div className="flex flex-col gap-1.5">
                {products.map((product) => {
                  const selected = applications.some((a) => a.productId === product.id)
                  const disabled = product.hasActiveTemplate === false
                  return (
                    <label key={product.id} className="flex items-center gap-2 text-sm font-medium text-content">
                      <input
                        type="checkbox"
                        className="h-4 w-4 rounded border-border text-primary focus:ring-2 focus:ring-primary disabled:opacity-50"
                        checked={selected}
                        disabled={disabled}
                        onChange={(e) => toggleProduct(product.id, e.target.checked)}
                      />
                      {product.name}
                      <span className="text-caption font-normal text-content-muted">
                        {disabled
                          ? 'no active template — define one first'
                          : product.totalTatDays != null
                            ? `→ active template · ${product.totalTatDays} working-day TAT`
                            : ''}
                      </span>
                    </label>
                  )
                })}
                {products.length === 0 && (
                  <p className="text-caption text-content-muted">No active products in the catalogue.</p>
                )}
              </div>
              <p className="mt-1.5 text-caption text-content-muted">
                Each product instantiates its own journey (locked until prerequisites clear);
                templates pin their version.
              </p>
            </div>

            <div className="flex items-start gap-2.5">
              <input
                id="wizard-portal-login"
                type="checkbox"
                className="mt-0.5 h-4 w-4 rounded border-border text-primary focus:ring-2 focus:ring-primary"
                checked={createPortalLogin}
                onChange={(e) => {
                  setCreatePortalLogin(e.target.checked)
                  setPortalLoginNotice(false)
                }}
              />
              <div className="flex flex-col">
                <span className="text-sm font-medium text-content">
                  {/* The glyph sits outside the <label> so the label's text —
                      what assistive tech and tests match on — is the words. */}
                  <span aria-hidden>🔑 </span>
                  <label htmlFor="wizard-portal-login">Create client portal login now</label>
                </span>
                <span className="text-caption text-content-muted">
                  Username auto-generated; one-time password emailed to the primary SPOC — can also
                  be done later from the client page.
                </span>
                {portalLoginNotice && (
                  <span role="alert" className="mt-1 text-caption text-warning-text">
                    Portal logins are not available from this screen yet — unchecked. The client can still be
                    created without one; add a login later from the client page once it ships.
                  </span>
                )}
              </div>
            </div>

            {/* The mockup also has an Attachments box here. There is no
                attachment field on the create call — files are per-client
                endpoints that exist only after the client does. Contract gap,
                reported. */}
          </section>
        )}
      </div>

      {/* ── the mockup's footer: Back/Cancel · spacer · Continue/finish ── */}
      <div className="mt-auto flex items-center gap-2.5 border-t border-border pt-4">
        {step > 1 ? (
          <Button type="button" variant="secondary" onClick={handleBack}>
            ← Back
          </Button>
        ) : (
          <Button type="button" variant="secondary" onClick={() => navigate('/onboarding/clients')}>
            Cancel
          </Button>
        )}
        <span className="flex-1" />
        {step < 4 ? (
          <Button type="button" onClick={handleContinue}>
            Continue →
          </Button>
        ) : (
          <Button type="button" onClick={() => void handleSubmit()} disabled={create.isPending}>
            {create.isPending
              ? 'Creating…'
              : 'Create client — journeys instantiate locked + login issued'}
          </Button>
        )}
      </div>
    </div>
  )
}

/**
 * The mockup's `steps-ind` row: "✓ 1. Client basics → **2. SPOC contacts** →
 * 3. Commercials → 4. Requirements & journeys". Reached steps are buttons so
 * the keyboard can go back without hunting for Back; unreached ones are inert
 * text, because jumping forward would skip the step guards.
 */
function StepsIndicator({ current, furthest, onSelect }:
  { current: Step; furthest: Step; onSelect: (step: Step) => void }) {
  return (
    <ol aria-label="Wizard progress" className="mt-1.5 flex flex-wrap items-center gap-2 text-caption text-content-muted">
      {STEPS.map(({ number, label }, index) => {
        const reached = number <= furthest
        const done = number < current
        return (
          <React.Fragment key={number}>
            {index > 0 && <span aria-hidden>→</span>}
            <li>
              <button
                type="button"
                disabled={!reached}
                aria-current={current === number ? 'step' : undefined}
                onClick={() => reached && onSelect(number)}
                className={
                  'rounded-control px-1 py-0.5 '
                  + (current === number
                    ? 'font-semibold text-primary'
                    : reached
                      ? 'text-content hover:bg-subtle'
                      : 'text-content-muted')
                }
              >
                {done && <span aria-hidden>✓ </span>}
                {number}. {label}
              </button>
            </li>
          </React.Fragment>
        )
      })}
    </ol>
  )
}
