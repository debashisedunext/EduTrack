import * as React from 'react'
import { Link, useParams } from 'react-router-dom'

import { ApiError } from '@/api/http'
import type { Permission } from '@/api/generated/model/permission'

import { Button } from '@/components/ui/button'
import { Chip } from '@/components/ui/chip'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { toast } from '@/components/ui/use-toast'

import {
  groupByCategory,
  groupState,
  hasChanges,
  toRequest,
  toggle,
  toggleGroup,
  type PermissionGroup,
} from './permissionMatrix'
import {
  usePermissionCatalogue,
  useRole,
  useSaveRolePermissions,
  useUpdateRole,
} from './roleQueries'

/**
 * S-09 Role & Permission Master — the matrix for one role. B-015.
 *
 * Two saves, deliberately separate. The identity fields go through
 * `PATCH /masters/roles/{id}`; the matrix goes through
 * `PUT /masters/roles/{id}/permissions` as a replace-all. Both take the same
 * `If-Match`, so saving either invalidates the other's tag — which is why the
 * page refetches after each and reseeds from the server.
 *
 * **The matrix is `category × capability`, not the blueprint's
 * `module × CRUD/approve`.** See `permissionMatrix.ts` and this feature's
 * README for why: the seeded vocabulary is eighteen dotted capability codes
 * already named by the JWT claim and by `@PreAuthorize`, and recutting them to
 * fit a grid would be a cross-stream breaking change made for a layout.
 */
export function RolePermissionsPage() {
  const params = useParams<{ roleId: string }>()
  const roleId = Number(params.roleId)

  const { data, isPending, isError } = useRole(Number.isFinite(roleId) ? roleId : null)
  const catalogue = usePermissionCatalogue()

  if (isPending || catalogue.isPending) {
    return (
      <div className="mx-auto flex max-w-4xl flex-col gap-6 p-6">
        <Skeleton className="h-20 w-full" />
        <Skeleton className="h-96 w-full" />
      </div>
    )
  }

  if (isError || !data || catalogue.isError || !catalogue.data) {
    return (
      <div className="mx-auto max-w-4xl p-6">
        <p className="text-sm text-danger-text">This role could not be loaded.</p>
        <Button asChild variant="secondary" className="mt-4">
          <Link to="/masters/roles">Back to roles</Link>
        </Button>
      </div>
    )
  }

  return (
    <div className="mx-auto flex max-w-4xl flex-col gap-8 p-6">
      <IdentitySection roleId={roleId} />
      <MatrixSection roleId={roleId} catalogue={catalogue.data} />
    </div>
  )
}

// ── name, description, status ───────────────────────────────────────────────

