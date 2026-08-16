import * as React from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { Controller, useForm, type Resolver } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { ArrowLeft } from 'lucide-react'

import { useListProjects } from '@/api/generated/projects/projects'
import { useListUsers } from '@/api/generated/users/users'
import { ApiError } from '@/api/http'

import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { SearchableDropdown } from '@/components/ui/searchable-dropdown'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { toast } from '@/components/ui/use-toast'
import { cn } from '@/lib/utils'

import { FieldGroup, FormField, ReadOnlyField } from '../masters/resources/FormField'
import { SkillsInput } from '../masters/resources/SkillsInput'

import { ClientContactsTab } from './ClientContactsTab'
import { ClientProjectsPicker } from './ClientProjectsPicker'
import { useClient, useCreateClient, useUpdateClient } from './clientQueries'
import {
  CLIENT_STATUSES,
  CLIENT_TABS,
  clientFieldSchema,
  clientFormSchema,
  emptyClientForm,
  SUPPORT_PLANS,
  tabForErrors,
  toFormValues,
  toWriteRequest,
  type ClientFormValues,
  type ClientTab,
} from './clientForm'

/**
 * S-33 Client Master — Create / Edit (B-026).
 *
 * One page for both verbs, for the reason `ProjectFormPage` and
 * `ResourceFormPage` both give: they are one form, every field and validation is
 * shared, and the only differences are the verb and whether an `ETag` is in play.
 * Two components would be the same file twice with one copy always slightly
 * behind.
 *
 * <h2>The tabs are state, not routes — and that is the opposite of `ProjectTabs`</h2>
 *
 * S-10's four tabs are four routes because each owns a different resource with a
 * different `ETag`: the General tab patches the project, the SLA tab replaces a
 * matrix, and no shared parent read could have served both.
 *
 * S-33's four tabs are **one resource behind one Save button**. Identity,
 * Commercial and Projects & SLA are all fields of `ClientWriteRequest`, saved by
 * one `PATCH` against one tag. Making them routes would mean either four reads of
 * the same client or losing every unsaved edit on a tab change — a form that
 * discards what you typed when you go to check something on another tab is worse
 * than one long page. So the tabs are `useState`, the form instance spans all
 * four, and the dirty state survives switching.
 *
 * The consequence is that a server error can name a field on a tab the admin
 * cannot see. `tabForErrors` is what handles it: the form opens the tab the first
 * bad field is on, so the message is visible rather than marked on a hidden
 * input.
 *
 * <h2>What is deliberately not here</h2>
 *
 * - **Contact writes.** B-027 owns add/edit/remove; this tab reads. See
 *   `ClientContactsTab`.
 * - **An SLA policy picker.** Nothing reads `clients.sla_policy_id` — C-012's
 *   ladder resolves org → project → task type and never consults it — so a
 *   picker here would be a control whose only effect is to write a number nobody
 *   looks at. The stored value is shown, preserved on save, and the tab points at
 *   B-018's matrix, which is where a project's SLA is actually set. **Flagged**
 *   rather than faked.
 * - **A delete button.** There is none and there cannot be: tickets, contacts and
 *   project mappings all point at `clients`, and §4B.2 says deactivating must
 *   never hide historical tickets. Going away is the status control on the grid.
 */
