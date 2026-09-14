import * as React from 'react'
import { Link, useSearchParams } from 'react-router-dom'

import { ApiError } from '@/api/http'
import type { ObClient } from '@/api/generated/model/obClient'
import type { ObPortalLoginIssued } from '@/api/generated/model/obPortalLoginIssued'

import { ObPortalLoginIssuedDialog } from './ObPortalLoginIssuedDialog'

import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/ui/empty-state'
import { Input } from '@/components/ui/input'
import {
  Modal,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
} from '@/components/ui/modal'
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

import {
  blockersFrom,
  useCreateObClient,
  useDeleteObClient,
  useObClient,
  useObClients,
  useUpdateObClient,
} from './obClientMasterQueries'

/**
 * The Clients master — `/onboarding/clients`. List, add, edit, delete, and
 * four fields.
 *
 * <h2>What this replaced, and why the replacement is smaller</h2>
 *
 * The screen here used to be OB-03: six filters, a health chip folding three
 * server fields into one, journey counts, products bought, a per-client
 * "prerequisites pending" state. Every one of those is a fact about an
 * <em>engagement</em>, and engagements have their own screen now — the Projects
 * grid, which answers "how is the ERP rollout going" far better than a client
 * row ever could for a client running two of them.
 *
 * What is left is the company: name, code, address, city. That is the whole
 * master, and it is why adding one takes a dialog rather than a four-step
 * wizard.
 *
 * <h2>Delete is rendered only where it can succeed</h2>
 *
 * The server refuses a delete for a client with projects, a prerequisite
 * checklist, uploaded documents or a portal login, and answers `409` naming
 * which. This page cannot see three of those four — only `journeyCount` is on
 * the row — so it does not try to predict the refusal: the button is offered,
 * and the `409` is surfaced with the blockers the server named plus the way
 * forward, which is Dropped rather than deleted.
 *
 * <p>Showing the button and explaining the refusal beats hiding it. A hidden
 * Delete on the one row somebody typed wrong is a dead end; a refused Delete is
 * a sentence that tells them what to do instead.
 *
 * <h2>The name guard is a two-step confirm, not a blocker</h2>
 *
 * `409 ob-client-name-similar` is advisory — two trusts really can share a name
 * stem — so the dialog shows what the server found and offers to proceed, which
 * re-submits with `acknowledgeSimilarNames`. A duplicate <em>code</em> is never
 * forceable and lands on the code field.
 */
