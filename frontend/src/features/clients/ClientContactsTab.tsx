import * as React from 'react'
import { AlertTriangle, Pencil, Plus, Star, Trash2, Undo2 } from 'lucide-react'

import { useListClientContacts } from '@/api/generated/clients/clients'
import type { Contact } from '@/api/generated/model/contact'

import { Button } from '@/components/ui/button'
import { Chip } from '@/components/ui/chip'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import {
  Modal,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
} from '@/components/ui/modal'
import { toast } from '@/components/ui/use-toast'

import { ContactEditorDialog } from './ContactEditorDialog'
import { useRemoveContact, useUpdateContact } from './contactQueries'
import { toContactWriteRequest, toContactFormValues } from './contactForm'

/**
 * B-027 · S-33's Contacts tab — the `client_contacts` child grid.
 *
 * <h2>What changed from B-026</h2>
 *
 * B-026 shipped this tab read-only and said why: `POST /clients/{clientId}
 * /contacts` was the **seventh** "declared, mocked, never mounted" operation
 * this stream has found, and the other two verbs were not merely unmounted but
 * undeclared. All three exist now, so the tab is a grid rather than a list.
 *
 * <h2>Every button here is `type="button"`, and that is load-bearing</h2>
 *
 * This panel lives inside `ClientFormPage`'s `<form>`. A button's default type
 * is `submit`, so an Add or a Remove without the attribute **saves the client**
 * — silently doing the right-looking thing for one click and the wrong thing
 * for the other. The editor itself dodges the nested-form problem a different
 * way: `ModalContent` is portalled to `document.body`, so its `<form>` is a
 * sibling of this one rather than a descendant.
 *
 * <h2>Removed contacts are shown, not hidden</h2>
 *
 * The grid reads `?includeInactive=true` where every picker reads the default.
 * Removal deactivates — `tickets.client_contact_id` is a foreign key with no
 * cascade — so a hidden removed row would mean an administrator watching
 * somebody vanish with no way to tell "removed" from "never existed", and no way
 * to see that the address is still spoken for. The pickers get the opposite
 * default for the opposite reason: a departed contact must stop being offered on
 * new tickets.
 *
 * <h2>B-028's gate is warned about, never enforced here</h2>
 *
 * **A client without a primary contact is not selectable on a ticket** —
 * blueprint §4B.2. B-026 stated it and this task keeps it stated rather than
 * enforced: removing the last primary is *allowed*, because the person may
 * simply have left and a contact who cannot be removed until somebody else is
 * promoted reads as a broken button. The confirmation says what the removal will
 * cost, which is the useful half.
 */
