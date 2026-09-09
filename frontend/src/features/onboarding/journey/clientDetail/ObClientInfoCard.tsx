import type { ObClientDetail } from '@/api/generated/model'
import { useListObClientAttachments } from '@/api/generated/onboarding/onboarding'
import { Chip } from '@/components/ui/chip'
import { RichTextView } from '@/components/ui/rich-text-view'

import { productIcon } from './productIcon'

/**
 * The mockup's "Client info" card — the right half of OB-05's closing grid,
 * beside B-126's portal-login panel. Onboarding-Module-Plan.md §9: SPOCs,
 * products bought, requirements, address, attachments.
 *
 * ## Everything here is the detail document, except the attachments
 *
 * `ObClientDetail` already carries contacts, applications, requirements and
 * the address — one read for the page, the contract's own design. Attachments
 * are the one child the document deliberately does not embed (they are a
 * paginated, mutable store with their own upload pipeline), so this card makes
 * the page's one extra read, `listObClientAttachments`, and renders names
 * only — OB-05 "lists documents by name", the download surface is A-102's.
 *
 * ## Inactive SPOCs render, and say so
 *
 * The contract sends active and inactive both, on its own argument: a panel
 * that could not see a departed SPOC could not explain whose name is on a past
 * sign-off. Hiding them here would re-open exactly that hole one layer up.
 *
 * ## No PAN
 *
 * §9's OB-05 row keeps identity data off the header; this card is where
 * authorized roles will read it once A-113's audited reveal lands. Until then
 * the masked value stays off the screen entirely rather than sitting on it as
 * a string that looks like a leak in every screenshot.
 */
export function ObClientInfoCard({ detail }: { detail: ObClientDetail }) {
  const attachments = useListObClientAttachments(detail.id)
  const files = (attachments.data?.data ?? []).filter((a) => !a.deletedAt)

  const contacts = detail.contacts ?? []
  const applications = detail.applications ?? []
  const requirements = detail.requirements ?? []

  return (
    <section
      aria-labelledby="ob-client-info"
      className="rounded-card border border-border bg-surface p-5 shadow-sm"
    >
      <h2 id="ob-client-info" className="m-0 text-base font-semibold text-content">
        Client info
      </h2>

      <div className="mt-3 flex flex-col gap-4 text-sm">
        <div>
          <Eyebrow>SPOC</Eyebrow>
          {contacts.length === 0 ? (
            <p className="m-0 mt-1 text-caption text-content-muted">No contacts recorded.</p>
          ) : (
            contacts.map((contact) => (
              <div key={contact.id} className={contact.isActive ? 'mt-1' : 'mt-1 opacity-60'}>
                <span className="font-medium text-content">{contact.name}</span>
                {contact.designation && (
                  <span className="text-content-muted"> · {contact.designation}</span>
                )}
                {contact.isPrimary && (
                  <Chip className="ml-1.5 bg-primary-soft text-[10px] text-primary">PRIMARY</Chip>
                )}
                {!contact.isActive && <Chip className="ml-1.5 text-[10px]">Inactive</Chip>}
                <div className="text-caption text-content-muted">
                  {contact.email}
                  {contact.phone && ` · ${contact.phone}`}
                </div>
              </div>
            ))
          )}
        </div>

        <div>
          <Eyebrow>Products bought</Eyebrow>
          <div className="mt-1 flex flex-wrap gap-1.5">
            {applications.map((application) => (
              <Chip key={application.id}>
                <span aria-hidden="true">{productIcon(application.product.code)}</span>
                {application.product.name}
                {application.licenseType && (
                  <span className="font-normal text-content-muted">· {application.licenseType}</span>
                )}
              </Chip>
            ))}
          </div>
        </div>

        <div>
          <Eyebrow>Requirements</Eyebrow>
          {requirements.length === 0 ? (
            <p className="m-0 mt-1 text-caption text-content-muted">
              None captured at boarding.
            </p>
          ) : (
            <ul className="m-0 mt-1 flex list-none flex-col gap-2 p-0">
              {requirements.map((requirement) => (
                <li key={requirement.id} className="flex items-start gap-2">
                  {/* B-106's flag, not a live control — confirming a
                      requirement met is the portal's and B-108's write. */}
                  <span
                    className={
                      requirement.isMet ? 'shrink-0 text-success-text' : 'shrink-0 text-content-muted'
                    }
                    title={requirement.isMet ? 'Met' : 'Not yet met'}
                    aria-label={requirement.isMet ? 'Met' : 'Not yet met'}
                  >
                    {requirement.isMet ? '✓' : '○'}
                  </span>
                  <div className="min-w-0">
                    {requirement.title && (
                      <div className="font-medium text-content">{requirement.title}</div>
                    )}
                    <RichTextView html={requirement.bodyHtml} className="text-caption" />
                  </div>
                </li>
              ))}
            </ul>
          )}
        </div>

        <div>
          <Eyebrow>Address</Eyebrow>
          <p className="m-0 mt-1 text-caption text-content-muted">
            {detail.address || 'Not recorded'}
          </p>
        </div>

        <div>
          <Eyebrow>Attachments ({files.length})</Eyebrow>
          <div className="mt-1 flex flex-wrap gap-1.5">
            {attachments.isPending ? (
              <span className="text-caption text-content-muted">Loading…</span>
            ) : files.length === 0 ? (
              <span className="text-caption text-content-muted">None</span>
            ) : (
              files.map((file) => (
                <Chip key={file.id} className="max-w-full">
                  <span aria-hidden="true">📎</span>
                  <span className="truncate">{file.fileName}</span>
                </Chip>
              ))
            )}
          </div>
        </div>
      </div>
    </section>
  )
}

function Eyebrow({ children }: { children: React.ReactNode }) {
  return (
    <div className="text-caption font-semibold uppercase tracking-wide text-content-muted">
      {children}
    </div>
  )
}