export function ObClientMasterPage() {
  /*
    The search term is in the URL, not in component state. A filtered list is a
    link — and more concretely, the top bar's Enter-to-search in the onboarding
    module navigates to `/onboarding/clients?q=…`, which would land on an empty
    box if this were private state. `useObClientFilters` made the same call for
    OB-03 and for the same reason.
  */
  const [params, setParams] = useSearchParams()
  const search = params.get('q') ?? ''
  const setSearch = (value: string) => {
    const next = new URLSearchParams(params)
    if (value) next.set('q', value)
    else next.delete('q')
    setParams(next, { replace: true })
  }
  const [creating, setCreating] = React.useState(false)
  const [editingId, setEditingId] = React.useState<number | null>(null)
  const [deleting, setDeleting] = React.useState<ObClient | null>(null)

  const { data, isPending, isError } = useObClients({ q: search })
  const clients = data?.data ?? []

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6 p-6">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-content">Clients</h1>
          <p className="mt-1 max-w-2xl text-sm text-content-muted">
            Every company on the books. A client added here can be given a project straight
            away — the project is where the product, the start date, the implementor and the
            module services are chosen.
          </p>
        </div>
        <Button onClick={() => setCreating(true)}>Add client</Button>
      </header>

      <div className="flex flex-wrap items-center gap-3">
        <label htmlFor="client-search" className="sr-only">
          Search clients by name or code
        </label>
        <Input
          id="client-search"
          className="max-w-xs"
          placeholder="Search name or code…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
      </div>

      {isPending ? (
        <Skeleton className="h-64 w-full" />
      ) : isError ? (
        <p className="text-sm text-danger-text">Clients could not be loaded.</p>
      ) : clients.length === 0 ? (
        <EmptyState
          title={search ? 'No clients match that search' : 'No clients yet'}
          description={
            search
              ? 'Try part of the company name, or the client code.'
              : 'Add the first company, then give it a project.'
          }
        />
      ) : (
        <TableContainer>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead scope="col" className="w-32">
                  Client code
                </TableHead>
                <TableHead scope="col">Client name</TableHead>
                <TableHead scope="col">Address</TableHead>
                <TableHead scope="col" className="w-40">
                  City
                </TableHead>
                <TableHead scope="col" className="w-24 text-right">
                  Projects
                </TableHead>
                <TableHead scope="col" className="w-40">
                  <span className="sr-only">Actions</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {clients.map((client) => (
                <ClientRow
                  key={client.id}
                  client={client}
                  onEdit={() => setEditingId(client.id)}
                  onDelete={() => setDeleting(client)}
                />
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      <CreateClientDialog open={creating} onOpenChange={setCreating} />
      <EditClientDialog obClientId={editingId} onClose={() => setEditingId(null)} />
      <DeleteClientDialog client={deleting} onClose={() => setDeleting(null)} />
    </div>
  )
}

function ClientRow({
  client,
  onEdit,
  onDelete,
}: {
  client: ObClient
  onEdit: () => void
  onDelete: () => void
}) {
  /*
    `journeyCount` is journeys, not projects — a project boarded through three
    module services counts three. It is still the honest signal for this
    column's question ("is anything running for this client?"), and the header
    says Projects because that is what a reader is deciding about. The exact
    figure is on the Projects grid, filtered to this client, which is where the
    link goes.
  */
  const running = client.journeyCount ?? 0
  return (
    <TableRow>
      <TableCell className="font-mono text-xs text-content-muted">
        {client.clientCode ?? '—'}
      </TableCell>
      <TableCell className="font-medium text-content">{client.name}</TableCell>
      <TableCell className="text-content-muted">{client.address ?? '—'}</TableCell>
      <TableCell className="text-content-muted">{client.city ?? '—'}</TableCell>
      <TableCell className="text-right tabular-nums">
        {running > 0 ? (
          <Link
            to={`/onboarding/projects?clientId=${client.id}`}
            className="text-primary hover:underline"
            title={`Open this client's projects`}
          >
            {running}
          </Link>
        ) : (
          <span className="text-content-muted">0</span>
        )}
      </TableCell>
      <TableCell>
        <div className="flex gap-2">
          <Button variant="secondary" onClick={onEdit} aria-label={`Edit ${client.name}`}>
            Edit
          </Button>
          <Button variant="secondary" onClick={onDelete} aria-label={`Delete ${client.name}`}>
            Delete
          </Button>
        </div>
      </TableCell>
    </TableRow>
  )
}

// ── the shared form ────────────────────────────────────────────────────────

interface ClientFormValues {
  name: string
  clientCode: string
  address: string
  city: string
  /** Add-only. The edit dialog issues a login from the client's account panel instead. */
  createPortalLogin: boolean
  contactName: string
  contactEmail: string
}

type ClientFormErrors = Partial<Record<keyof ClientFormValues, string>>

/** Everything `ClientFields` renders as a text input — `createPortalLogin` is a checkbox. */
type TextFieldKey = Exclude<keyof ClientFormValues, 'createPortalLogin'>

const emptyForm: ClientFormValues = {
  name: '',
  clientCode: '',
  address: '',
  city: '',
  createPortalLogin: false,
  contactName: '',
  contactEmail: '',
}

/** Only what the server cannot say better — a round trip to be told a field is blank is one wasted. */
function formErrors(values: ClientFormValues): ClientFormErrors {
  const errors: ClientFormErrors = {}
  if (!values.name.trim()) errors.name = 'A name is required.'
  if (!values.clientCode.trim()) errors.clientCode = 'A client code is required.'
  // Conditional, and mirrored from ObClientWriteService#validateForCreate
  // rather than owned here — the server refuses the same two fields, because a
  // guard that only exists in the form is not a guard.
  if (values.createPortalLogin) {
    if (!values.contactName.trim()) {
      errors.contactName = 'A portal login is issued to a person — give the main contact.'
    }
    if (!values.contactEmail.trim()) {
      errors.contactEmail = "The login's one-time link is mailed to this address."
    }
  }
  return errors
}

/** Field-keyed 400s and 409s land on their own input rather than in a toast. */
function fieldErrorsFrom(e: ApiError): ClientFormErrors {
  const problem = e.problem as { errors?: Record<string, string[]> }
  const server = problem.errors ?? {}
  const mapped: ClientFormErrors = {}
  for (const key of [
    'name',
    'clientCode',
    'address',
    'city',
    'contactName',
    'contactEmail',
  ] as const) {
    const message = server[key]?.[0]
    if (message) mapped[key] = message
  }
  return mapped
}

function ClientFields({
  idPrefix,
  values,
  errors,
  onChange,
  withPortalLogin = false,
}: {
  idPrefix: string
  values: ClientFormValues
  errors: ClientFormErrors
  onChange: (patch: Partial<ClientFormValues>) => void
  /**
   * Add only. On the edit dialog a login is issued from the client's own
   * account panel, which is also where it is reset and disabled — three
   * decisions that belong together and none of which is a field on a form.
   */
  withPortalLogin?: boolean
}) {
  const field = (
    key: TextFieldKey,
    label: string,
    extra?: { maxLength?: number; autoFocus?: boolean; hint?: string; type?: string },
  ) => {
    const id = `${idPrefix}-${key}`
    const errorId = `${id}-error`
    const hintId = `${id}-hint`
    const describedBy = [errors[key] ? errorId : null, extra?.hint ? hintId : null]
      .filter(Boolean)
      .join(' ')
    return (
      <div className="flex flex-col gap-1 text-sm">
        <label htmlFor={id} className="font-medium text-content">
          {label}
        </label>
        <Input
          id={id}
          type={extra?.type}
          value={values[key]}
          maxLength={extra?.maxLength}
          autoFocus={extra?.autoFocus}
          aria-invalid={errors[key] ? true : undefined}
          aria-describedby={describedBy || undefined}
          onChange={(e) => onChange({ [key]: e.target.value } as Partial<ClientFormValues>)}
        />
        {extra?.hint ? (
          <span id={hintId} className="text-xs text-content-muted">
            {extra.hint}
          </span>
        ) : null}
        {errors[key] ? (
          <span id={errorId} className="text-xs text-danger-text">
            {errors[key]}
          </span>
        ) : null}
      </div>
    )
  }

  const portalLoginId = `${idPrefix}-createPortalLogin`

  return (
    /*
      `htmlFor` and an id rather than a wrapping `<label>`, so a hint arrives
      through `aria-describedby` and not as part of the field's accessible
      name — the split every onboarding master makes.
    */
    <>
      <div className="grid grid-cols-1 gap-4 px-6 py-4 sm:grid-cols-2">
        {field('name', 'Client name', { maxLength: 200, autoFocus: true })}
        {field('clientCode', 'Client code', {
          maxLength: 32,
          hint: 'Unique. How operations file this company — and their portal username.',
        })}
        <div className="sm:col-span-2">{field('address', 'Address', { maxLength: 2000 })}</div>
        {field('city', 'City', { maxLength: 120 })}
      </div>

      {withPortalLogin ? (
        <div className="border-t border-border px-6 py-4">
          <div className="flex items-start gap-2.5">
            <input
              id={portalLoginId}
              type="checkbox"
              checked={values.createPortalLogin}
              aria-describedby={`${portalLoginId}-hint`}
              className="mt-0.5 h-4 w-4 rounded border-border text-primary focus:ring-2 focus:ring-primary"
              onChange={(e) => onChange({ createPortalLogin: e.target.checked })}
            />
            <div className="flex flex-col">
              <label htmlFor={portalLoginId} className="text-sm text-content">
                Create client portal login
              </label>
              <p id={`${portalLoginId}-hint`} className="text-caption text-content-muted">
                They sign in with the client code above. Their contact receives a one-time link
                to set a password.
              </p>
            </div>
          </div>

          {/*
            Revealed rather than always shown. A login is issued to a person —
            the account stores their name and email and the credential mail is
            addressed at them — but a company boarded without one has no use
            for either field, and a form that asks for a SPOC every time
            invites a placeholder.
          */}
          {values.createPortalLogin ? (
            <div className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-2">
              {field('contactName', 'Contact name', {
                maxLength: 160,
                hint: 'Created as the client’s primary contact.',
              })}
              {field('contactEmail', 'Contact email', { maxLength: 200, type: 'email' })}
            </div>
          ) : null}
        </div>
      ) : null}
    </>
  )
}

// ── add ────────────────────────────────────────────────────────────────────

function CreateClientDialog({
  open,
  onOpenChange,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const create = useCreateObClient()
  const [values, setValues] = React.useState<ClientFormValues>(emptyForm)
  const [errors, setErrors] = React.useState<ClientFormErrors>({})
  /** The server's own sentence from `ob-client-name-similar`, and the offer to proceed. */
  const [similarWarning, setSimilarWarning] = React.useState<string | null>(null)
  /**
   * Held after a successful create until the operator dismisses it. The client
   * is already saved at this point — this is the one chance to read the
   * password, not a step that can still fail.
   */
  const [issued, setIssued] = React.useState<{
    clientName: string
    login: ObPortalLoginIssued
  } | null>(null)

  React.useEffect(() => {
    if (!open) return
    // Reset on every open rather than keeping an abandoned draft — a form that
    // reopens holding one is a Save away from a client nobody meant to add.
    setValues(emptyForm)
    setErrors({})
    setSimilarWarning(null)
  }, [open])

  const onChange = (patch: Partial<ClientFormValues>) => {
    setValues((current) => ({ ...current, ...patch }))
    // Editing the name after a similarity warning makes the warning about a
    // name that is no longer being submitted.
    if (patch.name !== undefined) setSimilarWarning(null)
  }

  const submit = (acknowledgeSimilarNames: boolean) => {
    const found = formErrors(values)
    setErrors(found)
    if (Object.keys(found).length > 0) return

    create.mutate(
      {
        name: values.name.trim(),
        clientCode: values.clientCode.trim(),
        address: values.address.trim() || null,
        city: values.city.trim() || null,
        acknowledgeSimilarNames,
        // Sent only when ticked, so a company-only add is byte-for-byte the
        // request it was before this field existed.
        ...(values.createPortalLogin
          ? {
              createPortalLogin: true,
              contactName: values.contactName.trim(),
              contactEmail: values.contactEmail.trim(),
            }
          : {}),
      },
      {
        onSuccess: ({ client, portalLogin }) => {
          if (portalLogin) {
            setIssued({ clientName: client.name, login: portalLogin })
          } else {
            toast({ title: `${client.name} added` })
            onOpenChange(false)
          }
        },
        onError: (error) => {
          if (error.problem.type?.endsWith('ob-client-name-similar')) {
            setSimilarWarning(error.problem.detail ?? 'A similarly named client already exists.')
            return
          }
          const mapped = fieldErrorsFrom(error)
          if (Object.keys(mapped).length > 0) setErrors(mapped)
          else toast({ title: 'The client was not added', description: error.problem.detail })
        },
      },
    )
  }

  return (
    <>
    <Modal open={open && issued == null} onOpenChange={onOpenChange}>
      <ModalContent>
        <form
          onSubmit={(event) => {
            event.preventDefault()
            submit(false)
          }}
        >
          <ModalHeader>
            <ModalTitle>Add client</ModalTitle>
            <ModalDescription>
              The company only. Its products, start dates and module services are chosen when
              you give it a project.
            </ModalDescription>
          </ModalHeader>

          <ClientFields
            idPrefix="new-client"
            values={values}
            errors={errors}
            onChange={onChange}
            withPortalLogin
          />

          {similarWarning ? (
            <div className="mx-6 mb-2 rounded-card border border-warning bg-warning-soft p-3 text-sm">
              <p className="font-medium text-warning-text">Similar client already on file</p>
              <p className="mt-1 text-content-muted">{similarWarning}</p>
              <Button
                type="button"
                variant="secondary"
                className="mt-2"
                onClick={() => submit(true)}
                disabled={create.isPending}
              >
                Add anyway
              </Button>
            </div>
          ) : null}

          <ModalFooter>
            <Button type="button" variant="secondary" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={create.isPending}>
              {create.isPending ? 'Saving…' : 'Save client'}
            </Button>
          </ModalFooter>
        </form>
      </ModalContent>
    </Modal>

    {/*
      Outside the form's modal, so dismissing the credentials closes the whole
      add flow rather than returning to a filled-in form for a client that has
      already been created.
    */}
    <ObPortalLoginIssuedDialog
      login={issued?.login ?? null}
      clientName={issued?.clientName ?? ''}
      onClose={() => {
        const name = issued?.clientName
        setIssued(null)
        onOpenChange(false)
        if (name) toast({ title: `${name} added` })
      }}
    />
    </>
  )
}

// ── edit ───────────────────────────────────────────────────────────────────

function EditClientDialog({
  obClientId,
  onClose,
}: {
  obClientId: number | null
  onClose: () => void
}) {
  const { data, isPending } = useObClient(obClientId)
  const update = useUpdateObClient()
  const [values, setValues] = React.useState<ClientFormValues>(emptyForm)
  const [errors, setErrors] = React.useState<ClientFormErrors>({})

  React.useEffect(() => {
    if (!data) return
    setValues({
      ...emptyForm,
      name: data.client.name,
      clientCode: data.client.clientCode ?? '',
      address: data.client.address ?? '',
      city: data.client.city ?? '',
    })
    setErrors({})
  }, [data])

  const onChange = (patch: Partial<ClientFormValues>) =>
    setValues((current) => ({ ...current, ...patch }))

  const onSubmit = (event: React.FormEvent) => {
    event.preventDefault()
    if (obClientId == null || !data) return
    const found = formErrors(values)
    setErrors(found)
    if (Object.keys(found).length > 0) return

    update.mutate(
      {
        obClientId,
        etag: data.etag,
        data: {
          name: values.name.trim(),
          clientCode: values.clientCode.trim(),
          address: values.address.trim() || null,
          city: values.city.trim() || null,
        },
      },
      {
        onSuccess: (client) => {
          toast({ title: `${client.name} saved` })
          onClose()
        },
        onError: (error) => {
          const mapped = fieldErrorsFrom(error)
          if (Object.keys(mapped).length > 0) setErrors(mapped)
          else toast({ title: 'The client was not saved', description: error.problem.detail })
        },
      },
    )
  }

  return (
    <Modal open={obClientId != null} onOpenChange={(open) => (open ? undefined : onClose())}>
      <ModalContent>
        <form onSubmit={onSubmit}>
          <ModalHeader>
            <ModalTitle>Edit client</ModalTitle>
            <ModalDescription>
              Correcting the code is fine — nothing resolves a client through it. It simply has
              to stay unique.
            </ModalDescription>
          </ModalHeader>

          {isPending || !data ? (
            <div className="px-6 py-4">
              <Skeleton className="h-40 w-full" />
            </div>
          ) : (
            <ClientFields
              idPrefix="edit-client"
              values={values}
              errors={errors}
              onChange={onChange}
            />
          )}

          <ModalFooter>
            <Button type="button" variant="secondary" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={update.isPending || isPending}>
              {update.isPending ? 'Saving…' : 'Save changes'}
            </Button>
          </ModalFooter>
        </form>
      </ModalContent>
    </Modal>
  )
}

// ── delete ─────────────────────────────────────────────────────────────────

function DeleteClientDialog({
  client,
  onClose,
}: {
  client: ObClient | null
  onClose: () => void
}) {
  const remove = useDeleteObClient()
  const [refusal, setRefusal] = React.useState<{ message: string; blockers: string[] } | null>(null)

  React.useEffect(() => {
    if (client) setRefusal(null)
  }, [client])

  const onConfirm = () => {
    if (!client) return
    remove.mutate(client.id, {
      onSuccess: () => {
        toast({ title: `${client.name} deleted` })
        onClose()
      },
      onError: (error) => {
        const blockers = blockersFrom(error)
        if (blockers.length > 0) {
          setRefusal({
            message: error.problem.detail ?? 'This client cannot be deleted.',
            blockers,
          })
          return
        }
        toast({ title: 'The client was not deleted', description: error.problem.detail })
      },
    })
  }

  return (
    <Modal open={client != null} onOpenChange={(open) => (open ? undefined : onClose())}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>Delete {client?.name}?</ModalTitle>
          <ModalDescription>
            {refusal
              ? 'Nothing was deleted.'
              : 'This removes the company record. It cannot be undone.'}
          </ModalDescription>
        </ModalHeader>

        <div className="px-6 py-4 text-sm">
          {refusal ? (
            <div className="rounded-card border border-danger bg-danger-soft p-3">
              <p className="font-medium text-danger-text">
                This client has {refusal.blockers.join(', ')}.
              </p>
              <p className="mt-1 text-content-muted">{refusal.message}</p>
            </div>
          ) : (
            <p className="text-content-muted">
              A client with projects, a prerequisite checklist, uploaded documents or a portal
              login cannot be deleted — the work underneath it is a record we keep. If that is
              this client, you will be told, and nothing will be removed.
            </p>
          )}
        </div>

        <ModalFooter>
          <Button type="button" variant="secondary" onClick={onClose}>
            {refusal ? 'Close' : 'Cancel'}
          </Button>
          {refusal ? null : (
            <Button type="button" onClick={onConfirm} disabled={remove.isPending}>
              {remove.isPending ? 'Deleting…' : 'Delete client'}
            </Button>
          )}
        </ModalFooter>
      </ModalContent>
    </Modal>
  )
}
