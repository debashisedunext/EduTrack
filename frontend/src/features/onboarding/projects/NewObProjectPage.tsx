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
import {
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { toast } from '@/components/ui/use-toast'

import { FormField } from '@/features/masters/resources/FormField'
import { useObClients } from '@/features/onboarding/clients/obClientMasterQueries'

import { activeServicesOf, categoryOf } from './moduleServicePicker'
import { existingProjectIdFrom, useCreateObProject } from './projectQueries'

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
  const [unchecked, setUnchecked] = React.useState<Set<number>>(new Set())
  const [errors, setErrors] = React.useState<Record<string, string>>({})
  const [duplicateProjectId, setDuplicateProjectId] = React.useState<number | null>(null)

  // 200 covers every client the org has today and keeps the picker one request.
  // A client list long enough to need paging needs a searchable picker, which is
  // a different control and a different task.
  const { data: clientPage } = useObClients({ limit: 200 })
  const { data: products } = useListObProducts({ isActive: true })
  const { data: users } = useListUsers()
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
    const found: Record<string, string> = {}
    if (!name.trim()) found.name = 'Give the project a name.'
    if (clientId == null) found.clientId = 'Choose a client.'
    if (productId == null) found.productId = 'Choose the product bought.'
    if (!startDate) found.startDate = 'Give the project a start date.'
    if (productId != null && services.length === 0 && !servicesPending) {
      found.productId =
        'This product publishes no active module service, so there is nothing to board the client through.'
    }
    if (productId != null && services.length > 0 && selectedIds.length === 0) {
      found.moduleServiceIds = 'Keep at least one module service — a project with none has nothing to run.'
    }
    setErrors(found)
    if (Object.keys(found).length > 0) return

    create.mutate(
      {
        name: name.trim(),
        clientId: clientId!,
        productId: productId!,
        startDate,
        salesPersonId,
        implementorUserId,
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
    <form className="mx-auto flex max-w-4xl flex-col gap-6 p-6" onSubmit={onSubmit}>
      <header>
        <h1 className="text-2xl font-semibold text-content">New project</h1>
        <p className="mt-1 max-w-2xl text-sm text-content-muted">
          One journey is created for each module service you keep checked. They stay locked until
          the client&rsquo;s prerequisites clear — everyone can see the plan from day one without a
          clock running against it.
        </p>
      </header>

      <section className="grid grid-cols-1 gap-4 rounded-card border border-default bg-surface p-4 sm:grid-cols-2">
        <FormField id="project-name" label="Project name" required error={errors.name}>
          {(aria) => (
            <Input
              {...aria}
              value={name}
              maxLength={200}
              autoFocus
              onChange={(e) => setName(e.target.value)}
            />
          )}
        </FormField>

        <FormField id="project-client" label="Client name" required error={errors.clientId}>
          {(aria) => (
            <SearchableDropdown
              {...aria}
              options={clients}
              value={clients.find((c) => c.id === clientId) ?? null}
              onChange={(c) => setClientId(c.id)}
              getKey={(c) => String(c.id)}
              getLabel={(c) => c.name}
              // The code and the city are how two similarly named trusts are
              // told apart, and both are typed into this box as often as the
              // name is.
              getSearchable={(c) => [c.clientCode ?? '', c.city ?? '']}
              placeholder="Search clients…"
            />
          )}
        </FormField>

        <FormField id="project-start" label="Project start date" required error={errors.startDate}>
          {(aria) => (
            <Input
              {...aria}
              type="date"
              value={startDate}
              onChange={(e) => setStartDate(e.target.value)}
            />
          )}
        </FormField>

        <FormField id="project-sales" label="Sales person" error={errors.salesPersonId}>
          {(aria) => (
            <SearchableDropdown
              {...aria}
              options={people}
              value={people.find((u) => u.id === salesPersonId) ?? null}
              onChange={(u) => setSalesPersonId(u.id)}
              getKey={(u) => String(u.id)}
              getLabel={(u) => u.displayName}
              getSearchable={(u) => [u.email ?? '']}
              placeholder="Search people…"
            />
          )}
        </FormField>

        <FormField
          id="project-implementor"
          label="Implementor"
          hint="Can be assigned later — the grid shows an em dash until then."
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
              placeholder="Search people…"
            />
          )}
        </FormField>

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
              placeholder="Search products…"
            />
          )}
        </FormField>
      </section>

      <section className="flex flex-col gap-3 rounded-card border border-default bg-surface p-4">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h2 className="text-base font-semibold text-content">Module services</h2>
          {productId != null && services.length > 0 ? (
            <p className="text-sm text-content-muted">
              {selectedIds.length} of {services.length} selected · total TAT {selectedTat} working
              days
            </p>
          ) : null}
        </div>

        {productId == null ? (
          <p className="text-sm text-content-muted">
            Choose a product and its module services appear here, every one checked.
          </p>
        ) : servicesPending ? (
          <Skeleton className="h-32 w-full" />
        ) : services.length === 0 ? (
          <p className="text-sm text-warning-text">
            This product has no published module service yet, so there is nothing to board a client
            through.{' '}
            <Link to="/onboarding/journey-templates" className="text-primary hover:underline">
              Publish one first
            </Link>
            .
          </p>
        ) : (
          <>
            <TableContainer>
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead scope="col" className="w-12">
                      <span className="sr-only">Include</span>
                    </TableHead>
                    <TableHead scope="col">Module service</TableHead>
                    <TableHead scope="col">Category</TableHead>
                    <TableHead scope="col" className="w-20 text-right">
                      Tasks
                    </TableHead>
                    <TableHead scope="col" className="w-20 text-right">
                      TAT
                    </TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {services.map((service) => (
                    <ServiceRow
                      key={service.id}
                      service={service}
                      checked={!unchecked.has(service.id)}
                      onToggle={() => toggle(service.id)}
                    />
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
            <p className="text-caption text-content-muted">
              Unchecking a service creates no journey for it. Nothing is lost — the service stays in
              the catalogue, and a client who buys it later gets a project of their own.
            </p>
            {errors.moduleServiceIds ? (
              <p role="alert" className="text-caption text-danger-text">
                {errors.moduleServiceIds}
              </p>
            ) : null}
          </>
        )}
      </section>

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
            <Link to={`/onboarding/projects/${duplicateProjectId}`}>Open the existing project</Link>
          </Button>
        </div>
      ) : null}

      <div className="flex justify-end gap-2">
        <Button asChild variant="secondary">
          <Link to="/onboarding/projects">Cancel</Link>
        </Button>
        <Button type="submit" disabled={create.isPending}>
          {create.isPending ? 'Creating…' : 'Create project'}
        </Button>
      </div>
    </form>
  )
}

function ServiceRow({
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
    <TableRow className={checked ? undefined : 'opacity-60'}>
      <TableCell>
        <input
          id={id}
          type="checkbox"
          checked={checked}
          onChange={onToggle}
          aria-label={`Include ${service.name}`}
        />
      </TableCell>
      <TableCell className="font-medium text-content">
        <label htmlFor={id}>{service.name}</label>
      </TableCell>
      <TableCell>
        {categories.length === 0 ? (
          <span className="text-caption text-content-muted">No stages defined</span>
        ) : (
          <div className="flex flex-wrap gap-1">
            {categories.map((category) => (
              <Chip key={category}>{category}</Chip>
            ))}
          </div>
        )}
      </TableCell>
      <TableCell className="text-right tabular-nums text-content-muted">
        {service.stepCount}
      </TableCell>
      <TableCell className="text-right tabular-nums text-content-muted">
        {service.totalTatDays} d
      </TableCell>
    </TableRow>
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
