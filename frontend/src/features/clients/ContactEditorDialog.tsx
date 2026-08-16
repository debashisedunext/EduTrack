import * as React from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'

import { ApiError } from '@/api/http'
import type { Contact } from '@/api/generated/model/contact'

import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  Modal,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
} from '@/components/ui/modal'

import { FormField } from '../masters/resources/FormField'

import {
  contactFormSchema,
  emptyContactForm,
  toContactFormValues,
  toContactWriteRequest,
  type ContactFormValues,
} from './contactForm'
import { useCreateContact, useUpdateContact } from './contactQueries'

/**
 * B-027 · the row editor behind S-33's Contacts tab, for both verbs.
 *
 * <h2>A dialog, and specifically a portalled one</h2>
 *
 * The Contacts tab is a panel of `ClientFormPage`'s `<form>`, so an inline
 * editor would be **a form inside a form** — invalid HTML, and in practice a
 * `<button type="submit">` in the contact editor would submit the *client*.
 * Radix portals `ModalContent` to `document.body`, so this form is a sibling of
 * the client's rather than a descendant, and its submit button belongs to it.
 *
 * That trap is not hypothetical: every button on the tab behind this dialog has
 * to carry `type="button"` for the same reason, and the default is `submit`.
 *
 * <h2>One component for add and edit</h2>
 *
 * The reason `ClientFormPage` gives for being one page for both verbs, one level
 * down: they are one form, every field and every rule is shared, and two
 * components would be the same file twice with one copy always slightly behind.
 * `contact` being null is the whole difference.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * There is no `isActive` control. Removal is the `DELETE` on the row behind
 * this, and a second way to reach the same outcome is how two controls end up
 * disagreeing — B-018's argument against a separate "clear this override"
 * button, one master over.
 */
