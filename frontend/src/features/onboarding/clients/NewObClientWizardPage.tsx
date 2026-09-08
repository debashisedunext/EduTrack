import * as React from 'react'
import { useNavigate } from 'react-router-dom'

import { useCreateObClient } from '@/api/generated/onboarding/onboarding'
import { useListObProducts } from '@/api/generated/onboarding-masters/onboarding-masters'
import { useListUsers } from '@/api/generated/users/users'
import type { ObClientCreateRequest } from '@/api/generated/model/obClientCreateRequest'
import { ObConsentSource } from '@/api/generated/model/obConsentSource'
import { ApiError } from '@/api/http'

import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { RichTextEditor } from '@/components/ui/rich-text-editor'
import { SearchableDropdown } from '@/components/ui/searchable-dropdown'
import { isRichTextEmpty } from '@/components/ui/rich-text'

import { FormField } from '@/features/masters/resources/FormField'

import {
  OB_CLIENT_NO_PREREQ_MASTER,
  OB_CLIENT_NAME_SIMILAR,
  OB_CLIENT_PAN_DUPLICATE,
  OB_CLIENT_PORTAL_LOGIN_UNAVAILABLE,
  OB_PRODUCT_NO_TEMPLATE,
} from './obClientWizardProblems'

const PAN_PATTERN = /^[A-Z]{5}[0-9]{4}[A-Z]$/

const STEPS = [
  { number: 1, label: 'Client details' },
  { number: 2, label: 'Contacts' },
  { number: 3, label: 'Products & requirements' },
  { number: 4, label: 'Review & create' },
] as const

type Step = (typeof STEPS)[number]['number']

interface ContactDraft {
  key: string
  name: string
  designation: string
  email: string
  phone: string
  whatsappOptIn: boolean
  /** A closed vocabulary, not free text — `ObConsentSource`'s own reason: this is what a challenged consent is defended with. */
  whatsappOptInSource: ObConsentSource | ''
  isPrimary: boolean
}

interface RequirementDraft {
  key: string
  title: string
  bodyHtml: string
}

interface ApplicationDraft {
  productId: number
  licenseType: string
  units: string
  licenseStart: string
  licenseEnd: string
}

let draftKeySeq = 0
function draftKey(): string {
  draftKeySeq += 1
  return `draft-${draftKeySeq}`
}

function blankContact(isPrimary: boolean): ContactDraft {
  return {
    key: draftKey(), name: '', designation: '', email: '', phone: '',
    whatsappOptIn: false, whatsappOptInSource: '', isPrimary,
  }
}

function blankRequirement(): RequirementDraft {
  return { key: draftKey(), title: '', bodyHtml: '' }
}

/** Which step a server-reported field belongs to, so a 400/409 can jump the wizard there. */
function stepForField(field: string): Step {
  if (field.startsWith('contacts')) return 2
  if (field.startsWith('applications') || field.startsWith('requirements')) return 3
  if (field === 'name' || field === 'onboardingDate' || field === 'pan'
    || field === 'address' || field === 'salesPersonId' || field === 'licenseType') return 1
  return 4
}

/**
 * B-109 · OB-04, the four-step new client wizard — `/onboarding/clients/new`.
 *
 * ## What already existed and what this closes
 *
 * `ObClientWriteService.create` (B-102) already accepts everything one screen
 * needs: identity, contacts, the product multi-select, requirements, the
 * duplicate guards and the portal-login checkbox, all in one atomic call that
 * instantiates a locked journey per product. This page is the first caller —
 * `ObClientListPage`'s own note has said since B-108 that a "New client"
 * button pointing nowhere is worse than none, and this is where it now
 * points. The service-side gap this branch also closes — the create was
 * never actually snapshotting a prerequisites checklist, despite its own
 * javadoc saying B-109 would call it — is `ObClientWriteService`'s, not this
 * page's; see that class for the fix. This page is also where the module's
 * navigation entry is added (`Sidebar.tsx`) — every other onboarding route's
 * comment has deferred it here since B-112.
 *
 * ## Four steps, one submit
 *
 * Nothing is written until step 4. There is no server round-trip between
 * steps — unlike `ImportWizardPage`, whose steps are the state of a batch
 * that genuinely exists server-side by the time the second step renders, this
 * wizard's first three steps are local form state with nothing to resume, so
 * the current step is an explicit index rather than derived from what the
 * server has recorded.
 *
 * ## The duplicate-PAN guard is "inline" by where the error lands, not by a
 * live check
 *
 * There is no standalone "does this PAN exist" endpoint, and B-109's backlog
 * line does not ask for one — `ObClientExceptionHandler`'s own note is that
 * the wizard shows this 409 *on the same screen* as the forceable name
 * warning, both keyed to `errors.pan` / `errors.name`. "Inline" here means
 * the field-keyed error lands on step 1's PAN input rather than a toast, via
 * `stepForField`, which is the whole mechanism.
 *
 * ## `createPortalLogin` is real UI over a refusal that is still real
 *
 * `PortalLoginUnavailableException`'s own javadoc says B-126 deletes it. Until
 * then the checkbox is shown — the plan names it as part of this screen and a
 * hidden checkbox is not what "explicit" means — but checking it and
 * submitting today comes back `409 ob-client-portal-login-unavailable` before
 * anything is written. `handleSubmit` below unchecks it and explains, rather
 * than pretending the box does nothing.
 */
