# `feature/onboarding/clients` — B-102

The onboarding client master: OB-03's list, OB-04's create, OB-05's read and
edit. Four routes under `/api/v1/onboarding/clients`.

**This is not `feature/clients`.** That package is the *ticketing* client master
(`clients`, B-025/B-026). The two tables are disjoint, there is no foreign key
between them, and a company present in both is linked at the identity layer
through `client_accounts` by an explicit audited admin action — never matched
automatically by name or PAN, because a false positive there shows one company
another company's tickets (onboarding plan §2.3).

## What is here

| Class | Does |
|---|---|
| `ObClientController` | The four routes, `ETag` and `If-Match` |
| `ObClientService` | Reads — the OB-03 page, the OB-05 document |
| `ObClientWriteService` | The create, the edit, and both duplicate guards |
| `ObClientReadRepository` | Every scoped read, as SQL through `JdbcClient` |
| `ObClientChildWriteRepository` | Contacts, purchases and requirements on create |
| `ObClientScope` | A-112's row-scope rule, as a SQL predicate |
| `SimilarClientNames` | The near-duplicate name detector |
| `ObClientExceptionHandler` | RFC 9457 problems, scoped to this controller |

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

## What this task deliberately left for later

| Left | Owner |
|---|---|
| Managing contacts after the create — add, edit, promote, deactivate, `whatsapp_opt_in`'s timestamp and source | B-103 |
| Purchases as their own sub-resource | B-104 |
| Requirements as structured rows with rich text | B-106 |
| Client attachments | B-107 |
| OB-03 and OB-04 screens | B-108, B-109 |
| The prerequisites snapshot the create would otherwise write | B-124, B-125 |
| The portal login `createPortalLogin` promises | B-126 |
| `ObJourneyStrip.utilizedHours` | C-120 |

The last two are the ones with visible edges. `createPortalLogin: true` is
**refused** with a 409 rather than ignored — see
`PortalLoginUnavailableException` for why refusing is the safer of the two — and
`utilizedHours` is null rather than `0.0`, because a zero on screen reads as
data and a null renders as an em dash.

## Reading order

`ObClientController` → `ObClientWriteService` (the guards) →
`ObClientReadRepository` (the SQL, including where the RAG filter runs) →
`ObStepRag` one package up, which now holds the colour formula in both Java and
SQL with an IT pinning that they agree.