export function ContactEditorDialog({
  clientId,
  contact,
  open,
  onOpenChange,
}: {
  clientId: number
  /** Null when adding. */
  contact: Contact | null
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const isEdit = contact != null
  const [bannerError, setBannerError] = React.useState<string | null>(null)

  const form = useForm<ContactFormValues>({
    resolver: zodResolver(contactFormSchema),
    defaultValues: emptyContactForm,
    mode: 'onBlur',
  })

  // Reset on every open rather than once. The dialog is mounted for the life of
  // the tab and reused for every row, so seeding it once would show the previous
  // contact's values — and, on the add path after an edit, would silently submit
  // them as a new contact.
  React.useEffect(() => {
    if (!open) return
    setBannerError(null)
    form.reset(contact ? toContactFormValues(contact) : emptyContactForm)
  }, [open, contact, form])

  const create = useCreateContact(clientId)
  const update = useUpdateContact(clientId)
  const isSaving = create.isPending || update.isPending

  function applyServerError(error: unknown): void {
    if (!(error instanceof ApiError)) {
      setBannerError('Something went wrong. Try again.')
      return
    }
    const known = new Set(Object.keys(contactFormSchema.shape))
    const unmatched: string[] = []

    for (const [key, messages] of Object.entries(error.fieldErrors)) {
      const message = messages[0] ?? 'Invalid'
      if (known.has(key)) {
        form.setError(key as keyof ContactFormValues, { type: 'server', message })
      } else {
        unmatched.push(message)
      }
    }

    const matched = Object.keys(error.fieldErrors).filter((key) => known.has(key))
    if (matched.length > 0) {
      form.setFocus(matched[0] as keyof ContactFormValues)
      return
    }
    setBannerError(unmatched[0] ?? error.problem.detail ?? 'The contact was not saved.')
  }

  const onSubmit = form.handleSubmit((values) => {
    setBannerError(null)
    const data = toContactWriteRequest(values)

    if (isEdit && contact?.id != null) {
      update.mutate(
        { contactId: contact.id, data },
        { onSuccess: () => onOpenChange(false), onError: applyServerError },
      )
      return
    }
    create.mutate(data, {
      onSuccess: () => onOpenChange(false),
      onError: applyServerError,
    })
  })

  return (
    <Modal open={open} onOpenChange={onOpenChange}>
      <ModalContent aria-describedby="contact-editor-description">
        <ModalHeader>
          <ModalTitle>{isEdit ? 'Edit contact' : 'Add contact'}</ModalTitle>
          <ModalDescription id="contact-editor-description">
            The people at this client — who reported an issue, and who hears back.
          </ModalDescription>
        </ModalHeader>

        {/*
          A real <form>, so Enter submits and the browser's own validation and
          autofill behave. Safe only because ModalContent is portalled out of
          ClientFormPage's form — see the component note.
        */}
        <form onSubmit={onSubmit} className="flex flex-col gap-5">
          {bannerError ? (
            <div
              role="alert"
              className="rounded-card border border-danger bg-surface px-4 py-3 text-sm text-danger-text"
            >
              {bannerError}
            </div>
          ) : null}

          <div className="grid gap-5 sm:grid-cols-2">
            <FormField
              id="contactName"
              label="Name"
              required
              error={form.formState.errors.name?.message}
            >
              {(aria) => <Input {...aria} {...form.register('name')} placeholder="Sara Kapoor" />}
            </FormField>

            <FormField
              id="contactDesignation"
              label="Designation"
              error={form.formState.errors.designation?.message}
              hint="What the desk sees before they pick up the call."
            >
              {(aria) => (
                <Input {...aria} {...form.register('designation')} placeholder="IT Director" />
              )}
            </FormField>

            <FormField
              id="contactEmail"
              label="Email"
              required
              error={form.formState.errors.email?.message}
              hint="Unique among this client's contacts. The same address at a different client is fine."
            >
              {(aria) => (
                <Input {...aria} type="email" {...form.register('email')} placeholder="sara@acme.example" />
              )}
            </FormField>

            <FormField
              id="contactPhone"
              label="Phone"
              error={form.formState.errors.phone?.message}
            >
              {(aria) => <Input {...aria} {...form.register('phone')} placeholder="+91 98200 11111" />}
            </FormField>
          </div>

          <fieldset className="flex flex-col gap-3 rounded-card border border-border p-4">
            <legend className="px-1 text-sm font-medium text-content">Options</legend>

            <Checkbox
              id="contactIsPrimary"
              label="Primary contact"
              hint="The client cannot be chosen on a ticket without one. Marking this one clears the flag on whoever holds it now."
              register={form.register('isPrimary')}
            />
            <Checkbox
              id="contactNotificationOptIn"
              label="Receives email updates"
              hint="Ticket mail addressed to this client goes to everybody with this ticked."
              register={form.register('notificationOptIn')}
            />
            <Checkbox
              id="contactPortalAccess"
              label="Portal access"
              hint="Reserved for the client portal, which is not part of this phase."
              register={form.register('portalAccess')}
            />
          </fieldset>

          <ModalFooter>
            <Button type="button" variant="secondary" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={isSaving}>
              {isSaving ? 'Saving…' : isEdit ? 'Save contact' : 'Add contact'}
            </Button>
          </ModalFooter>
        </form>
      </ModalContent>
    </Modal>
  )
}

/**
 * A labelled checkbox with its hint wired to `aria-describedby`.
 *
 * Local rather than promoted to `components/ui/`: this is its first caller, and
 * that directory is Stream C's — B-010's `FilterDropdown` move is still waiting
 * on their sign-off and B-011, B-016 and B-019 all declined to make a second
 * unilateral change there. A second caller is this codebase's trigger.
 */
function Checkbox({
  id,
  label,
  hint,
  register,
}: {
  id: string
  label: string
  hint: string
  register: ReturnType<ReturnType<typeof useForm<ContactFormValues>>['register']>
}) {
  return (
    <div className="flex items-start gap-2.5">
      <input
        id={id}
        type="checkbox"
        aria-describedby={`${id}-hint`}
        className="mt-0.5 h-4 w-4 rounded border-border text-primary focus:ring-2 focus:ring-primary"
        {...register}
      />
      <div className="flex flex-col">
        <label htmlFor={id} className="text-sm text-content">
          {label}
        </label>
        <p id={`${id}-hint`} className="text-caption text-content-muted">
          {hint}
        </p>
      </div>
    </div>
  )
}