function IdentitySection({ roleId }: { roleId: number }) {
  const { data } = useRole(roleId)
  const update = useUpdateRole()

  const [name, setName] = React.useState('')
  const [description, setDescription] = React.useState('')
  const [isActive, setIsActive] = React.useState(true)
  const [dirty, setDirty] = React.useState(false)

  // Seed from the server once, and again after a save — but never over the top
  // of an edit in progress.
  React.useEffect(() => {
    if (data && !dirty) {
      setName(data.role.name)
      setDescription(data.role.description ?? '')
      setIsActive(data.role.isActive)
    }
  }, [data, dirty])

  if (!data) return null
  const role = data.role

  const onSave = () => {
    update.mutate(
      {
        roleId,
        data: { name: name.trim(), description: description.trim() || null, isActive },
        etag: data.etag,
      },
      {
        onSuccess: () => {
          setDirty(false)
          toast({ title: 'Role saved' })
        },
        onError: (e: ApiError) => onWriteError(e, 'Could not save the role'),
      },
    )
  }

  return (
    <section className="flex flex-col gap-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <Button asChild variant="ghost" size="sm" className="-ml-3 mb-1">
            <Link to="/masters/roles">← Roles</Link>
          </Button>
          <h1 className="text-2xl font-semibold text-content">{role.name}</h1>
          <p className="mt-1 text-sm text-content-muted">
            <code>{role.code}</code> · {role.userCount} resource
            {role.userCount === 1 ? '' : 's'}
          </p>
        </div>
        {role.isSystem ? <Chip variant="info">System role</Chip> : null}
      </div>

      <div className="grid gap-4 sm:grid-cols-2">
        <label className="flex flex-col gap-1 text-sm">
          <span className="font-medium text-content">Name</span>
          <Input
            value={name}
            onChange={(e) => {
              setName(e.target.value)
              setDirty(true)
            }}
          />
        </label>

        <label className="flex flex-col gap-1 text-sm">
          <span className="font-medium text-content">Description</span>
          <Input
            value={description}
            onChange={(e) => {
              setDescription(e.target.value)
              setDirty(true)
            }}
          />
        </label>
      </div>

      {/*
        The code is read-only and says why. Leaving the field out entirely would
        make it look like an oversight; a disabled input with the reason beside
        it is the answer to the question the admin is about to ask.
      */}
      <div className="flex flex-col gap-1 text-sm">
        {/*
          The note sits outside the `label` deliberately: a wrapping label's
          accessible name is its whole text content, so a paragraph inside it
          becomes part of the field's name and `getByLabelText('Code')` stops
          finding it. `aria-describedby` is the right relationship anyway.
        */}
        <label htmlFor="role-code" className="font-medium text-content">
          Code
        </label>
        <Input id="role-code" value={role.code} disabled readOnly aria-describedby="role-code-note" />
        <span id="role-code-note" className="text-xs text-content-muted">
          Permanent. The code is carried in access tokens, authorisation rules and workflow
          transitions — to change it, deactivate this role and create a replacement.
        </span>
      </div>

      <label className="flex items-center gap-2 text-sm">
        <input
          type="checkbox"
          checked={isActive}
          className="size-4 rounded border-border"
          onChange={(e) => {
            setIsActive(e.target.checked)
            setDirty(true)
          }}
        />
        <span className="text-content">
          Active
          <span className="ml-2 text-content-muted">
            Inactive roles stay on the resources that hold them, but are not offered in pickers.
          </span>
        </span>
      </label>

      <div>
        <Button disabled={!dirty || update.isPending || !name.trim()} onClick={onSave}>
          {update.isPending ? 'Saving…' : 'Save role'}
        </Button>
      </div>
    </section>
  )
}

// ── the matrix ──────────────────────────────────────────────────────────────

function MatrixSection({ roleId, catalogue }: { roleId: number; catalogue: Permission[] }) {
  const { data } = useRole(roleId)
  const save = useSaveRolePermissions()

  const [selected, setSelected] = React.useState<Set<string>>(new Set())
  const [dirty, setDirty] = React.useState(false)

  React.useEffect(() => {
    if (data && !dirty) {
      setSelected(new Set(data.role.permissionCodes))
    }
  }, [data, dirty])

  const groups = React.useMemo(() => groupByCategory(catalogue), [catalogue])

  if (!data) return null

  const changed = hasChanges(selected, data.role.permissionCodes)

  const onSave = () => {
    save.mutate(
      { roleId, permissionCodes: toRequest(selected), etag: data.etag },
      {
        onSuccess: () => {
          setDirty(false)
          toast({
            title: 'Permissions saved',
            description:
              'They apply the next time each holder signs in — a token already issued keeps the permissions it was minted with.',
          })
        },
        onError: (e: ApiError) => onWriteError(e, 'Could not save the permissions'),
      },
    )
  }

  return (
    <section className="flex flex-col gap-4">
      <div>
        <h2 className="text-lg font-semibold text-content">Permissions</h2>
        <p className="mt-1 text-sm text-content-muted">
          Saving replaces this role&apos;s permissions with exactly what is ticked below.
        </p>
      </div>

      <div className="flex flex-col gap-6">
        {groups.map((group) => (
          <MatrixGroup
            key={group.category}
            group={group}
            selected={selected}
            onToggle={(permission) => {
              setSelected((current) => toggle(current, permission))
              setDirty(true)
            }}
            onToggleGroup={(grant) => {
              setSelected((current) => toggleGroup(current, group, grant))
              setDirty(true)
            }}
          />
        ))}
      </div>

      <div className="flex items-center gap-3">
        <Button disabled={!changed || save.isPending} onClick={onSave}>
          {save.isPending ? 'Saving…' : 'Save permissions'}
        </Button>
        {changed ? (
          <Button
            variant="ghost"
            onClick={() => {
              setSelected(new Set(data.role.permissionCodes))
              setDirty(false)
            }}
          >
            Discard changes
          </Button>
        ) : null}
      </div>
    </section>
  )
}

