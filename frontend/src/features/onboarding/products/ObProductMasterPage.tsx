import * as React from 'react'
import { Link } from 'react-router-dom'

import { ApiError } from '@/api/http'
import type { ObProduct } from '@/api/generated/model/obProduct'

import { Button } from '@/components/ui/button'
import { Chip } from '@/components/ui/chip'
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

import { codeFromName } from './productCode'
import { useCreateProduct, useProduct, useProducts, useUpdateProduct } from './productQueries'

/**
 * OB-07 · the Products master — the catalogue of what the organisation sells.
 *
 * A product is the thing a Module Service is written *for* and a client
 * *buys*: `ObJourneyTemplate.productId` and `ob_client_applications` both
 * point at a row here. Until this screen the only way to add one was the API,
 * and the module-service create form offered whatever the fixtures had
 * seeded. Now an admin adds a product here and it is offered in that form's
 * "For product" picker straight away — the list is one query key, and every
 * write on this page invalidates it.
 *
 * <h2>Admin only, enforced where it can be</h2>
 *
 * Creating and editing are OB Admin writes (`ObModuleRoleRules` names both
 * routes `ADMIN_ONLY`), and the server is where that refusal lives. The page
 * itself is drawn for everyone holding the module, for the reason
 * `Sidebar.tsx` gives for every Administration row: the session carries the
 * platform role and the module list but not the onboarding role, so a
 * client-side gate would be guessing. A non-admin who opens the create dialog
 * is told by the server rather than by a missing button.
 *
 * <h2>Retire, never delete</h2>
 *
 * There is no delete button because there is no delete route. A product with
 * a journey instantiated from it is named in every mail, report and journey
 * row for that client; a retired product drops out of the pickers, keeps its
 * row here and changes nothing in flight.
 *
 * <h2>A product is a name — the code is nobody's business</h2>
 *
 * The API requires a code on every product and never lets it change; the
 * master asks for neither. The create form takes a name and
 * `codeFromName` makes the code from it, the edit form sends the stored
 * code back untouched, and no column shows it. A server refusal on the code
 * — a duplicate, in practice — lands on the name field, because the name is
 * the only thing the admin typed.
 */