export function ClientFormPage() {
  const navigate = useNavigate()
  const params = useParams<{ clientId?: string }>()

  const clientId = params.clientId ? Number(params.clientId) : null
  const isEdit = clientId != null

  const { data: loaded, isPending: loading, isError: loadFailed } = useClient(clientId)

  const [tab, setTab] = React.useState<ClientTab>('Identity')
  const [bannerError, setBannerError] = React.useState<string | null>(null)

  // Every active resource is a candidate account manager. The role is
  // deliberately not filtered — B-016 made the same call for a project manager,
  // and a hardcoded role set is what B-015 removed from `ResourceController`.
  const { data: managersData } = useListUsers({ isActive: true, limit: 200 })
  const managers = React.useMemo(() => managersData?.data ?? [], [managersData])

  // Unfiltered on purpose — see `ClientProjectsPicker`: a mapping to a closed
  // project is a fact about the relationship, and hiding it here would delete it
  // on the next save.
  const { data: projectsData } = useListProjects({ limit: 200 })
  const projects = React.useMemo(() => projectsData?.data ?? [], [projectsData])

  const form = useForm<ClientFormValues>({
    resolver: zodResolver(clientFormSchema) as Resolver<ClientFormValues>,
    defaultValues: emptyClientForm,
    mode: 'onBlur',
  })

  // Seeded once the read lands. `reset` rather than `defaultValues` because the
  // form mounts before the fetch resolves, and re-mounting the page on load would
  // throw away focus, scroll position and the tab the admin had opened.
  const seeded = React.useRef(false)
  React.useEffect(() => {
    if (loaded && !seeded.current) {
      seeded.current = true
      form.reset(toFormValues(loaded.client))
    }
  }, [loaded, form])

  const createClient = useCreateClient()
  const updateClient = useUpdateClient()
  const isSaving = createClient.isPending || updateClient.isPending

  function applyServerError(error: unknown): void {
    if (!(error instanceof ApiError)) {
      setBannerError('Something went wrong. Try again.')
      return
    }

    // 412 is not a field error and does not belong on an input: nothing the
    // admin typed is wrong, the row moved underneath them.
    if (error.status === 412) {
      setBannerError(
        'Somebody else saved this client while you were editing. Reload the page and reapply your change.',
      )
      return
    }

    const known = new Set(Object.keys(clientFieldSchema.shape))
    const unmatched: string[] = []

    for (const [key, messages] of Object.entries(error.fieldErrors)) {
      const message = messages[0] ?? 'Invalid'
      if (known.has(key)) {
        form.setError(key as keyof ClientFormValues, { type: 'server', message })
      } else {
        unmatched.push(message)
      }
    }

    const matched = Object.keys(error.fieldErrors).filter((key) => known.has(key))
    if (matched.length > 0) {
      // Open the tab the first bad field is on *before* focusing it. A four-tab
      // form otherwise marks an input the admin cannot see, and the save reads
      // as having failed silently.
      const target = tabForErrors(matched)
      if (target) setTab(target)
      const first = matched[0] as keyof ClientFormValues
      window.setTimeout(() => form.setFocus(first), 0)
      return
    }
    setBannerError(unmatched[0] ?? error.problem.detail ?? 'The client was not saved.')
  }

  const onSubmit = form.handleSubmit(
    (values) => {
      setBannerError(null)

      if (isEdit && clientId != null) {
        updateClient.mutate(
          { clientId, data: toWriteRequest(values), etag: loaded?.etag ?? null },
          {
            onSuccess: (client) => {
              toast({ title: `${client.name} saved` })
              navigate('/masters/clients')
            },
            onError: applyServerError,
          },
        )
        return
      }

      createClient.mutate(toWriteRequest(values), {
        onSuccess: (client) => {
          toast({
            title: `${client.name} created`,
            description:
              'Add a primary contact before this client can be chosen on a ticket.',
          })
          navigate('/masters/clients')
        },
        onError: applyServerError,
      })
    },
    // The client-side mirror of the same problem: zod refusing a field on a tab
    // that is not open would show nothing at all.
    (errors) => {
      const target = tabForErrors(Object.keys(errors))
      if (target) setTab(target)
    },
  )

  if (isEdit && loading) {
    return <Skeleton className="m-6 h-96" />
  }
  if (isEdit && loadFailed) {
    return (
      <div className="mx-auto max-w-3xl p-6">
        <p className="text-sm text-danger-text">That client could not be loaded.</p>
        <Button asChild variant="secondary" className="mt-4">
          <Link to="/masters/clients">Back to clients</Link>
        </Button>
      </div>
    )
  }

  const client = loaded?.client ?? null

  return (
    <form onSubmit={onSubmit} className="mx-auto flex max-w-3xl flex-col gap-6 p-6">
      <header className="flex flex-col gap-3">
        <Link
          to="/masters/clients"
          className="inline-flex w-fit items-center gap-1 text-sm text-content-muted hover:text-content"
        >
          <ArrowLeft className="h-4 w-4" aria-hidden />
          Clients
        </Link>

        <div className="flex flex-wrap items-baseline gap-3">
          <h1 className="text-2xl font-semibold text-content">
            {isEdit ? (client?.name ?? 'Client') : 'New client'}
          </h1>
          {client?.clientCode ? (
            <code className="text-sm text-content-muted">{client.clientCode}</code>
          ) : null}
        </div>

        {/*
          A real tab list. `aria-selected` and roving `tabIndex` are what tell a
          screen reader which panel is showing, and arrow keys move between them
          — WCAG AA is not optional (CLAUDE.md). Buttons rather than links,
          because these are panels of one form and not four addresses.
        */}
        <div role="tablist" aria-label="Client sections" className="border-b border-border">
          <ul className="flex gap-1">
            {CLIENT_TABS.map((name) => {
              const isActive = name === tab
              return (
                <li key={name}>
                  <button
                    type="button"
                    role="tab"
                    id={`tab-${slug(name)}`}
                    aria-selected={isActive}
                    aria-controls={`panel-${slug(name)}`}
                    tabIndex={isActive ? 0 : -1}
                    onClick={() => setTab(name)}
                    onKeyDown={(e) => handleTabKeys(e, name, setTab)}
                    className={cn(
                      'inline-block border-b-2 px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary',
                      isActive
                        ? 'border-primary font-medium text-content'
                        : 'border-transparent text-content-muted hover:text-content',
                    )}
                  >
                    {name}
                  </button>
                </li>
              )
            })}
          </ul>
        </div>
      </header>

      {bannerError ? (
        <div
          role="alert"
          className="rounded-card border border-danger bg-surface px-4 py-3 text-sm text-danger-text"
        >
          {bannerError}
        </div>
      ) : null}

      {/*
        Every panel stays mounted and is hidden with `hidden`, not unmounted.
        react-hook-form unregisters an input that leaves the DOM, so unmounting a
        tab would drop the values on it from the submitted payload — and this
        form sends every field on every save. The bug would be invisible until
        somebody edited a client's address and found their notes had been
        cleared.
      */}
      <div role="tabpanel" id="panel-identity" aria-labelledby="tab-identity" hidden={tab !== 'Identity'}>
        <div className="flex flex-col gap-6">
          <FieldGroup title="Identity" description="Who this client is, and how they are recognised.">
            <FormField
              id="clientCode"
              label="Client code"
              required
              error={form.formState.errors.clientCode?.message}
              hint="Unique, and the key the Excel import matches on — changing it means the next import creates a second client instead of updating this one."
            >
              {(aria) => (
                <Input
                  {...aria}
                  {...form.register('clientCode')}
                  placeholder="ACME"
                  onChange={(e) => form.setValue('clientCode', e.target.value.toUpperCase())}
                />
              )}
            </FormField>

            <FormField
              id="name"
              label="Client name"
              required
              error={form.formState.errors.name?.message}
            >
              {(aria) => <Input {...aria} {...form.register('name')} placeholder="Acme Retail Ltd" />}
            </FormField>

            <FormField
              id="shortName"
              label="Short name"
              error={form.formState.errors.shortName?.message}
              hint="What the grid and the ticket header use when the full name will not fit."
            >
              {(aria) => <Input {...aria} {...form.register('shortName')} placeholder="Acme" />}
            </FormField>

            <FormField
              id="industry"
              label="Industry"
              error={form.formState.errors.industry?.message}
            >
              {(aria) => <Input {...aria} {...form.register('industry')} placeholder="Retail" />}
            </FormField>

            <Controller
              control={form.control}
              name="status"
              render={({ field }) => (
                <FormField
                  id="status"
                  label="Status"
                  required
                  hint={CLIENT_STATUSES.find((s) => s.value === field.value)?.hint}
                >
                  {(aria) => (
                    <Select value={field.value} onValueChange={field.onChange}>
                      <SelectTrigger {...aria}>
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        {CLIENT_STATUSES.map((s) => (
                          <SelectItem key={s.value} value={s.value}>
                            {s.label}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  )}
                </FormField>
              )}
            />

            <FormField
              id="logoUrl"
              label="Logo URL"
              error={form.formState.errors.logoUrl?.message}
              hint="A link, not an upload — the grid reads this row on every page."
            >
              {(aria) => (
                <Input {...aria} {...form.register('logoUrl')} placeholder="https://acme.example/logo.png" />
              )}
            </FormField>
          </FieldGroup>

          <FieldGroup
            title="Contact details"
            description="How this client reaches us, and how inbound mail is matched back to them."
          >
            <FormField
              id="domain"
              label="Website / domain"
              error={form.formState.errors.domain?.message}
              hint="Inbound mail from this domain is attributed to this client. A scheme or www. prefix is stripped on save."
            >
              {(aria) => <Input {...aria} {...form.register('domain')} placeholder="acme.example" />}
            </FormField>

            <FormField
              id="primaryEmail"
              label="Primary email"
              error={form.formState.errors.primaryEmail?.message}
            >
              {(aria) => (
                <Input {...aria} type="email" {...form.register('primaryEmail')} placeholder="hello@acme.example" />
              )}
            </FormField>

            <FormField
              id="supportEmail"
              label="Support email"
              error={form.formState.errors.supportEmail?.message}
              hint="Used for email-to-ticket matching."
            >
              {(aria) => (
                <Input {...aria} type="email" {...form.register('supportEmail')} placeholder="support@acme.example" />
              )}
            </FormField>

            <FormField id="phone" label="Phone" error={form.formState.errors.phone?.message}>
              {(aria) => <Input {...aria} {...form.register('phone')} placeholder="+91 98200 11111" />}
            </FormField>
          </FieldGroup>

          <FieldGroup title="Address">
            <FormField
              id="addressLine1"
              label="Address line 1"
              error={form.formState.errors.addressLine1?.message}
              className="sm:col-span-2"
            >
              {(aria) => <Input {...aria} {...form.register('addressLine1')} />}
            </FormField>

            <FormField
              id="addressLine2"
              label="Address line 2"
              error={form.formState.errors.addressLine2?.message}
              className="sm:col-span-2"
            >
              {(aria) => <Input {...aria} {...form.register('addressLine2')} />}
            </FormField>

            <FormField id="city" label="City" error={form.formState.errors.city?.message}>
              {(aria) => <Input {...aria} {...form.register('city')} />}
            </FormField>

            <FormField id="state" label="State" error={form.formState.errors.state?.message}>
              {(aria) => <Input {...aria} {...form.register('state')} />}
            </FormField>

            <FormField id="country" label="Country" error={form.formState.errors.country?.message}>
              {(aria) => <Input {...aria} {...form.register('country')} />}
            </FormField>

            <FormField
              id="postalCode"
              label="Postal code"
              error={form.formState.errors.postalCode?.message}
            >
              {(aria) => <Input {...aria} {...form.register('postalCode')} />}
            </FormField>

            <FormField
              id="timezone"
              label="Time zone"
              error={form.formState.errors.timezone?.message}
              hint="An IANA zone, like Asia/Kolkata. Display only — everything is stored in UTC."
            >
              {(aria) => <Input {...aria} {...form.register('timezone')} placeholder="Asia/Kolkata" />}
            </FormField>
          </FieldGroup>
        </div>
      </div>

      <div
        role="tabpanel"
        id="panel-commercial"
        aria-labelledby="tab-commercial"
        hidden={tab !== 'Commercial'}
      >
        <div className="flex flex-col gap-6">
          <FieldGroup title="Commercial" description="The account, its contract and its plan.">
            <Controller
              control={form.control}
              name="accountManagerId"
              render={({ field }) => (
                <FormField
                  id="accountManagerId"
                  label="Account manager"
                  error={form.formState.errors.accountManagerId?.message}
                  hint="Any active resource, whatever their role. Watched onto every ticket raised for this client."
                >
                  {(aria) => (
                    <SearchableDropdown
                      {...aria}
                      options={managers}
                      value={managers.find((u) => u.id === field.value) ?? null}
                      onChange={(u) => field.onChange(u?.id ?? 0)}
                      getKey={(u) => String(u.id)}
                      getLabel={(u) => u.displayName}
                      getSearchable={(u) => [u.email ?? '']}
                      placeholder="Search resources…"
                    />
                  )}
                </FormField>
              )}
            />

            <Controller
              control={form.control}
              name="supportPlan"
              render={({ field }) => (
                <FormField id="supportPlan" label="Support plan">
                  {(aria) => (
                    <Select
                      value={field.value === '' ? NONE : field.value}
                      onValueChange={(v) => field.onChange(v === NONE ? '' : v)}
                    >
                      <SelectTrigger {...aria}>
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        {/* A radix Select cannot hold an empty-string value, so
                            "not stated" needs a sentinel. It is mapped back to
                            '' on the way out, which `toWriteRequest` turns into
                            the null the column means. */}
                        <SelectItem value={NONE}>Not stated</SelectItem>
                        {SUPPORT_PLANS.map((plan) => (
                          <SelectItem key={plan.value} value={plan.value}>
                            {plan.label}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  )}
                </FormField>
              )}
            />

            <FormField
              id="contractStart"
              label="Contract start"
              error={form.formState.errors.contractStart?.message}
            >
              {(aria) => <Input {...aria} type="date" {...form.register('contractStart')} />}
            </FormField>

            <FormField
              id="contractEnd"
              label="Contract end"
              error={form.formState.errors.contractEnd?.message}
            >
              {(aria) => <Input {...aria} type="date" {...form.register('contractEnd')} />}
            </FormField>

            <FormField
              id="billingReference"
              label="Billing reference"
              error={form.formState.errors.billingReference?.message}
              hint="Their reference for us — a PO number, a contract id."
            >
              {(aria) => <Input {...aria} {...form.register('billingReference')} />}
            </FormField>

            <FormField
              id="billingEmail"
              label="Billing email"
              error={form.formState.errors.billingEmail?.message}
              hint="Where invoices go. Kept apart from the support address on purpose."
            >
              {(aria) => <Input {...aria} type="email" {...form.register('billingEmail')} />}
            </FormField>
          </FieldGroup>

          <FieldGroup title="Notes & tags" columns={1}>
            <FormField id="notes" label="Account notes" error={form.formState.errors.notes?.message}>
              {(aria) => (
                <textarea
                  {...aria}
                  {...form.register('notes')}
                  rows={4}
                  className="w-full rounded-control border border-border bg-surface px-3 py-2 text-sm text-content focus:outline-none focus:ring-2 focus:ring-primary focus:ring-offset-1"
                  placeholder="Anything the desk should know before they pick up a ticket for this client."
                />
              )}
            </FormField>

            <Controller
              control={form.control}
              name="tags"
              render={({ field }) => (
                <FormField
                  id="tags"
                  label="Tags"
                  error={form.formState.errors.tags?.message}
                  hint="Free text. Enter or comma commits one."
                >
                  {(aria) => (
                    <SkillsInput
                      aria={aria}
                      value={field.value}
                      onChange={field.onChange}
                      max={20}
                    />
                  )}
                </FormField>
              )}
            />
          </FieldGroup>
        </div>
      </div>

      <div
        role="tabpanel"
        id="panel-contacts"
        aria-labelledby="tab-contacts"
        hidden={tab !== 'Contacts'}
      >
        <ClientContactsTab clientId={clientId} />
      </div>

      <div
        role="tabpanel"
        id="panel-projects-sla"
        aria-labelledby="tab-projects-sla"
        hidden={tab !== 'Projects & SLA'}
      >
        <div className="flex flex-col gap-6">
          <FieldGroup
            title="Projects"
            description="Which projects this client can have tickets raised against, and which one is offered first."
            columns={1}
          >
            <Controller
              control={form.control}
              name="projectIds"
              render={({ field }) => (
                <FormField
                  id="projectIds"
                  label="Mapped projects"
                  error={
                    form.formState.errors.projectIds?.message ??
                    form.formState.errors.defaultProjectId?.message
                  }
                >
                  {(aria) => (
                    <ClientProjectsPicker
                      aria={aria}
                      projects={projects}
                      selected={field.value}
                      defaultProjectId={form.watch('defaultProjectId')}
                      onChange={(projectIds, defaultProjectId) => {
                        field.onChange(projectIds)
                        form.setValue('defaultProjectId', defaultProjectId, {
                          shouldDirty: true,
                        })
                      }}
                    />
                  )}
                </FormField>
              )}
            />
          </FieldGroup>

          <FieldGroup title="SLA" columns={1}>
            <ReadOnlyField
              label="Default SLA policy"
              value={client?.slaPolicyId ?? 'Inherited'}
              hint="Read-only. Nothing resolves a client-level policy today — a ticket's SLA comes from the project's matrix, then the org default. Set it on the project's SLA tab."
            />
          </FieldGroup>
        </div>
      </div>

      <div className="flex justify-end gap-3">
        <Button asChild type="button" variant="secondary">
          <Link to="/masters/clients">Cancel</Link>
        </Button>
        <Button type="submit" disabled={isSaving}>
          {isSaving ? 'Saving…' : isEdit ? 'Save client' : 'Create client'}
        </Button>
      </div>
    </form>
  )
}

/** Radix refuses an empty-string `SelectItem` value; this is the stand-in. */
const NONE = '__none__'

function slug(tab: ClientTab): string {
  return tab.toLowerCase().replace(/[^a-z]+/g, '-').replace(/^-|-$/g, '')
}

/** Left/right arrows move between tabs, which is what `role="tablist"` promises. */
function handleTabKeys(
  event: React.KeyboardEvent,
  current: ClientTab,
  setTab: (tab: ClientTab) => void,
): void {
  if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return
  event.preventDefault()
  const index = CLIENT_TABS.indexOf(current)
  const next = event.key === 'ArrowRight' ? index + 1 : index - 1
  const wrapped = (next + CLIENT_TABS.length) % CLIENT_TABS.length
  setTab(CLIENT_TABS[wrapped])
  // Focus follows selection in an automatic tablist, which is what makes the
  // arrow keys useful rather than merely present.
  document.getElementById(`tab-${slug(CLIENT_TABS[wrapped])}`)?.focus()
}
