# `feature/onboarding/clients` — B-102, B-103, B-104

The onboarding client master: OB-03's list, OB-04's create, OB-05's read and
edit, OB-05's SPOC panel and OB-05's purchases panel. Nine routes under
`/api/v1/onboarding/clients`.

**This is not `feature/clients`.** That package is the *ticketing* client master
(`clients`, B-025/B-026). The two tables are disjoint, there is no foreign key
between them, and a company present in both is linked at the identity layer
through `client_accounts` by an explicit audited admin action — never matched
automatically by name or PAN, because a false positive there shows one company
another company's tickets (onboarding plan §2.3).

## What is here

| Class | Does |
|---|---|
| `ObClientController` | The client's four routes |
| `ObContactController` | B-103 · the SPOC panel's three |
| `ObApplicationController` | B-104 · the purchases panel's two |
| `ObClientETag` | The one tag both controllers derive and check |
| `ObClientService` | Reads — the OB-03 page, the OB-05 document |
| `ObClientWriteService` | The create, the edit, and both duplicate guards |
| `ObContactService` | B-103 · add, edit, promote, deactivate, and consent |
| `ObApplicationService` | B-104 · buy a product, and renew the licence |
| `ObClientReadRepository` | Every scoped read, as SQL through `JdbcClient` |
| `ObClientChildWriteRepository` | Contacts, purchases and requirements on create |
| `ObContactWriteRepository` | B-103 · SPOC writes and the consent journal |
| `ObApplicationWriteRepository` | B-104 · the purchase insert and update |
| `ObClientScope` | A-112's row-scope rule, as a SQL predicate |
| `SimilarClientNames` | The near-duplicate name detector |
| `ObConsentSource` | B-103 · the closed vocabulary consent is defended with |
| `ObClientExceptionHandler` | RFC 9457 problems, for both controllers |

The entity is `domain/onboarding/ObClient`, **widened** by this task rather than
duplicated — A-112 mapped two columns for the scope guard and its javadoc asked
for exactly this.

## The two guards are not the same shape

- **PAN** is exact, unscoped and **final**. Matched on `pan_blind_index`, a
  deterministic HMAC, so no PAN is decrypted to answer it and §11's audit log
  stays free of routine traffic. `uq_ob_clients_pan_blind` is what makes it true
  under a race; the service check is what makes the message readable.
- **Name** is fuzzy, unscoped and **forceable** with `acknowledgeSimilarNames`.
  "Acme Pvt Ltd" and "Acme Private Limited" are frequently two real clients, so
  the decision belongs to the person who can tell them apart.

Both run unscoped, because "is this client already on file" is a fact about the
organisation and not about the caller. What is *named back* is scoped: a match
the caller cannot see is counted, never named.

## Consent, and why it is built before anything sends a message (B-103)

`PHASE-2-BUILD-PLAN.md` §6.1 defers WhatsApp out of phase 2 entirely and then
names one exception: `whatsapp_opt_in` capture, *"the one item that is genuinely
irreversible"*. V20260903_1210 gave that a `TINYINT` and nothing else, which
records that somebody ticked a box and cannot answer **when** or **on what
basis** — the two facts a challenged consent turns on, and the two that cannot
be reconstructed afterwards.

So three columns and one table:

- `whatsapp_opt_in_at` / `_source` / `_by` on the contact — the current
  position. `ck_ob_client_contacts_consent` makes the flag and its stamp move
  together, so a bare `true` is refused by the column and not only by the
  service.
- `ob_contact_consent_events` — every grant and every withdrawal, insert-only by
  trigger. A row can say where consent stands; only a journal can say it once
  stood, which is what the organisation needs when it is asked about messages it
  already sent.

Two rules in `ObContactService` carry the weight:

- **A `true` needs a basis.** `whatsappOptInSource` is required beside it, from a
  closed enum, and it describes *how the client gave consent* rather than which
  screen recorded it — a source naming the screen would read identically on every
  staff-entered row.
- **An unchanged consent is not restamped.** Correcting a phone number in
  November must not re-date a consent given in March. That is the same evidence
  destroyed by a missing basis, arrived at silently and by a routine edit.

`UNRECORDED` is the backfill's value for rows that predate all of this. It reads
back and no caller can send it, so those SPOCs stay visibly in need of
re-approach instead of passing for settled.

**One consequence worth knowing before you write a fixture.** A contact who has
consented cannot be hard-deleted: the journal refuses `DELETE` and references the
contact without a cascade, on the pattern `ob_step_communications` already uses
for `author_contact_id`. Nothing in the product deletes a contact — they are
deactivated — but a test that tears its rows down cannot, so `ObContactsIT`
builds a fresh client per test and removes nothing.

## Purchases, and the two things that were unrepresentable until B-104

OB-04 captured a client's purchases at boarding, and that was the only moment
they could ever be captured. Two ordinary things therefore had no representation
in the API at all once the wizard closed.

- **A client buying a second product.** The purchase is what a journey is
  instantiated from, so with no way to add one, a client who bought a module six
  months in had nothing to be onboarded through — and no way to get one short of
  somebody writing the row by hand and remembering to call C-103's service
  afterwards.
- **A renewal.** `license_end` is what the backlog calls "the renewal anchor",
  and it is why `ix_ob_client_applications_license_end` exists — the migration's
  own words, "renewals will read this without a client in hand". A column
  writable once at boarding and never moved forward is not an anchor: by the time
  a renewals module reads it, every row past its first year says the licence
  lapsed.