export function NewObClientWizardPage() {
  const navigate = useNavigate()

  const [step, setStep] = React.useState<Step>(1)
  const [furthestStep, setFurthestStep] = React.useState<Step>(1)

  // ── step 1 ──────────────────────────────────────────────────────────────
  const [name, setName] = React.useState('')
  const [description, setDescription] = React.useState('')
  const [onboardingDate, setOnboardingDate] = React.useState('')
  const [pan, setPan] = React.useState('')
  const [address, setAddress] = React.useState('')
  const [salesPersonId, setSalesPersonId] = React.useState<number | null>(null)
  const [licenseType, setLicenseType] = React.useState('')

  // ── step 2 ──────────────────────────────────────────────────────────────
  const [contacts, setContacts] = React.useState<ContactDraft[]>(() => [blankContact(true)])

  // ── step 3 ──────────────────────────────────────────────────────────────
  const [applications, setApplications] = React.useState<ApplicationDraft[]>([])
  const [requirements, setRequirements] = React.useState<RequirementDraft[]>([])

  // ── step 4 ──────────────────────────────────────────────────────────────
  const [createPortalLogin, setCreatePortalLogin] = React.useState(false)
  const [acknowledgeSimilarNames, setAcknowledgeSimilarNames] = React.useState(false)
  const [similarNameCandidates, setSimilarNameCandidates] =
    React.useState<{ id: number; name: string }[] | null>(null)
  const [portalLoginNotice, setPortalLoginNotice] = React.useState(false)

  const [blockingError, setBlockingError] = React.useState<string | null>(null)
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

  // ── step 1 validation ──────────────────────────────────────────────────
  function step1Errors(): string[] {
    const errors: string[] = []
    if (!name.trim()) errors.push('A client needs a name.')
    if (!onboardingDate) errors.push('The onboarding date is required.')
    if (pan.trim() && !PAN_PATTERN.test(pan.trim().toUpperCase())) {
      errors.push('PAN must be five letters, four digits, one letter — e.g. ABCDE1234F.')
    }
    return errors
  }

  // ── step 2 validation ──────────────────────────────────────────────────
  function step2Errors(): string[] {
    const errors: string[] = []
    if (contacts.length === 0) errors.push('At least one SPOC is required.')
    const primaries = contacts.filter((c) => c.isPrimary).length
    if (contacts.length > 0 && primaries !== 1) {
      errors.push('Exactly one contact must be marked primary.')
    }
    contacts.forEach((c, i) => {
      if (!c.name.trim() || !c.email.trim()) {
        errors.push(`Contact ${i + 1} needs a name and an email.`)
      }
      if (c.whatsappOptIn && !c.whatsappOptInSource.trim()) {
        errors.push(`Contact ${i + 1}: say where WhatsApp consent came from.`)
      }
    })
    return errors
  }

  // ── step 3 validation ──────────────────────────────────────────────────
  function step3Errors(): string[] {
    const errors: string[] = []
    if (applications.length === 0) errors.push('Select at least one product.')
    return errors
  }

  const stepErrors: Record<Step, string[]> = {
    1: step1Errors(), 2: step2Errors(), 3: step3Errors(), 4: [],
  }

  function handleNext() {
    if (step === 4) return
    const errors = stepErrors[step]
    if (errors.length > 0) return
    goTo((step + 1) as Step)
  }

  function toggleProduct(productId: number, selected: boolean) {
    setApplications((current) =>
      selected
        ? [...current, { productId, licenseType: '', units: '', licenseStart: '', licenseEnd: '' }]
        : current.filter((a) => a.productId !== productId),
    )
  }

  function updateApplication(productId: number, patch: Partial<ApplicationDraft>) {
    setApplications((current) =>
      current.map((a) => (a.productId === productId ? { ...a, ...patch } : a)),
    )
  }

  function buildRequestBody(overrides?: Partial<ObClientCreateRequest>): ObClientCreateRequest {
    return {
      name: name.trim(),
      description: description.trim() || undefined,
      onboardingDate,
      pan: pan.trim() ? pan.trim().toUpperCase() : undefined,
      address: address.trim() || undefined,
      salesPersonId: salesPersonId ?? undefined,
      licenseType: licenseType.trim() || undefined,
      contacts: contacts.map((c) => ({
        name: c.name.trim(),
        designation: c.designation.trim() || undefined,
        email: c.email.trim(),
        phone: c.phone.trim() || undefined,
        whatsappOptIn: c.whatsappOptIn,
        whatsappOptInSource: c.whatsappOptIn && c.whatsappOptInSource ? c.whatsappOptInSource : undefined,
        isPrimary: c.isPrimary,
      })),
      applications: applications.map((a) => ({
        productId: a.productId,
        licenseType: a.licenseType.trim() || undefined,
        units: a.units.trim() ? Number(a.units) : undefined,
        licenseStart: a.licenseStart || undefined,
        licenseEnd: a.licenseEnd || undefined,
      })),
      requirements: requirements
        .filter((r) => !isRichTextEmpty(r.bodyHtml))
        .map((r) => ({ title: r.title.trim() || undefined, bodyHtml: r.bodyHtml })),
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
    setBlockingError(null)
    setFieldErrors({})
    setSimilarNameCandidates(null)
    setPortalLoginNotice(false)

    try {
      const created = await create.mutateAsync({ data: buildRequestBody(overrides) })
      navigate(`/onboarding/clients/${created.data.id}`)
    } catch (error) {
      if (!(error instanceof ApiError)) {
        setBlockingError('Something went wrong. Try again.')
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
        // so it renders on step 4 beside the button that triggered it. Only
        // reachable from step 4 in the first place: it is the one guard that
        // fires on `Create client`, never on `Next`.
        const candidates = (error.problem as { candidates?: { id: number; name: string }[] }).candidates ?? []
        setSimilarNameCandidates(candidates)
        return
      }

      if (error.is(OB_CLIENT_NO_PREREQ_MASTER)) {
        setBlockingError(
          'No prerequisites checklist is published yet. An onboarding admin needs to publish '
          + 'one on the Prerequisites master (OB-14) before a client can be boarded.',
        )
        return
      }

      if (error.is(OB_CLIENT_PAN_DUPLICATE) || error.is(OB_PRODUCT_NO_TEMPLATE)) {
        setFieldErrors(error.fieldErrors)
        const [firstField] = Object.keys(error.fieldErrors)
        goTo(firstField ? stepForField(firstField) : 1)
        return
      }

      if (Object.keys(error.fieldErrors).length > 0) {
        setFieldErrors(error.fieldErrors)
        const [firstField] = Object.keys(error.fieldErrors)
        goTo(firstField ? stepForField(firstField) : 1)
        return
      }

      setBlockingError(error.problem.detail ?? error.message)
    }
  }

  return (
    <div className="mx-auto flex h-full max-w-3xl flex-col gap-6 p-6">
      <div>
        <h1 className="text-h1 text-content">New client</h1>
        <p className="text-caption text-content-muted">
          OB-04 · one journey per product bought, locked until the prerequisites checklist clears.
        </p>
      </div>

      <StepRail current={step} furthest={furthestStep} onSelect={goTo} />

      {blockingError && (
        <div role="alert" className="rounded-card border border-danger bg-danger-soft p-4 text-sm text-danger-text">
          {blockingError}
        </div>
      )}

      {step === 1 && (
        <section aria-labelledby="step-1-heading" className="flex flex-col gap-5">
          <h2 id="step-1-heading" className="sr-only">Client details</h2>

          <FormField id="wizard-name" label="Client name" required error={fieldErrors.name?.[0]}>
            {(aria) => <Input {...aria} value={name} onChange={(e) => setName(e.target.value)} />}
          </FormField>

          <FormField id="wizard-onboarding-date" label="Onboarding date" required
            error={fieldErrors.onboardingDate?.[0]}>
            {(aria) => (
              <Input {...aria} type="date" value={onboardingDate}
                onChange={(e) => setOnboardingDate(e.target.value)} />
            )}
          </FormField>

          <FormField id="wizard-pan" label="PAN" hint="Identity only — never a financial field."
            error={fieldErrors.pan?.[0]}>
            {(aria) => (
              <Input {...aria} value={pan}
                onChange={(e) => setPan(e.target.value.toUpperCase())}
                placeholder="ABCDE1234F" maxLength={10} />
            )}
          </FormField>

          <FormField id="wizard-address" label="Address">
            {(aria) => <Input {...aria} value={address} onChange={(e) => setAddress(e.target.value)} />}
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

          <FormField id="wizard-license-type" label="License type">
            {(aria) => <Input {...aria} value={licenseType} onChange={(e) => setLicenseType(e.target.value)} />}
          </FormField>

          <FormField id="wizard-description" label="Description">
            {(aria) => (
              <textarea {...aria} value={description} onChange={(e) => setDescription(e.target.value)}
                rows={3}
                className="rounded-control border border-border bg-surface px-3 py-2 text-sm text-content" />
            )}
          </FormField>

          <ErrorList errors={step1Errors()} />
        </section>
      )}

      {step === 2 && (
        <section aria-labelledby="step-2-heading" className="flex flex-col gap-4">
          <h2 id="step-2-heading" className="sr-only">Contacts</h2>
          {fieldErrors.contacts && (
            <p role="alert" className="text-caption text-danger-text">{fieldErrors.contacts[0]}</p>
          )}
          {contacts.map((contact, index) => (
            <ContactRow
              key={contact.key}
              contact={contact}
              canRemove={contacts.length > 1}
              onChange={(patch) =>
                setContacts((current) => current.map((c, i) => (i === index ? { ...c, ...patch } : c)))
              }
              onMakePrimary={() =>
                setContacts((current) => current.map((c, i) => ({ ...c, isPrimary: i === index })))
              }
              onRemove={() =>
                setContacts((current) => current.filter((_, i) => i !== index))
              }
            />
          ))}
          <Button
            type="button" variant="secondary" size="sm" className="self-start"
            onClick={() => setContacts((current) => [...current, blankContact(current.length === 0)])}
          >
            Add contact
          </Button>
          <ErrorList errors={step2Errors()} />
        </section>
      )}

      {step === 3 && (
        <section aria-labelledby="step-3-heading" className="flex flex-col gap-6">
          <div>
            <h2 id="step-3-heading" className="mb-2 text-h3 text-content">Products</h2>
            {fieldErrors.applications && (
              <p role="alert" className="mb-2 text-caption text-danger-text">{fieldErrors.applications[0]}</p>
            )}
            <div className="flex flex-col gap-2">
              {products.map((product) => {
                const selected = applications.some((a) => a.productId === product.id)
                const application = applications.find((a) => a.productId === product.id)
                const disabled = product.hasActiveTemplate === false
                return (
                  <div key={product.id} className="rounded-card border border-border p-3">
                    <label className="flex items-start gap-2.5">
                      <input
                        type="checkbox"
                        className="mt-0.5 h-4 w-4 rounded border-border text-primary focus:ring-2 focus:ring-primary disabled:opacity-50"
                        checked={selected}
                        disabled={disabled}
                        onChange={(e) => toggleProduct(product.id, e.target.checked)}
                      />
                      <span className="flex flex-col">
                        <span className="text-sm font-medium text-content">
                          {product.name} <span className="text-content-muted">({product.code})</span>
                        </span>
                        {disabled && (
                          <span className="text-caption text-warning-text">
                            No published journey template yet — cannot be bought.
                          </span>
                        )}
                      </span>
                    </label>
                    {selected && application && (
                      <div className="mt-3 grid gap-3 pl-6 sm:grid-cols-2">
                        <Input
                          aria-label={`${product.name} license type`}
                          placeholder="License type"
                          value={application.licenseType}
                          onChange={(e) => updateApplication(product.id, { licenseType: e.target.value })}
                        />
                        <Input
                          aria-label={`${product.name} units`}
                          type="number" min={1} placeholder="Units"
                          value={application.units}
                          onChange={(e) => updateApplication(product.id, { units: e.target.value })}
                        />
                        <Input
                          aria-label={`${product.name} license start`}
                          type="date"
                          value={application.licenseStart}
                          onChange={(e) => updateApplication(product.id, { licenseStart: e.target.value })}
                        />
                        <Input
                          aria-label={`${product.name} license end`}
                          type="date"
                          value={application.licenseEnd}
                          onChange={(e) => updateApplication(product.id, { licenseEnd: e.target.value })}
                        />
                      </div>
                    )}
                  </div>
                )
              })}
              {products.length === 0 && (
                <p className="text-caption text-content-muted">No active products in the catalogue.</p>
              )}
            </div>
            <ErrorList errors={step3Errors()} />
          </div>

          <div>
            <h2 className="mb-2 text-h3 text-content">Requirements</h2>
            <p className="mb-3 text-caption text-content-muted">
              Optional. Anything the client has already told you they need.
            </p>
            <div className="flex flex-col gap-4">
              {requirements.map((requirement, index) => (
                <div key={requirement.key} className="rounded-card border border-border p-3">
                  <div className="mb-2 flex items-center gap-2">
                    <Input
                      aria-label="Requirement title"
                      placeholder="Title (optional)"
                      value={requirement.title}
                      onChange={(e) =>
                        setRequirements((current) =>
                          current.map((r, i) => (i === index ? { ...r, title: e.target.value } : r)))
                      }
                      className="flex-1"
                    />
                    <Button
                      type="button" variant="ghost" size="sm"
                      onClick={() => setRequirements((current) => current.filter((_, i) => i !== index))}
                    >
                      Remove
                    </Button>
                  </div>
                  <RichTextEditor
                    aria-label="Requirement detail"
                    value={requirement.bodyHtml}
                    onChange={(html) =>
                      setRequirements((current) =>
                        current.map((r, i) => (i === index ? { ...r, bodyHtml: html } : r)))
                    }
                    rows={3}
                  />
                </div>
              ))}
              <Button
                type="button" variant="secondary" size="sm" className="self-start"
                onClick={() => setRequirements((current) => [...current, blankRequirement()])}
              >
                Add requirement
              </Button>
            </div>
          </div>
        </section>
      )}

      {step === 4 && (
        <section aria-labelledby="step-4-heading" className="flex flex-col gap-5">
          <h2 id="step-4-heading" className="sr-only">Review &amp; create</h2>

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

          <dl className="grid grid-cols-2 gap-x-4 gap-y-2 rounded-card border border-border p-4 text-sm">
            <dt className="text-content-muted">Name</dt>
            <dd className="text-content">{name || '—'}</dd>
            <dt className="text-content-muted">Onboarding date</dt>
            <dd className="text-content">{onboardingDate || '—'}</dd>
            <dt className="text-content-muted">Contacts</dt>
            <dd className="text-content">{contacts.length}</dd>
            <dt className="text-content-muted">Products (locked journeys)</dt>
            <dd className="text-content">
              {applications
                .map((a) => products.find((p) => p.id === a.productId)?.name ?? `#${a.productId}`)
                .join(', ') || '—'}
            </dd>
            <dt className="text-content-muted">Requirements</dt>
            <dd className="text-content">
              {requirements.filter((r) => !isRichTextEmpty(r.bodyHtml)).length}
            </dd>
          </dl>

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
              <label htmlFor="wizard-portal-login" className="text-sm text-content">
                Create client portal login now
              </label>
              <span className="text-caption text-content-muted">
                A one-time password goes to the primary SPOC. Never automatic — this is the only way it happens.
              </span>
              {portalLoginNotice && (
                <span role="alert" className="mt-1 text-caption text-warning-text">
                  Portal logins are not available from this screen yet — unchecked. The client can still be
                  created without one; add a login later from the client page once it ships.
                </span>
              )}
            </div>
          </div>

          <Button onClick={() => void handleSubmit()} disabled={create.isPending}>
            {create.isPending ? 'Creating…' : 'Create client'}
          </Button>
        </section>
      )}

      <div className="mt-auto flex items-center justify-between border-t border-border pt-4">
        <Button type="button" variant="secondary" disabled={step === 1}
          onClick={() => goTo((step - 1) as Step)}>
          Back
        </Button>
        {step < 4 && (
          <Button type="button" onClick={handleNext} disabled={stepErrors[step].length > 0}>
            Next
          </Button>
        )}
      </div>
    </div>
  )
}

function StepRail({ current, furthest, onSelect }:
  { current: Step; furthest: Step; onSelect: (step: Step) => void }) {
  return (
    <ol className="flex items-center gap-2" aria-label="Wizard progress">
      {STEPS.map(({ number, label }, index) => {
        const reached = number <= furthest
        return (
          <React.Fragment key={number}>
            {index > 0 && <span aria-hidden className="h-px flex-1 bg-border" />}
            <li>
              <button
                type="button"
                disabled={!reached}
                aria-current={current === number ? 'step' : undefined}
                onClick={() => reached && onSelect(number)}
                className={
                  'flex items-center gap-1.5 rounded-control px-2 py-1 text-caption font-medium '
                  + (current === number
                    ? 'bg-primary-soft text-primary'
                    : reached
                      ? 'text-content hover:bg-subtle'
                      : 'text-content-muted')
                }
              >
                <span className="flex h-5 w-5 items-center justify-center rounded-full border border-current text-xs">
                  {number}
                </span>
                {label}
              </button>
            </li>
          </React.Fragment>
        )
      })}
    </ol>
  )
}

function ErrorList({ errors }: { errors: string[] }) {
  if (errors.length === 0) return null
  return (
    <ul className="list-disc rounded-card bg-subtle p-3 pl-8 text-caption text-content-muted">
      {errors.map((error) => (
        <li key={error}>{error}</li>
      ))}
    </ul>
  )
}

function ContactRow({ contact, canRemove, onChange, onMakePrimary, onRemove }: {
  contact: ContactDraft
  canRemove: boolean
  onChange: (patch: Partial<ContactDraft>) => void
  onMakePrimary: () => void
  onRemove: () => void
}) {
  return (
    <div className="rounded-card border border-border p-3">
      <div className="grid gap-3 sm:grid-cols-2">
        <Input aria-label="Contact name" placeholder="Name" value={contact.name}
          onChange={(e) => onChange({ name: e.target.value })} />
        <Input aria-label="Designation" placeholder="Designation" value={contact.designation}
          onChange={(e) => onChange({ designation: e.target.value })} />
        <Input aria-label="Email" type="email" placeholder="Email" value={contact.email}
          onChange={(e) => onChange({ email: e.target.value })} />
        <Input aria-label="Phone" placeholder="Phone" value={contact.phone}
          onChange={(e) => onChange({ phone: e.target.value })} />
      </div>

      <div className="mt-3 flex flex-wrap items-center gap-4">
        <label className="flex items-center gap-1.5 text-sm text-content">
          <input type="radio" name="primary-contact" checked={contact.isPrimary} onChange={onMakePrimary}
            className="h-4 w-4 border-border text-primary focus:ring-2 focus:ring-primary" />
          Primary SPOC
        </label>

        <label className="flex items-center gap-1.5 text-sm text-content">
          <input type="checkbox" checked={contact.whatsappOptIn}
            onChange={(e) => onChange({ whatsappOptIn: e.target.checked })}
            className="h-4 w-4 rounded border-border text-primary focus:ring-2 focus:ring-primary" />
          WhatsApp opt-in
        </label>

        {contact.whatsappOptIn && (
          <select
            aria-label="Where this consent came from"
            value={contact.whatsappOptInSource}
            onChange={(e) => onChange({ whatsappOptInSource: e.target.value as ObConsentSource | '' })}
            className="min-w-[12rem] flex-1 rounded-control border border-border bg-surface px-3 py-2 text-sm text-content"
          >
            <option value="">Consent basis…</option>
            <option value={ObConsentSource.VERBAL}>Verbal</option>
            <option value={ObConsentSource.EMAIL}>Email</option>
            <option value={ObConsentSource.WRITTEN}>Written</option>
            <option value={ObConsentSource.CONTRACT}>Contract</option>
            <option value={ObConsentSource.CLIENT_PORTAL}>Client portal</option>
          </select>
        )}

        {canRemove && (
          <Button type="button" variant="ghost" size="sm" className="ml-auto" onClick={onRemove}>
            Remove
          </Button>
        )}
      </div>
    </div>
  )
}
