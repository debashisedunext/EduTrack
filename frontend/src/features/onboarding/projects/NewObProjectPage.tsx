import * as React from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'

import { ApiError } from '@/api/http'
import { useListObJourneyTemplates } from '@/api/generated/onboarding-journeys/onboarding-journeys'
import { useListObProducts } from '@/api/generated/onboarding-masters/onboarding-masters'
import { useListUsers } from '@/api/generated/users/users'
import type { ObJourneyTemplateSummary } from '@/api/generated/model/obJourneyTemplateSummary'

import { Button } from '@/components/ui/button'
import { Chip } from '@/components/ui/chip'
import { Input } from '@/components/ui/input'
import { SearchableDropdown } from '@/components/ui/searchable-dropdown'
import { Skeleton } from '@/components/ui/skeleton'
import { toast } from '@/components/ui/use-toast'
import { cn } from '@/lib/utils'

import { FormField } from '@/features/masters/resources/FormField'
import { useObClients } from '@/features/onboarding/clients/obClientMasterQueries'

import { activeServicesOf, categoryOf } from './moduleServicePicker'
import { validateNewProject } from './newProjectForm'
import { existingProjectIdFrom, useCreateObProject } from './projectQueries'
import { useObManagerOptions } from './useObManagerOptions'

/**
 * The New Project form — `/onboarding/projects/new`.
 *
 * <h2>Choosing the product loads the services, all checked</h2>
 *
 * A product publishes any number of Module Services, and a client buying it is
 * normally boarded through every one. So the picker arrives fully checked and
 * the work is *unpicking* — which is the right default because the common case
 * is "all of them", and because a form that starts empty invites somebody to
 * save a project with no journeys at all.
 *
 * Unchecking every row is refused by the server (`minItems: 1`) and by the
 * submit button here, for the same reason: a project with no journey has no
 * ribbon and nothing to report, which is a purchase record rather than a
 * project.
 *
 * <h2>The Category column is a column, not a grouping</h2>
 *
 * Each row names the implementation stages that service covers — its stage
 * groups, from the Module Service designer. They are printed *beside* the
 * service rather than used as group headers, and that is forced rather than
 * chosen: a service spanning Configuration, UAT and Go-live cannot sit under
 * three headings at once, and repeating its row under each would repeat its
 * checkbox — one checkbox per journey is what the model has.
 *
 * <h2>Only active services are offered</h2>
 *
 * `listObJourneyTemplates` returns every version of every service, because the
 * OB-07 catalogue draws version history. Boarding a client onto a retired
 * version would pin them to a template the admin has replaced, so this form
 * takes the active head of each chain and nothing else — `activeServicesOf`.
 */
