import { AlertTriangle, Star } from 'lucide-react'

import { useListClientContacts } from '@/api/generated/clients/clients'
import { Chip } from '@/components/ui/chip'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'

/**
 * B-026 · S-33's Contacts tab.
 *
 * <h2>Read-only, and that is the task boundary rather than an omission</h2>
 *
 * B-027 is "`client_contacts` child grid — add/edit/remove, primary flag,
 * notification opt-in, portal access", and `POST /clients/{clientId}/contacts`
 * is a seventh "declared, mocked, never mounted" operation waiting for it. What
 * B-026 owns is the tab: reading the contacts, and stating the one rule an
 * administrator needs to know from this screen.
 *
 * The alternative was to leave the tab out until B-027, the way `ProjectTabs`
 * withheld Settings until B-019 — and the cases are different. A greyed-out tab
 * and a broken one look identical, which is why an *unbuilt* tab is not rendered;
 * this one is built and shows real data. Hiding it would mean an admin editing a
 * client cannot see who the contacts are, on the screen whose whole subject is
 * that client.
 *
 * <h2>B-028's gate, stated rather than enforced</h2>
 *
 * **A client without a primary contact is not selectable on a ticket.** That is
 * blueprint §4B.2 and B-028's task line, and it is enforced on the ticket create
 * path where a caller can act on it — not here, where refusing the save would
 * make the rule impossible to satisfy: the save is what creates the client the
 * contact must hang off. So the tab warns, with the count, and the form still
 * saves. `hasPrimaryContact` on the detail read is where the answer comes from.
 */
export function ClientContactsTab({ clientId }: { clientId: number | null }) {
  const { data, isPending, isError } = useListClientContacts(clientId ?? 0, {
    query: { enabled: clientId != null },
  })

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
  const hasPrimary = contacts.some((contact) => contact.isPrimary)

  return (
    <div className="flex flex-col gap-4">
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
        <ul className="flex flex-col divide-y divide-border rounded-card border border-border bg-surface">
          {contacts.map((contact) => (
            <li key={contact.id} className="flex flex-wrap items-center gap-3 px-4 py-3">
              <div className="flex min-w-0 flex-1 flex-col">
                <span className="flex items-center gap-1.5 font-medium text-content">
                  {contact.name}
                  {contact.isPrimary ? (
                    <>
                      <Star className="h-3.5 w-3.5 fill-current text-primary" aria-hidden />
                      <span className="sr-only">(primary contact)</span>
                    </>
                  ) : null}
                </span>
                <span className="truncate text-caption text-content-muted">
                  {contact.email}
                  {contact.phone ? ` · ${contact.phone}` : ''}
                </span>
              </div>
              {/* Never colour alone — §12.1. Each chip carries its own word. */}
              <span className="flex flex-wrap items-center gap-1">
                {contact.isPrimary ? <Chip variant="success">Primary</Chip> : null}
                {contact.notificationOptIn ? <Chip variant="neutral">Notified</Chip> : null}
                {contact.portalAccess ? <Chip variant="neutral">Portal</Chip> : null}
              </span>
            </li>
          ))}
        </ul>
      )}

      <p className="text-caption text-content-muted">
        Adding, editing and removing contacts is B-027 and is not built yet. This tab shows what
        the client has.
      </p>
    </div>
  )
}