export function ClientContactsTab({ clientId }: { clientId: number | null }) {
  const { data, isPending, isError } = useListClientContacts(
    clientId ?? 0,
    { includeInactive: true },
    { query: { enabled: clientId != null } },
  )

  const [editing, setEditing] = React.useState<Contact | null>(null)
  const [editorOpen, setEditorOpen] = React.useState(false)
  const [removing, setRemoving] = React.useState<Contact | null>(null)

  const remove = useRemoveContact(clientId ?? 0)
  const update = useUpdateContact(clientId ?? 0)

  // A client that does not exist yet. Not an error state and not an empty one —
  // there is nothing to add a contact to until the form has been saved once, and
  // saying so is more useful than an empty grid that looks broken.
  if (clientId == null) {
    return (
      <EmptyState
        title="Contacts come after the client"
        description={
          'Save this client first, then add the people at it. A client is not selectable ' +
          'on a ticket until it has one primary contact.'
        }
      />
    )
  }

  if (isPending) {
    return <Skeleton className="h-40" />
  }
  if (isError) {
    return (
      <p role="alert" className="text-sm text-danger-text">
        The contacts for this client could not be loaded.
      </p>
    )
  }

  const contacts = data?.data ?? []
  const live = contacts.filter((c) => c.isActive !== false)
  const removed = contacts.filter((c) => c.isActive === false)
  const hasPrimary = live.some((contact) => contact.isPrimary)

  function openAdd() {
    setEditing(null)
    setEditorOpen(true)
  }

  function openEdit(contact: Contact) {
    setEditing(contact)
    setEditorOpen(true)
  }

  /**
   * Promoting from the row rather than through the editor.
   *
   * Sends the whole representation with `isPrimary` flipped, because that is
   * what the endpoint takes — a partial body would clear every field it omits.
   * `toContactFormValues` then `toContactWriteRequest` is deliberately the same
   * round trip the editor makes, so the one place that decides how a contact
   * becomes a request stays one place.
   */
  function promote(contact: Contact) {
    if (contact.id == null) return
    update.mutate(
      {
        contactId: contact.id,
        data: { ...toContactWriteRequest(toContactFormValues(contact)), isPrimary: true },
      },
      { onSuccess: () => toast({ title: `${contact.name} is now the primary contact` }) },
    )
  }

  function confirmRemove() {
    const target = removing
    if (target?.id == null) return
    remove.mutate(target.id, {
      onSuccess: () => {
        setRemoving(null)
        toast({
          title: `${target.name} removed`,
          description: 'Tickets they reported still show their name.',
        })
      },
    })
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-sm text-content-muted">
          {live.length === 0
            ? 'No contacts yet.'
            : `${live.length} contact${live.length === 1 ? '' : 's'}`}
          {removed.length > 0 ? ` · ${removed.length} removed` : ''}
        </p>
        {/* type="button" — see the component note. The default would save the client. */}
        <Button type="button" onClick={openAdd}>
          <Plus className="mr-1.5 h-4 w-4" aria-hidden />
          Add contact
        </Button>
      </div>

      {!hasPrimary ? (
        // `border-danger` + `text-danger-text` are the tokens that exist — §12.1
        // defines a 3:1 UI shade and a 4.5:1 text shade and nothing between them.
        // Same note as the banners on the resource and project forms.
        <div
          role="status"
          className="flex items-start gap-2 rounded-card border border-danger bg-surface px-4 py-3 text-sm text-danger-text"
        >
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
          <span>
            <strong className="font-medium">No primary contact.</strong> This client cannot be
            chosen on a ticket until one of its contacts is marked primary.
          </span>
        </div>
      ) : null}

      {contacts.length === 0 ? (
        <EmptyState
          title="No contacts yet"
          description="The people at this client — who reported an issue, and who hears back."
        />
      ) : (
        // Named, because the Toaster renders a second list into the same
        // document and a screen reader landing on either should be able to say
        // which is which.
        <ul
          aria-label="Contacts"
          className="flex flex-col divide-y divide-border rounded-card border border-border bg-surface"
        >
          {[...live, ...removed].map((contact) => {
            const isRemoved = contact.isActive === false
            return (
              <li
                key={contact.id}
                className="flex flex-wrap items-center gap-3 px-4 py-3"
                data-removed={isRemoved || undefined}
              >
                <div className="flex min-w-0 flex-1 flex-col">
                  <span
                    className={
                      isRemoved
                        ? 'flex items-center gap-1.5 text-content-muted line-through'
                        : 'flex items-center gap-1.5 font-medium text-content'
                    }
                  >
                    {contact.name}
                    {contact.isPrimary ? (
                      <>
                        <Star className="h-3.5 w-3.5 fill-current text-primary" aria-hidden />
                        <span className="sr-only">(primary contact)</span>
                      </>
                    ) : null}
                  </span>
                  <span className="truncate text-caption text-content-muted">
                    {[contact.designation, contact.email, contact.phone]
                      .filter(Boolean)
                      .join(' · ')}
                  </span>
                </div>

                {/* Never colour alone — §12.1. Each chip carries its own word. */}
                <span className="flex flex-wrap items-center gap-1">
                  {isRemoved ? <Chip variant="neutral">Removed</Chip> : null}
                  {contact.isPrimary ? <Chip variant="success">Primary</Chip> : null}
                  {contact.notificationOptIn ? <Chip variant="neutral">Notified</Chip> : null}
                  {contact.portalAccess ? <Chip variant="neutral">Portal</Chip> : null}
                </span>

                <span className="flex items-center gap-1">
                  {!isRemoved && !contact.isPrimary ? (
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      onClick={() => promote(contact)}
                      disabled={update.isPending}
                    >
                      Make primary
                    </Button>
                  ) : null}
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    onClick={() => openEdit(contact)}
                    aria-label={`Edit ${contact.name}`}
                  >
                    <Pencil className="h-4 w-4" aria-hidden />
                  </Button>
                  {!isRemoved ? (
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      onClick={() => setRemoving(contact)}
                      aria-label={`Remove ${contact.name}`}
                    >
                      <Trash2 className="h-4 w-4" aria-hidden />
                    </Button>
                  ) : (
                    /*
                     * A removed contact is still editable — correcting the
                     * spelling of a departed contact's name so a historical
                     * ticket reads properly is a real thing to want — but there
                     * is no un-remove, and the icon says why rather than leaving
                     * a gap. `is_active` is not in the server's UPDATE
                     * statement, so an edit structurally cannot resurrect them;
                     * somebody who returns to the client is added again.
                     */
                    <span
                      title="Removed contacts are not restored. Add them again if they return."
                      className="inline-flex h-8 w-8 items-center justify-center text-content-muted"
                    >
                      <Undo2 className="h-4 w-4" aria-hidden />
                      <span className="sr-only">
                        {contact.name} was removed and cannot be restored
                      </span>
                    </span>
                  )}
                </span>
              </li>
            )
          })}
        </ul>
      )}

      <ContactEditorDialog
        clientId={clientId}
        contact={editing}
        open={editorOpen}
        onOpenChange={setEditorOpen}
      />

      <Modal open={removing != null} onOpenChange={(open) => !open && setRemoving(null)}>
        <ModalContent aria-describedby="remove-contact-description">
          <ModalHeader>
            <ModalTitle>Remove {removing?.name}?</ModalTitle>
            <ModalDescription id="remove-contact-description">
              They stop being offered on new tickets. Tickets they already reported keep their
              name — nothing historical is hidden.
              {removing?.isPrimary
                ? ' They are this client’s primary contact, so the client will not be selectable on a ticket until another contact is marked primary.'
                : ''}
            </ModalDescription>
          </ModalHeader>
          <ModalFooter>
            <Button type="button" variant="secondary" onClick={() => setRemoving(null)}>
              Cancel
            </Button>
            <Button
              type="button"
              variant="danger"
              onClick={confirmRemove}
              disabled={remove.isPending}
            >
              {remove.isPending ? 'Removing…' : 'Remove contact'}
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  )
}