function MatrixGroup({
  group,
  selected,
  onToggle,
  onToggleGroup,
}: {
  group: PermissionGroup
  selected: Set<string>
  onToggle: (permission: Permission) => void
  onToggleGroup: (grant: boolean) => void
}) {
  const state = groupState(selected, group)
  const headerRef = React.useRef<HTMLInputElement>(null)

  // `indeterminate` is a DOM property with no HTML attribute, so React cannot
  // set it declaratively — a partially-ticked section otherwise renders as a
  // plain unticked box and reads as "nothing granted here".
  React.useEffect(() => {
    if (headerRef.current) headerRef.current.indeterminate = state === 'some'
  }, [state])

  const headingId = `permission-group-${group.category}`

  return (
    // `aria-labelledby` rather than a `legend`: a legend must be the fieldset's
    // first child, so pairing it with the header row means rendering the label
    // twice — which is a duplicate for a screen reader as well as for a test.
    <fieldset className="rounded-card border border-border" aria-labelledby={headingId}>
      <div className="flex items-center gap-3 border-b border-border bg-subtle px-4 py-2">
        <input
          ref={headerRef}
          type="checkbox"
          className="size-4 rounded border-border"
          checked={state === 'all'}
          aria-label={`Grant every permission under ${group.label}`}
          onChange={(e) => onToggleGroup(e.target.checked)}
        />
        <h3 id={headingId} className="text-sm font-semibold text-content">
          {group.label}
        </h3>
      </div>

      <ul className="divide-y divide-border">
        {group.permissions.map((permission) => (
          <li key={permission.code} className="px-4 py-3">
            <label className="flex items-start gap-3">
              <input
                type="checkbox"
                className="mt-0.5 size-4 rounded border-border"
                checked={selected.has(permission.code)}
                disabled={!permission.isGrantable}
                aria-describedby={`${permission.code}-note`}
                onChange={() => onToggle(permission)}
              />
              <span className="flex flex-col gap-0.5">
                <span
                  className={
                    permission.isGrantable
                      ? 'text-sm text-content'
                      : 'text-sm text-content-muted'
                  }
                >
                  {permission.name}
                </span>
                <span id={`${permission.code}-note`} className="text-xs text-content-muted">
                  <code>{permission.code}</code>
                  {permission.description ? ` · ${permission.description}` : null}
                </span>
                {/*
                  The one permission nobody may hold. It is rendered rather than
                  omitted so the append-only guarantee is visible on the screen
                  that would otherwise be where you went to look for it — a row
                  that is simply absent reads as a permission somebody forgot.
                */}
                {!permission.isGrantable ? (
                  <span className="text-xs font-medium text-content-muted">
                    Cannot be granted to anyone. Ticket history and the ribbon are append-only —
                    corrections are recorded as new entries, never edits.
                  </span>
                ) : null}
              </span>
            </label>
          </li>
        ))}
      </ul>
    </fieldset>
  )
}

/**
 * 412 is the one worth naming. Anything else is a generic failure; this one
 * tells the user precisely what to do about it.
 */
function onWriteError(e: ApiError, fallbackTitle: string) {
  const stale = e.status === 412
  toast({
    variant: 'danger',
    title: stale ? 'Somebody else changed this role' : fallbackTitle,
    description: stale
      ? 'Reload the page and reapply your change — your edit was not saved.'
      : (e.problem.detail ?? undefined),
  })
}