export function ObProductMasterPage() {
  const { data: products, isPending, isError } = useProducts()
  const [creating, setCreating] = React.useState(false)
  const [editingId, setEditingId] = React.useState<number | null>(null)

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6 p-6">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-content">Products</h1>
          <p className="mt-1 max-w-2xl text-sm text-content-muted">
            Everything the organisation sells. A product added here is offered straight away
            when a Module Service is created, and becomes sellable to a client once a Module
            Service has been published for it. Products cannot be deleted — clients and services
            already name them — but they can be retired, which drops them out of the pickers.
          </p>
        </div>
        <Button onClick={() => setCreating(true)}>New product</Button>
      </header>

      {isPending ? (
        <Skeleton className="h-64 w-full" />
      ) : isError || !products ? (
        <p className="text-sm text-danger-text">Products could not be loaded.</p>
      ) : (
        <TableContainer>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead scope="col">Product</TableHead>
                <TableHead scope="col" className="w-44">
                  Module Service
                </TableHead>
                <TableHead scope="col" className="w-24 text-right">
                  Clients
                </TableHead>
                <TableHead scope="col" className="w-28">
                  Status
                </TableHead>
                <TableHead scope="col" className="w-24">
                  <span className="sr-only">Actions</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {products.map((product) => (
                <ProductRow
                  key={product.id}
                  product={product}
                  onEdit={() => setEditingId(product.id)}
                />
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      <CreateProductDialog open={creating} onOpenChange={setCreating} />
      <EditProductDialog productId={editingId} onClose={() => setEditingId(null)} />
    </div>
  )
}

function ProductRow({ product, onEdit }: { product: ObProduct; onEdit: () => void }) {
  const journeys = product.journeyCount ?? 0
  return (
    <TableRow>
      <TableCell className="font-medium text-content">{product.name}</TableCell>
      <TableCell>
        {product.hasActiveTemplate && product.activeTemplateId != null ? (
          <Link
            to={`/onboarding/journey-templates/${product.activeTemplateId}`}
            className="text-sm text-primary hover:underline"
            title="Open this product's active Module Service"
          >
            Published
            {product.totalTatDays != null && (
              <span className="ml-1 text-content-muted">· {product.totalTatDays}d</span>
            )}
          </Link>
        ) : (
          /* Named rather than left blank: a product with no published service
             cannot be bought, and this is the column that says so. */
          <span
            className="text-sm text-content-muted"
            title="A product cannot be sold until a Module Service is published for it."
          >
            None yet
          </span>
        )}
      </TableCell>
      <TableCell className="text-right tabular-nums text-content-muted">{journeys}</TableCell>
      <TableCell>
        {product.isActive ? (
          <Chip variant="success">Active</Chip>
        ) : (
          <Chip variant="neutral">Retired</Chip>
        )}
      </TableCell>
      <TableCell>
        <Button
          variant="secondary"
          onClick={onEdit}
          // Named for a screen reader, because "Edit" five times in a column
          // says nothing about which row it edits.
          aria-label={`Edit ${product.name}`}
        >
          Edit
        </Button>
      </TableCell>
    </TableRow>
  )
}

// ── the shared form ────────────────────────────────────────────────────────

interface ProductFormValues {
  name: string
  isActive: boolean
}

type ProductFormErrors = Partial<Record<'name', string>>

/**
 * Only what the server cannot say better: a blank name is refused locally
 * because a round trip to be told so is a round trip wasted.
 */
function formErrors(values: ProductFormValues): ProductFormErrors {
  const errors: ProductFormErrors = {}
  if (!values.name.trim()) errors.name = 'A name is required.'
  return errors
}

/**
 * 409s and 400s are field-keyed, so they land on the input rather than in a
 * toast. A `code` error is shown on the name: the code was made from the
 * name, so a duplicate code means a product by that name already exists,
 * and the name is the only field there is.
 */
function fieldErrorsFrom(e: ApiError): ProductFormErrors {
  const problem = e.problem as { errors?: Record<string, string[]> }
  const server = problem.errors ?? {}
  const mapped: ProductFormErrors = {}
  if (server.name?.[0]) mapped.name = server.name[0]
  else if (server.code?.[0]) mapped.name = 'A product with this name already exists.'
  return mapped
}

function ProductFields({
  values,
  errors,
  onChange,
}: {
  values: ProductFormValues
  errors: ProductFormErrors
  onChange: (patch: Partial<ProductFormValues>) => void
}) {
  return (
    /*
      `htmlFor` and an id rather than a wrapping `<label>`, so the hint under
      each field arrives through `aria-describedby` and not as part of the
      field's accessible name — the same split every onboarding master makes.
    */
    <div className="flex flex-col gap-4 px-6 py-4">
      <div className="flex flex-col gap-1 text-sm">
        <label htmlFor="product-name" className="font-medium text-content">
          Product name
        </label>
        <Input
          id="product-name"
          value={values.name}
          maxLength={160}
          autoFocus
          aria-invalid={errors.name ? true : undefined}
          aria-describedby={errors.name ? 'product-name-error' : undefined}
          onChange={(e) => onChange({ name: e.target.value })}
        />
        {errors.name ? (
          <span id="product-name-error" className="text-xs text-danger-text">
            {errors.name}
          </span>
        ) : null}
      </div>

      <fieldset className="flex flex-col gap-2 rounded-card bg-subtle p-3 text-sm">
        <legend className="sr-only">Availability</legend>
        <div className="flex items-start gap-2">
          <input
            id="product-active"
            type="checkbox"
            className="mt-1"
            checked={values.isActive}
            aria-describedby="product-active-hint"
            onChange={(e) => onChange({ isActive: e.target.checked })}
          />
          <span>
            <label htmlFor="product-active" className="font-medium text-content">
              Available for selection
            </label>
            <span id="product-active-hint" className="mt-1 block text-xs text-content-muted">
              Retiring a product removes it from the new-client and module-service pickers.
              Clients already on it keep their journeys, and it stays listed here.
            </span>
          </span>
        </div>
      </fieldset>
    </div>
  )
}

// ── create ─────────────────────────────────────────────────────────────────

const emptyForm: ProductFormValues = { name: '', isActive: true }

function CreateProductDialog({
  open,
  onOpenChange,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const create = useCreateProduct()
  const [values, setValues] = React.useState<ProductFormValues>(emptyForm)
  const [errors, setErrors] = React.useState<ProductFormErrors>({})

  React.useEffect(() => {
    if (!open) return
    // Reset on every open rather than keeping whatever was typed and
    // abandoned last time — a form that reopens holding a discarded draft is
    // one Save away from creating a product nobody meant to.
    setValues(emptyForm)
    setErrors({})
  }, [open])

  const onChange = (patch: Partial<ProductFormValues>) =>
    setValues((current) => ({ ...current, ...patch }))

  const onSubmit = (event: React.FormEvent) => {
    event.preventDefault()
    const found = formErrors(values)
    setErrors(found)
    if (Object.keys(found).length > 0) return

    const name = values.name.trim()
    create.mutate(
      {
        // Never typed and never shown — see `codeFromName`.
        code: codeFromName(name),
        name,
        isActive: values.isActive,
      },
      {
        onSuccess: (product) => {
          onOpenChange(false)
          toast({
            title: `${product.name} added`,
            description:
              'It is offered when a Module Service is created. Publish one for it before a client can buy it.',
          })
        },
        onError: (e: ApiError) => {
          const fields = fieldErrorsFrom(e)
          if (Object.keys(fields).length > 0) {
            setErrors(fields)
            return
          }
          toast({
            variant: 'danger',
            title: 'Could not add the product',
            description: problemDetail(e),
          })
        },
      },
    )
  }

  return (
    <Modal open={open} onOpenChange={onOpenChange}>
      <ModalContent>
        <form onSubmit={onSubmit}>
          <ModalHeader>
            <ModalTitle>New product</ModalTitle>
            <ModalDescription>
              It joins the catalogue immediately and can be picked when a Module Service is
              created. A client can buy it once that service is published.
            </ModalDescription>
          </ModalHeader>

          <ProductFields values={values} errors={errors} onChange={onChange} />

          <ModalFooter>
            <Button type="button" variant="secondary" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={create.isPending}>
              {create.isPending ? 'Adding…' : 'Add product'}
            </Button>
          </ModalFooter>
        </form>
      </ModalContent>
    </Modal>
  )
}

// ── edit and retire ────────────────────────────────────────────────────────

function EditProductDialog({
  productId,
  onClose,
}: {
  productId: number | null
  onClose: () => void
}) {
  const { data, isPending } = useProduct(productId)
  const update = useUpdateProduct()
  const [values, setValues] = React.useState<ProductFormValues | null>(null)
  const [errors, setErrors] = React.useState<ProductFormErrors>({})

  // Seeded from the read rather than from the list row, because the read is
  // what carries the `ETag` — editing values the tag does not cover would
  // make the precondition a formality.
  React.useEffect(() => {
    setValues(
      data
        ? { name: data.product.name, isActive: data.product.isActive }
        : null,
    )
    setErrors({})
  }, [data])

  if (productId == null) return null

  const journeys = data?.product.journeyCount ?? 0

  const onChange = (patch: Partial<ProductFormValues>) =>
    setValues((current) => (current ? { ...current, ...patch } : current))

  const onSubmit = (event: React.FormEvent) => {
    event.preventDefault()
    if (!values || !data) return
    const found = formErrors(values)
    setErrors(found)
    if (Object.keys(found).length > 0) return

    update.mutate(
      {
        productId,
        etag: data.etag,
        data: {
          // The stored code goes back untouched — the request shape requires
          // one, the server refuses any other value, and a rename does not
          // re-derive it: mails and reports already name the product by it.
          code: data.product.code,
          name: values.name.trim(),
          isActive: values.isActive,
        },
      },
      {
        onSuccess: (product) => {
          onClose()
          toast({
            title: `${product.name} saved`,
            description: product.isActive ? undefined : 'It no longer appears in the pickers.',
          })
        },
        onError: (e: ApiError) => {
          if (e.status === 412) {
            toast({
              variant: 'danger',
              title: 'Someone else changed this product',
              description: 'Close and reopen the form to see the current values, then reapply.',
            })
            return
          }
          const fields = fieldErrorsFrom(e)
          if (Object.keys(fields).length > 0) {
            setErrors(fields)
            return
          }
          toast({
            variant: 'danger',
            title: 'Could not save the product',
            description: problemDetail(e),
          })
        },
      },
    )
  }

  return (
    <Modal open onOpenChange={(next) => (next ? undefined : onClose())}>
      <ModalContent>
        <form onSubmit={onSubmit}>
          <ModalHeader>
            <ModalTitle>Edit product</ModalTitle>
            <ModalDescription>
              {journeys > 0
                ? `${journeys} client journey${journeys === 1 ? ' has' : 's have'} been instantiated from this product. A rename follows through to them; retiring it changes nothing already running.`
                : 'No client has bought this product yet.'}
            </ModalDescription>
          </ModalHeader>

          {isPending || !values ? (
            <div className="px-6 py-4">
              <Skeleton className="h-48 w-full" />
            </div>
          ) : (
            <ProductFields values={values} errors={errors} onChange={onChange} />
          )}

          <ModalFooter>
            <Button type="button" variant="secondary" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={update.isPending || isPending || !values}>
              {update.isPending ? 'Saving…' : 'Save product'}
            </Button>
          </ModalFooter>
        </form>
      </ModalContent>
    </Modal>
  )
}

function problemDetail(error: unknown): string | undefined {
  if (!(error instanceof ApiError)) return undefined
  return error.problem.detail ?? error.problem.title ?? undefined
}