export function NewObProjectPage() {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const create = useCreateObProject()

  const [name, setName] = React.useState('')
  const [clientId, setClientId] = React.useState<number | null>(numberParam(params.get('clientId')))
  const [productId, setProductId] = React.useState<number | null>(null)
  const [startDate, setStartDate] = React.useState(today())
  const [salesPersonId, setSalesPersonId] = React.useState<number | null>(null)
  const [implementorUserId, setImplementorUserId] = React.useState<number | null>(null)
  const [implementorManagerUserId, setImplementorManagerUserId] = React.useState<number | null>(null)
  const [unchecked, setUnchecked] = React.useState<Set<number>>(new Set())
  const [errors, setErrors] = React.useState<Record<string, string>>({})
  const [duplicateProjectId, setDuplicateProjectId] = React.useState<number | null>(null)

  // 200 covers every client the org has today and keeps the picker one request.
  // A client list long enough to need paging needs a searchable picker, which is
  // a different control and a different task.
  const { data: clientPage } = useObClients({ limit: 200 })
  const { data: products } = useListObProducts({ isActive: true })
  const { data: users } = useListUsers()
  // Narrower than `people` on purpose — only somebody the review routes will
  // accept may be named here. See `useObManagerOptions`.
  const { managers } = useObManagerOptions()
  const { data: templates, isPending: servicesPending } = useListObJourneyTemplates(
    { productId: productId ?? undefined },
    // The catalogue read is meaningless without a product — asking for every
    // service of every product would load the whole catalogue to render nothing.
    { query: { enabled: productId != null } },
  )

  const clients = React.useMemo(() => clientPage?.data ?? [], [clientPage])
  const people = React.useMemo(() => users?.data ?? [], [users])
  const productList = React.useMemo(() => products?.data ?? [], [products])

  const services = React.useMemo(
    () => activeServicesOf(templates?.data ?? [], productId),
    [templates, productId],
  )

  /*
    Changing the product invalidates the selection entirely: the unchecked set
    holds ids of the *previous* product's services, and carrying them over would
    silently unpick rows of the new one that happened to share an id.
  */
  React.useEffect(() => {
    setUnchecked(new Set())
    setDuplicateProjectId(null)
  }, [productId])

  /*
    The client's name, which the project falls back to when the name box is left
    blank — "leave blank to name it after the client" on the field, resolved
    here so the submit and the placeholder cannot disagree about what that name
    would be.
  */
  const clientName = clients.find((c) => c.id === clientId)?.name ?? null

  const selectedIds = services.filter((s) => !unchecked.has(s.id)).map((s) => s.id)
  const selectedTat = services
    .filter((s) => !unchecked.has(s.id))
    .reduce((total, s) => total + (s.totalTatDays ?? 0), 0)

  const toggle = (templateId: number) =>
    setUnchecked((current) => {
      const next = new Set(current)
      if (next.has(templateId)) next.delete(templateId)
      else next.add(templateId)
      return next
    })

  const onSubmit = (event: React.FormEvent) => {
    event.preventDefault()
    const found = validateNewProject({
      name,
      clientId,
      productId,
      startDate,
      salesPersonId,
      implementorUserId,
      implementorManagerUserId,
      serviceCount: services.length,
      selectedCount: selectedIds.length,
      servicesPending,
    })
    setErrors(found)
    if (Object.keys(found).length > 0) return

    create.mutate(
      {
        name: name.trim() || (clientName ?? ''),
        clientId: clientId!,
        productId: productId!,
        startDate,
        salesPersonId: salesPersonId!,
        implementorUserId: implementorUserId!,
        implementorManagerUserId: implementorManagerUserId!,
        moduleServiceIds: selectedIds,
      },
      {
        /*
          Back to the grid, not into the ribbon.

          Landing on the new project's own page was the first behaviour, and it
          is the wrong one for how this form is actually used: a project is
          created by somebody boarding a client, and the next thing they do is
          either create another or look at where this one sits among the rest.
          Dropping them into a ribbon whose every step is still locked behind
          the prerequisite gate shows them nothing they can act on and costs a
          click to get back.

          The grid is ordered newest-created first, so the project they just
          made is the top row — which is the confirmation the toast is only
          repeating.
        */
        onSuccess: (project) => {
          toast({ title: `${project.name} created` })
          navigate('/onboarding/projects')
        },
        onError: (error) => {
          const existing = existingProjectIdFrom(error)
          if (existing != null) {
            setDuplicateProjectId(existing)
            return
          }
          const mapped = fieldErrorsFrom(error)
          if (Object.keys(mapped).length > 0) setErrors(mapped)
          else
            toast({
              title: 'The project was not created',
              description: error.problem.detail,
            })
        },
      },
    )
  }

  return (
    <form className="mx-auto flex w-full max-w-[74rem] flex-col gap-5 p-6" onSubmit={onSubmit}>
      <header>
        <h1 className="text-2xl font-semibold text-content">Start a project</h1>
        <p className="mt-1 max-w-2xl text-sm text-content-muted">
          Choosing the product copies its module services, their steps, tasks and check lists into
          this project. Uncheck any the client is not taking — one journey is created for each you
          keep.
        </p>
      </header>

      {/*
        Two columns: what has to be decided on the left, when it happens — and
        the button that commits it — on the right. The rail is where the form is
        submitted from because that is where its last unanswered question is;
        a submit at the foot of a three-card column is a scroll away from the
        card somebody is still filling in.
      */}
      <div className="grid grid-cols-1 items-start gap-5 lg:grid-cols-[minmax(0,1fr)_21rem]">
        <div className="flex flex-col gap-5">
          <Card title="Client">
            <FormField
              id="project-client"
              label="Which client is this for?"
              required
              error={errors.clientId}
            >
              {(aria) => (
                <SearchableDropdown
                  {...aria}
                  options={clients}
                  value={clients.find((c) => c.id === clientId) ?? null}
                  onChange={(c) => setClientId(c.id)}
                  getKey={(c) => String(c.id)}
                  getLabel={(c) => c.name}
                  // The code and the city are how two similarly named trusts
                  // are told apart, and both are typed into this box as often
                  // as the name is.
                  getSearchable={(c) => [c.clientCode ?? '', c.city ?? '']}
                  placeholder="Choose a client…"
                />
              )}
            </FormField>

            <FormField
              id="project-name"
              label="Project name"
              hint="Leave blank to name it after the client."
              error={errors.name}
            >
              {(aria) => (
                <Input
                  {...aria}
                  value={name}
                  maxLength={200}
                  placeholder={clientName ?? ''}
                  onChange={(e) => setName(e.target.value)}
                />
              )}
            </FormField>
          </Card>

          <Card
            title="Products to implement"
            aside={
              productId != null && services.length > 0 ? (
                <p className="text-caption tabular-nums text-content-muted">
                  {selectedIds.length} of {services.length} selected · {selectedTat} working days of
                  standard effort
                </p>
              ) : null
            }
          >
            <FormField id="project-product" label="Product bought" required error={errors.productId}>
              {(aria) => (
                <SearchableDropdown
                  {...aria}
                  options={productList}
                  value={productList.find((p) => p.id === productId) ?? null}
                  onChange={(p) => setProductId(p.id)}
                  getKey={(p) => String(p.id)}
                  getLabel={(p) => p.name}
                  getSearchable={(p) => [p.code]}
                  placeholder="Choose a product…"
                />
              )}
            </FormField>

            {productId == null ? (
              <p className="text-sm text-content-muted">
                Choose a product and its module services appear here, every one checked.
              </p>
            ) : servicesPending ? (
              <Skeleton className="h-28 w-full" />
            ) : services.length === 0 ? (
              <p className="text-sm text-warning-text">
                This product has no published module service yet, so there is nothing to board a
                client through.{' '}
                <Link to="/onboarding/journey-templates" className="text-primary hover:underline">
                  Publish one first
                </Link>
                .
              </p>
            ) : (
              <>
                <ul className="m-0 flex list-none flex-col gap-0.5 p-0">
                  {services.map((service) => (
                    <ServiceOption
                      key={service.id}
                      service={service}
                      checked={!unchecked.has(service.id)}
                      onToggle={() => toggle(service.id)}
                    />
                  ))}
                </ul>
                <p className="text-caption text-content-muted">
                  Unchecking a service creates no journey for it. Nothing is lost — the service
                  stays in the catalogue, and a client who buys it later gets a project of their
                  own.
                </p>
                {errors.moduleServiceIds ? (
                  <p role="alert" className="text-caption text-danger-text">
                    {errors.moduleServiceIds}
                  </p>
                ) : null}
              </>
            )}
          </Card>

          <Card title="Team">
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <FormField
                id="project-implementor"
                label="Implementor"
                required
                hint="Every task with no responsible on its module service falls to this person."
                error={errors.implementorUserId}
              >
                {(aria) => (
                  <SearchableDropdown
                    {...aria}
                    options={people}
                    value={people.find((u) => u.id === implementorUserId) ?? null}
                    onChange={(u) => setImplementorUserId(u.id)}
                    getKey={(u) => String(u.id)}
                    getLabel={(u) => u.displayName}
                    getSearchable={(u) => [u.email ?? '']}
                    placeholder="Choose…"
                  />
                )}
              </FormField>

              <FormField
                id="project-implementor-manager"
                label="Implementor manager"
                required
                hint="Who this project escalates to, and who verifies its check lists. Onboarding managers and admins only — anybody else is refused by the review routes. Not read from the implementor's reporting line: a project can be overseen by somebody they do not report to."
                error={errors.implementorManagerUserId}
              >
                {(aria) => (
                  <SearchableDropdown
                    {...aria}
                    options={[...managers]}
                    value={managers.find((u) => u.id === implementorManagerUserId) ?? null}
                    onChange={(u) => setImplementorManagerUserId(u.id)}
                    getKey={(u) => String(u.id)}
                    getLabel={(u) => u.displayName}
                    getSearchable={(u) => [u.email ?? '']}
                    placeholder="Choose…"
                  />
                )}
              </FormField>

              <FormField
                id="project-sales"
                label="Sales person"
                required
                error={errors.salesPersonId}
              >
                {(aria) => (
                  <SearchableDropdown
                    {...aria}
                    options={people}
                    value={people.find((u) => u.id === salesPersonId) ?? null}
                    onChange={(u) => setSalesPersonId(u.id)}
                    getKey={(u) => String(u.id)}
                    getLabel={(u) => u.displayName}
                    getSearchable={(u) => [u.email ?? '']}
                    placeholder="Choose…"
                  />
                )}
              </FormField>
            </div>
          </Card>

          {duplicateProjectId != null ? (
            <div className="rounded-card border border-warning bg-warning-soft p-3 text-sm">
              <p className="font-medium text-warning-text">
                This client already has a project for that product
              </p>
              <p className="mt-1 text-content-muted">
                A client runs one project per product. Open the existing one, or choose a different
                product.
              </p>
              <Button asChild variant="secondary" className="mt-2">
                <Link to={`/onboarding/projects/${duplicateProjectId}`}>
                  Open the existing project
                </Link>
              </Button>
            </div>
          ) : null}
        </div>

        <Card title="Dates">
          <FormField id="project-start" label="Planned start" required error={errors.startDate}>
            {(aria) => (
              <Input
                {...aria}
                type="date"
                value={startDate}
                onChange={(e) => setStartDate(e.target.value)}
              />
            )}
          </FormField>

          {/*
            Target go-live is read, not typed. The server pins it from the
            working calendar — weekends, org holidays and resource leave — so a
            date box here would let somebody enter a day the calendar will not
            agree with, and the project would then carry two answers. What the
            form can say before it is created is the budget it is derived from.
          */}
          <div>
            <p className="text-sm font-medium text-content">Target go-live</p>
            <p className="mt-1 text-caption text-content-muted">
              {productId != null && selectedIds.length > 0
                ? `Set on creation — ${selectedTat} working days of TAT from the planned start, across the working calendar.`
                : 'Set on creation, from the TAT of the module services you keep.'}
            </p>
          </div>

          <div className="flex flex-col gap-2 border-t border-default pt-4">
            <Button type="submit" disabled={create.isPending} className="w-full justify-center">
              {create.isPending ? 'Creating…' : 'Create project'}
            </Button>
            <Button asChild variant="secondary" className="w-full justify-center">
              <Link to="/onboarding/projects">Cancel</Link>
            </Button>
            <p className="text-caption text-content-muted">
              Every journey is created at once and stays locked until the client&rsquo;s
              prerequisites clear — so the plan is visible from day one without a clock running
              against it.
            </p>
          </div>
        </Card>
      </div>
    </form>
  )
}

