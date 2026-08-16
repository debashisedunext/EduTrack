import { describe, expect, it } from 'vitest'

import type { Contact } from '@/api/generated/model/contact'

import {
  contactFormSchema,
  emptyContactForm,
  toContactFormValues,
  toContactWriteRequest,
} from './contactForm'

/**
 * B-027 · the row editor's shape, its validation and its two translations.
 *
 * Tested here rather than through the rendered dialog for the reason
 * `clientForm.test.ts` gives beside it: the mapping between a form and a request
 * is the part worth testing on its own, and it is unreachable behind a dialog
 * that has to be opened first.
 */
describe('the contact form', () => {
  describe('what a new contact starts as', () => {
    /**
     * Not neutral, and deliberately so. §11's recipient lists name the client
     * contact on the mails a client is meant to receive, so somebody added
     * without the question being answered should hear about their own tickets.
     * Being the primary and holding portal access are both decisions.
     */
    it('opts into notifications and into nothing else', () => {
      expect(emptyContactForm.notificationOptIn).toBe(true)
      expect(emptyContactForm.isPrimary).toBe(false)
      expect(emptyContactForm.portalAccess).toBe(false)
    })

    it('holds empty strings, never undefined', () => {
      // React switches an input from uncontrolled to controlled on the first
      // keystroke otherwise, and warns.
      expect(emptyContactForm.name).toBe('')
      expect(emptyContactForm.designation).toBe('')
      expect(emptyContactForm.phone).toBe('')
    })
  })

  describe('validation', () => {
    const valid = { ...emptyContactForm, name: 'Sara Kapoor', email: 'sara@acme.example' }

    it('accepts a name and an email and nothing else', () => {
      expect(contactFormSchema.safeParse(valid).success).toBe(true)
    })

    it('requires a name', () => {
      const result = contactFormSchema.safeParse({ ...valid, name: '   ' })
      expect(result.success).toBe(false)
    })

    /**
     * Required on the form where `Contact.email` is nullable on the wire, and
     * the asymmetry is the point: the column is nullable because B-035's import
     * will write rows without an address, but a contact entered *here* must have
     * one — `notificationOptIn` defaults to true and a mail D-036 can never
     * deliver is worse than a refused save.
     */
    it('requires a well-formed email', () => {
      expect(contactFormSchema.safeParse({ ...valid, email: '' }).success).toBe(false)
      expect(contactFormSchema.safeParse({ ...valid, email: 'sara' }).success).toBe(false)
    })

    /**
     * B-028 · zod's `.email()` accepted this and the server now does not, so
     * the form would have taken the address, sent it, and shown the user a
     * server error on a field it had just marked valid. Blueprint line 948's
     * "valid emails" is one rule, and `@/lib/email` is the browser's half of it.
     */
    it('refuses an address with no dotted TLD, which zod .email() accepted', () => {
      expect(contactFormSchema.safeParse({ ...valid, email: 'sara@acme' }).success).toBe(false)
      expect(contactFormSchema.safeParse({ ...valid, email: 'sara@localhost' }).success)
        .toBe(false)
      expect(contactFormSchema.safeParse({ ...valid, email: 'sara@acme.example' }).success)
        .toBe(true)
    })

    /**
     * "Missing" and "malformed" stay different answers — the box the desk left
     * empty is told it is required, not that what they did not type is
     * malformed.
     */
    it('reports a blank address as required, not as malformed', () => {
      const failure = contactFormSchema.safeParse({ ...valid, email: '  ' })

      expect(failure.success).toBe(false)
      expect(failure.success === false && failure.error.issues[0].message)
        .toBe('An email address is required.')
    })

    /**
     * **120, not 150.** The contract declared `maxLength: 150` against a
     * `VARCHAR(120)` until B-027 — a value the form accepted, the server
     * accepted, and MySQL refused. This file restates the column widths because
     * there is nothing to import them from, so the boundary is asserted in both
     * directions: the drift it can suffer is otherwise silent.
     */
    it('holds the column widths, at the boundary in both directions', () => {
      expect(contactFormSchema.safeParse({ ...valid, name: 'a'.repeat(120) }).success).toBe(true)
      expect(contactFormSchema.safeParse({ ...valid, name: 'a'.repeat(121) }).success).toBe(false)

      expect(contactFormSchema.safeParse({ ...valid, phone: '9'.repeat(30) }).success).toBe(true)
      expect(contactFormSchema.safeParse({ ...valid, phone: '9'.repeat(31) }).success).toBe(false)

      expect(
        contactFormSchema.safeParse({ ...valid, designation: 'a'.repeat(80) }).success,
      ).toBe(true)
      expect(
        contactFormSchema.safeParse({ ...valid, designation: 'a'.repeat(81) }).success,
      ).toBe(false)
    })
  })

  describe('reading a contact into the form', () => {
    it('turns every null column into the form’s empty string', () => {
      const contact: Contact = {
        id: 1,
        name: 'Erin Walsh',
        designation: null,
        email: 'erin@bluewave.example',
        phone: null,
        isPrimary: true,
        notificationOptIn: false,
        portalAccess: false,
        isActive: true,
      }

      expect(toContactFormValues(contact)).toEqual({
        name: 'Erin Walsh',
        designation: '',
        email: 'erin@bluewave.example',
        phone: '',
        isPrimary: true,
        notificationOptIn: false,
        portalAccess: false,
      })
    })
  })

  describe('writing the form back', () => {
    /**
     * The server reads this body as the whole representation, so **every field
     * goes on every save** — B-026's call on `ClientWriteRequest`. Omitting an
     * emptied input would mean the admin who cleared a designation is told the
     * save succeeded and finds it still there.
     */
    it('sends every field, turning blanks into the null the column means', () => {
      const request = toContactWriteRequest({
        name: '  Sara Kapoor  ',
        designation: '   ',
        email: '  sara@acme.example ',
        phone: '',
        isPrimary: true,
        notificationOptIn: false,
        portalAccess: true,
      })

      expect(request).toEqual({
        name: 'Sara Kapoor',
        designation: null,
        email: 'sara@acme.example',
        phone: null,
        isPrimary: true,
        notificationOptIn: false,
        portalAccess: true,
      })
    })

    /**
     * Removal is the `DELETE`. A second way to reach the same outcome is how two
     * controls for one thing end up disagreeing — B-018's argument against a
     * separate "clear this override" button, one master over.
     */
    it('never sends isActive', () => {
      const request = toContactWriteRequest(emptyContactForm) as unknown as Record<string, unknown>
      expect('isActive' in request).toBe(false)
    })

    /** A round trip through both translations must be the identity. */
    it('round-trips a contact unchanged', () => {
      const contact: Contact = {
        id: 2,
        name: 'Dev Patel',
        designation: 'Helpdesk Lead',
        email: 'dev@acme.example',
        phone: '+91 98200 22222',
        isPrimary: false,
        notificationOptIn: true,
        portalAccess: false,
        isActive: true,
      }

      expect(toContactWriteRequest(toContactFormValues(contact))).toEqual({
        name: 'Dev Patel',
        designation: 'Helpdesk Lead',
        email: 'dev@acme.example',
        phone: '+91 98200 22222',
        isPrimary: false,
        notificationOptIn: true,
        portalAccess: false,
      })
    })
  })
})
