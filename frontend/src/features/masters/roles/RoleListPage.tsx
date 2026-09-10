import * as React from 'react'
import { Link } from 'react-router-dom'

import { ApiError } from '@/api/http'
import type { Role } from '@/api/generated/model/role'

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

import { useCreateRole, useDeleteRole, useRole, useRoles, useUpdateRole } from './roleQueries'

/**
 * S-09 Role & Permission Master — the role list. B-015.
 *
 * The matrix itself is `RolePermissionsPage`, one role at a time; this is the
 * grid that gets you there and the three writes that do not need it — create,
 * edit and delete.
 *
 * **Edit is here as well as on the detail page, not instead of it.** Renaming a
 * role, correcting its description or deactivating it are the edits an admin
 * comes to the grid to make, and sending them to the permission matrix to make
 * one of them means loading eighteen capability checkboxes to change a word.
 * Both write through the same `PATCH` and the same `If-Match`, so they cannot
 * drift; the detail page keeps its copy because that is where the code, the
 * resource count and the matrix are already on screen.
 *
 * **`userCount` is on every row, not only in the refusal.** A delete that is
 * going to be refused should be visibly going to be refused before it is
 * clicked; discovering the count only in the error is how an admin ends up
 * clicking it four times to see whether anything changed.
 */
export function RoleListPage() {
  const { data: roles, isPending, isError } = useRoles()
  const [creating, setCreating] = React.useState(false)
  const [editing, setEditing] = React.useState<Role | null>(null)
  const [pendingDelete, setPendingDelete] = React.useState<Role | null>(null)

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6 p-6">
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-content">Roles &amp; permissions</h1>
          <p className="mt-1 max-w-2xl text-sm text-content-muted">
            What each role may do. The six roles EduTrack ships with cannot be deleted — they
            are what every scope decision is built on — but they can be renamed, deactivated
            and re-permissioned.
          </p>
        </div>
        <Button onClick={() => setCreating(true)}>New role</Button>
      </header>

      {isPending ? (
        <Skeleton className="h-64 w-full" />
      ) : isError || !roles ? (
        <p className="text-sm text-danger-text">Roles could not be loaded.</p>
      ) : (
        <TableContainer>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead scope="col">Role</TableHead>
                <TableHead scope="col">Code</TableHead>
                <TableHead scope="col">Resources</TableHead>
                <TableHead scope="col">Permissions</TableHead>
                <TableHead scope="col">Status</TableHead>
                <TableHead scope="col">
                  <span className="sr-only">Actions</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {roles.map((role) => (
                <RoleRow
                  key={role.id}
                  role={role}
                  onEdit={() => setEditing(role)}
                  onDelete={() => setPendingDelete(role)}
                />
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}

      <CreateRoleDialog open={creating} onOpenChange={setCreating} />
      {/*
        Keyed and conditionally mounted so each open starts from the role that
        was clicked: an always-mounted dialog would carry the previous role's
        half-typed name into the next one.
      */}
      {editing ? (
        <EditRoleDialog key={editing.id} role={editing} onClose={() => setEditing(null)} />
      ) : null}
      <DeleteRoleDialog role={pendingDelete} onClose={() => setPendingDelete(null)} />
    </div>
  )
}

function RoleRow({
  role,
  onEdit,
  onDelete,
}: {
  role: Role
  onEdit: () => void
  onDelete: () => void
}) {
  return (
    <TableRow>
      <TableCell>
        <Link
          to={`/masters/roles/${role.id}`}
          className="font-medium text-primary hover:underline"
        >
          {role.name}
        </Link>
        {role.description ? (
          <p className="mt-0.5 text-xs text-content-muted">{role.description}</p>
        ) : null}
      </TableCell>
      <TableCell>
        <code className="text-xs text-content-muted">{role.code}</code>
      </TableCell>
      <TableCell>{role.userCount}</TableCell>
      <TableCell>{role.permissionCount}</TableCell>
      <TableCell>
        <Chip variant={role.isActive ? 'success' : 'neutral'}>
          {role.isActive ? 'Active' : 'Inactive'}
        </Chip>
        {role.isSystem ? (
          <Chip variant="info" className="ml-2">
            System
          </Chip>
        ) : null}
      </TableCell>
      <TableCell className="text-right">
        <Button asChild variant="ghost" size="sm">
          <Link to={`/masters/roles/${role.id}`}>Permissions</Link>
        </Button>
        {/*
          Enabled for a system role: the six are undeletable, not unrenameable —
          the page header says so, and `RoleService.update` accepts a patch to
          any of them. Only the code is immutable, and the dialog does not offer
          it.
        */}
        <Button variant="ghost" size="sm" onClick={onEdit}>
          Edit
        </Button>
        {/*
          Disabled rather than hidden for a system role, with the reason in the
          title: a missing button reads as a rendering bug, and the rule — the
          six are structural — is worth stating where somebody looks for it.
        */}
        <Button
          variant="ghost"
          size="sm"
          className="text-danger-text"
          disabled={role.isSystem}
          title={
            role.isSystem
              ? 'System roles cannot be deleted. Deactivate it instead.'
              : undefined
          }
          onClick={onDelete}
        >
          Delete
        </Button>
      </TableCell>
    </TableRow>
  )
}

// ── create ──────────────────────────────────────────────────────────────────

function CreateRoleDialog({
  open,
  onOpenChange,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const create = useCreateRole()
  const [code, setCode] = React.useState('')
  const [name, setName] = React.useState('')
  const [description, setDescription] = React.useState('')
  const [codeError, setCodeError] = React.useState<string | null>(null)

  React.useEffect(() => {
    if (!open) {
      setCode('')
      setName('')
      setDescription('')
      setCodeError(null)
    }
  }, [open])

  const onSubmit = (event: React.FormEvent) => {
    event.preventDefault()
    setCodeError(null)
    create.mutate(
      { code: code.trim().toUpperCase(), name: name.trim(), description: description.trim() || null },
      {
        onSuccess: (role) => {
          onOpenChange(false)
          toast({
            title: `${role.name} created`,
            description: 'It holds no permissions yet — open it to set them.',
          })
        },
        onError: (e: ApiError) => {
          if (e.status === 409) {
            setCodeError(e.problem.detail ?? 'That code is already taken.')
            return
          }
          toast({ variant: 'danger', title: 'Could not create the role' })
        },
      },
    )
  }

  return (
    <Modal open={open} onOpenChange={onOpenChange}>
      <ModalContent>
        <form onSubmit={onSubmit}>
          <ModalHeader>
            <ModalTitle>New role</ModalTitle>
            <ModalDescription>
              The code is permanent — it is carried in access tokens and workflow rules, so it
              cannot be changed later. A new role starts with no permissions.
            </ModalDescription>
          </ModalHeader>

          <div className="flex flex-col gap-4 py-4">
            <label className="flex flex-col gap-1 text-sm">
              <span className="font-medium text-content">Code</span>
              <Input
                value={code}
                required
                aria-invalid={codeError != null}
                aria-describedby={codeError ? 'role-code-error' : undefined}
                placeholder="AUDITOR"
                onChange={(e) => setCode(e.target.value.toUpperCase())}
              />
              {codeError ? (
                <span id="role-code-error" role="alert" className="text-xs text-danger-text">
                  {codeError}
                </span>
              ) : null}
            </label>

            <label className="flex flex-col gap-1 text-sm">
              <span className="font-medium text-content">Name</span>
              <Input
                value={name}
                required
                placeholder="Auditor"
                onChange={(e) => setName(e.target.value)}
              />
            </label>

            <label className="flex flex-col gap-1 text-sm">
              <span className="font-medium text-content">Description</span>
              <Input
                value={description}
                placeholder="Read-only oversight of tickets and reports"
                onChange={(e) => setDescription(e.target.value)}
              />
            </label>
          </div>

          {/*
            A custom role is definable and grantable but not yet selectable on a
            resource: the contract types `user.role` as a closed six-value enum,
            and opening it touches three other streams. Saying so here beats an
            admin creating one and finding it missing from the S-08 picker.
          */}
          <p className="rounded-card bg-subtle p-3 text-xs text-content-muted">
            Custom roles can be given permissions now, but cannot yet be assigned to a resource
            — the resource form still offers the six built-in roles only.
          </p>

          <ModalFooter>
            <Button type="button" variant="secondary" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={create.isPending || !code.trim() || !name.trim()}>
              {create.isPending ? 'Creating…' : 'Create role'}
            </Button>
          </ModalFooter>
        </form>
      </ModalContent>
    </Modal>
  )
}

// ── edit ────────────────────────────────────────────────────────────────────

/**
 * Rename, re-describe or deactivate a role.
 *
 * **The values come from the detail read, not from the grid row.** `If-Match`
 * only guards anything if the tag and the values it is sent with describe the
 * same version of the role — seeding the form from a list row cached minutes
 * ago and then attaching a freshly fetched tag would send a `412`-proof write
 * over the top of somebody else's edit, which is the precise failure the tag
 * exists to catch. So the dialog waits for the read before it offers fields.
 *
 * The code is not offered: `RoleService.update` refuses a patch that changes it
 * (`ImmutableRoleCodeException`) because it is carried in access tokens and
 * workflow rules. It is shown, disabled, with the reason — the same answer the
 * detail page gives.
 */
function EditRoleDialog({ role, onClose }: { role: Role; onClose: () => void }) {
  const { data, isPending, isError } = useRole(role.id)
  const update = useUpdateRole()

  const [name, setName] = React.useState('')
  const [description, setDescription] = React.useState('')
  const [isActive, setIsActive] = React.useState(true)
  const [seeded, setSeeded] = React.useState(false)

  // Once. A background refetch must not overwrite what is being typed.
  React.useEffect(() => {
    if (!data || seeded) return
    setName(data.role.name)
    setDescription(data.role.description ?? '')
    setIsActive(data.role.isActive)
    setSeeded(true)
  }, [data, seeded])

  const onSubmit = (event: React.FormEvent) => {
    event.preventDefault()
    if (!data) return
    update.mutate(
      {
        roleId: role.id,
        data: { name: name.trim(), description: description.trim() || null, isActive },
        etag: data.etag,
      },
      {
        onSuccess: (saved) => {
          onClose()
          toast({ title: `${saved.name} saved` })
        },
        onError: (e: ApiError) => {
          const stale = e.status === 412
          toast({
            variant: 'danger',
            title: stale ? 'Somebody else changed this role' : 'Could not save the role',
            description: stale
              ? 'Reopen it and reapply your change — your edit was not saved.'
              : (e.problem.detail ?? undefined),
          })
        },
      },
    )
  }

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent>
        <form onSubmit={onSubmit}>
          <ModalHeader>
            <ModalTitle>Edit {role.name}</ModalTitle>
            <ModalDescription>
              Name, description and status. Permissions are set on the role's own page; the
              code is permanent.
            </ModalDescription>
          </ModalHeader>

          {isError ? (
            <p className="py-4 text-sm text-danger-text">
              This role could not be loaded, so it cannot be edited safely. Close this and try
              again.
            </p>
          ) : !data ? (
            <div className="py-4">
              <Skeleton className="h-40 w-full" />
            </div>
          ) : (
            <div className="flex flex-col gap-4 py-4">
              <label className="flex flex-col gap-1 text-sm">
                <span className="font-medium text-content">Name</span>
                <Input value={name} required onChange={(e) => setName(e.target.value)} />
              </label>

              <label className="flex flex-col gap-1 text-sm">
                <span className="font-medium text-content">Description</span>
                <Input
                  value={description}
                  placeholder="Read-only oversight of tickets and reports"
                  onChange={(e) => setDescription(e.target.value)}
                />
              </label>

              {/*
                The note is a sibling of the label, not inside it: a wrapping
                label's accessible name is its whole text content, so a note
                nested in one becomes part of the field's name and
                `getByLabelText('Code')` stops finding it.
              */}
              <div className="flex flex-col gap-1 text-sm">
                <label htmlFor="edit-role-code" className="font-medium text-content">
                  Code
                </label>
                <Input
                  id="edit-role-code"
                  value={data.role.code}
                  disabled
                  readOnly
                  aria-describedby="edit-role-code-note"
                />
                <span id="edit-role-code-note" className="text-xs text-content-muted">
                  Permanent — it is carried in access tokens and workflow rules. To change it,
                  deactivate this role and create a replacement.
                </span>
              </div>

              <label className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={isActive}
                  className="size-4 rounded border-border"
                  onChange={(e) => setIsActive(e.target.checked)}
                />
                <span className="text-content">
                  Active
                  <span className="ml-2 text-content-muted">
                    Inactive roles stay on the resources that hold them, but are not offered in
                    pickers.
                  </span>
                </span>
              </label>
            </div>
          )}

          <ModalFooter>
            <Button type="button" variant="secondary" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={isPending || !data || update.isPending || !name.trim()}>
              {update.isPending ? 'Saving…' : 'Save role'}
            </Button>
          </ModalFooter>
        </form>
      </ModalContent>
    </Modal>
  )
}

// ── delete ──────────────────────────────────────────────────────────────────

function DeleteRoleDialog({ role, onClose }: { role: Role | null; onClose: () => void }) {
  const remove = useDeleteRole()

  if (!role) return null

  const inUse = role.userCount > 0

  const onConfirm = () => {
    remove.mutate(role.id, {
      onSuccess: () => {
        onClose()
        toast({ title: `${role.name} deleted` })
      },
      onError: (e: ApiError) => {
        onClose()
        const count = (e.problem as { userCount?: number }).userCount
        toast({
          variant: 'danger',
          title: 'Could not delete the role',
          description:
            e.problem.type === 'https://edutrack/errors/role-in-use'
              ? `${count} resource${count === 1 ? '' : 's'} still hold it. Reassign them first.`
              : (e.problem.detail ?? 'The role was not deleted.'),
        })
      },
    })
  }

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>Delete {role.name}?</ModalTitle>
          <ModalDescription>
            {inUse
              ? `${role.userCount} resource${role.userCount === 1 ? '' : 's'} still hold this role. Reassign them before it can be deleted.`
              : 'Its permission grants go with it. Resources are unaffected — none hold it.'}
          </ModalDescription>
        </ModalHeader>
        <ModalFooter>
          <Button variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="danger" disabled={inUse || remove.isPending} onClick={onConfirm}>
            {remove.isPending ? 'Deleting…' : 'Delete role'}
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  )
}
