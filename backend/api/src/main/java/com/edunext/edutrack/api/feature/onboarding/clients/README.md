# `feature/onboarding/clients` — B-102, B-103

The onboarding client master: OB-03's list, OB-04's create, OB-05's read and
edit, and OB-05's SPOC panel. Seven routes under
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
| `ObClientETag` | The one tag both controllers derive and check |
| `ObClientService` | Reads — the OB-03 page, the OB-05 document |
| `ObClientWriteService` | The create, the edit, and both duplicate guards |
| `ObContactService` | B-103 · add, edit, promote, deactivate, and consent |
| `ObClientReadRepository` | Every scoped read, as SQL through `JdbcClient` |
| `ObClientChildWriteRepository` | Contacts, purchases and requirements on create |
| `ObContactWriteRepository` | B-103 · SPOC writes and the consent journal |
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
| Purchases as their own sub-resource | B-104 |
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