**Adding a purchase instantiates its journey, in the same transaction.** Not a
follow-up call the caller has to remember. `ObClientWriteService` does exactly
this for the wizard's products, and a client with a purchase and no journey is
one of the two states `ObClientChildWriteRepository` names as having to be
noticed and repaired by hand. C-103's own rule then applies from the other side:
a product bought **after** this client's gate has opened instantiates directly
`OPEN` rather than `LOCKED` (plan §5.3 item 3), so the client is not re-gated on
prerequisites they have already satisfied — precisely the case this route creates
and the wizard never could.

**The product identifies a purchase; it is not a field on it.** `ob_journeys`
carries a composite foreign key straight to `(ob_client_id, product_id)` rather
than an `application_id`, so a `PATCH` naming a different product is refused with
`ob-application-product-immutable` rather than ignored. MySQL would refuse the
repoint anyway, and succeeding would be worse: the journey's `template_id` is
pinned to the template of the product that was actually bought, so the client
would be onboarded through the old product's steps under the new product's name.

**The add re-checks the product; the edit does not.** Whether a product may be
*bought* and whether an existing purchase may be *corrected* are different
questions with opposite answers. A product retired last quarter is out of OB-04's
picker and its clients are still onboarding through it — refusing to renew their
licence would make a retirement retroactively strand everybody who already bought
it, which is the opposite of what `ob_products` retires rather than deletes for.

## There is no `DELETE` on a purchase, and the absence is the design

`fk_ob_journeys_application` has no `ON DELETE` clause, so it is `RESTRICT` — and
every purchase acquires a journey the moment it is made. A delete route could
therefore only be one of two things:

1. Issue the `DELETE` and let MySQL refuse it. A 500 dressed as a feature: it
   would fail for every purchase that has ever existed, which is all of them.
2. Delete the journey first — reaching into `ob_journeys`, `ob_journey_steps` and
   everything hanging off them (clock events, sign-off requests, escalations)
   from a package that owns none of it, to destroy the record of work that was
   done. Archiving does not help: `archived_at` leaves the row in place and
   `RESTRICT` still refuses.

So it is not offered rather than offered broken. `ObApplicationsIT` exercises
both halves of that claim rather than asserting them in prose, because the
argument stops being true the day somebody adds a cascade to that key.

## One defect B-104 found on its way past

`ObClientReadRepository.localDate` was `rs.getDate(..).toLocalDate()`, which
renders an instant through the **JVM default zone** — so a date stored in a UTC
database and read on an IST machine came back a day early. That is **A-067's
defect**, already fixed with a comment at the call site in
`TicketReportRepository`, `ReportScheduleRepository` and `WidgetRepository`; this
repository was the last one carrying it.

It was latent because nothing had ever compared a date written through this
package against the same date read back out of it. `licenseEnd` was the first
field where doing so was the whole point of the test — but the mapper is shared,
so it was wrong for **`onboardingDate`** as well, on every OB-03 row and every
OB-05 header. A client boarded on the 7th displayed as the 6th.

The fix is `getObject(.., LocalDate.class)`, and the rule behind it is worth
carrying forward: **a `DATE` has no instant and must not be given one.**
`ObContactWriteRepository.Consent.atTimestamp` is the write-side counterpart of
the same family, on a `DATETIME(6)` that genuinely does carry an instant.

## The primary SPOC is stricter here than in the ticketing master

`updateClientContact` (B-027) lets a client end up with no primary and explains
why that is fine over there. None of its reasons hold here — see
`LastPrimaryContactException`, which sets them out one by one. The short version:
onboarding clients are created with exactly one primary, there is no later gate
that reports a missing one, and the mail simply goes nowhere. Demoting or
deactivating the last primary is a `409`; promoting a replacement is one request
and demotes the incumbent in the same transaction.

## What these tasks deliberately left for later

| Left | Owner |
|---|---|
| Removing a purchase, and unpicking the journey behind it | beside C-103's instantiation |
| Requirements as structured rows with rich text | B-106 |
| Client attachments | B-107 |
| OB-03 and OB-04 screens, and the SPOC panel's UI | B-108, B-109 |
| The prerequisites snapshot the create would otherwise write | B-124, B-125 |
| The portal login `createPortalLogin` promises | B-126 |
| `ObJourneyStrip.utilizedHours` | C-120 |
| A `SELECT, INSERT` grants branch for `ob_contact_consent_events` | Stream A |

`createPortalLogin: true` is **refused** with a 409 rather than ignored — see
`PortalLoginUnavailableException` for why refusing is the safer of the two — and
`utilizedHours` is null rather than `0.0`, because a zero on screen reads as data
and a null renders as an em dash.

The grants row is the one owed outward: `apply-app-grants.sql` sweeps every table
and hands the consent journal the default `SELECT, INSERT, UPDATE, DELETE`. It
wants the branch `ob_step_communications` and `ob_step_clock_events` already
have. That file is Stream A's; the two triggers hold the guarantee meanwhile.

## Reading order

`ObClientController` → `ObClientWriteService` (the guards) →
`ObClientReadRepository` (the SQL, including where the RAG filter runs) →
`ObStepRag` one package up, which now holds the colour formula in both Java and
SQL with an IT pinning that they agree.

For the SPOC half: `ObContactController` → `ObContactService` (the three rules
above, in `consentFor` and `refuseStrandingTheClient`) → `ObContactWriteRepository`
(and its `Consent.atTimestamp`, which is where a real timezone bug was caught).

For the purchases half: `ObApplicationService`'s class javadoc first — it carries
the whole argument, including why there are two routes and not three — then
`ObApplicationController`, then `ObApplicationWriteRepository` (whose `update`
leaves `product_id` out of the `SET` list on purpose). `ObApplicationsIT` is
worth reading beside it: the two `RESTRICT` tests are what keep the no-`DELETE`
decision honest.