/**
 * One titled block of the form.
 *
 * <p>Local to this page rather than shared: it is a heading, a rule and a
 * padded body, and the moment it becomes a component in `components/ui` it
 * grows variants for every other screen's idea of a card.
 */
function Card({
  title,
  aside,
  children,
}: {
  title: string
  /** A figure or note on the heading line — the selected-services count. */
  aside?: React.ReactNode
  children: React.ReactNode
}) {
  return (
    <section className="rounded-card border border-default bg-surface">
      <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1 border-b border-default px-4 py-3">
        <h2 className="text-base font-semibold text-content">{title}</h2>
        {aside}
      </div>
      <div className="flex flex-col gap-4 p-4">{children}</div>
    </section>
  )
}

/**
 * One module service, as a row somebody ticks.
 *
 * <p>It was a five-column table — include, service, category, tasks, TAT —
 * which is the right shape for comparing forty rows and the wrong one for a
 * list of three you are choosing between: the header row alone was as tall as
 * the rows it described. The figures that mattered are now a caption under the
 * name, and the whole row is the label, so the click target is the line rather
 * than a 13px box at the left of it.
 */
function ServiceOption({
  service,
  checked,
  onToggle,
}: {
  service: ObJourneyTemplateSummary
  checked: boolean
  onToggle: () => void
}) {
  const categories = categoryOf(service)
  const id = `service-${service.id}`
  return (
    <li>
      <label
        htmlFor={id}
        className={cn(
          'flex cursor-pointer items-start gap-3 rounded-control px-2 py-2 hover:bg-subtle',
          !checked && 'opacity-65',
        )}
      >
        <input
          id={id}
          type="checkbox"
          checked={checked}
          onChange={onToggle}
          className="mt-[3px]"
          aria-label={`Include ${service.name}`}
        />
        <span className="min-w-0">
          <span className="flex flex-wrap items-center gap-x-2 gap-y-1">
            <span className="text-sm font-semibold text-content">{service.name}</span>
            {categories.map((category) => (
              <Chip key={category}>{category}</Chip>
            ))}
          </span>
          <span className="mt-0.5 block text-caption tabular-nums text-content-muted">
            {service.stepCount} {service.stepCount === 1 ? 'task' : 'tasks'} ·{' '}
            {service.totalTatDays} working {service.totalTatDays === 1 ? 'day' : 'days'} of standard
            effort
            {categories.length === 0 ? ' · no stages defined' : ''}
          </span>
        </span>
      </label>
    </li>
  )
}

/** Field-keyed 400s land on their own input rather than in a toast. */
function fieldErrorsFrom(e: ApiError): Record<string, string> {
  const problem = e.problem as { errors?: Record<string, string[]> }
  const server = problem.errors ?? {}
  const mapped: Record<string, string> = {}
  for (const [key, messages] of Object.entries(server)) {
    if (messages?.[0]) mapped[key] = messages[0]
  }
  return mapped
}

/** `yyyy-mm-dd` in the viewer's own zone — a project usually starts today. */
function today(): string {
  const now = new Date()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${now.getFullYear()}-${month}-${day}`
}

function numberParam(value: string | null): number | null {
  if (!value) return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}
