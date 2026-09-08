# `onboarding/moduleaccess` — OB-08, roles and module access

**A-117.** Who can reach a module, as what, and the audited grant and revoke
behind it. Three routes over one table, `user_module_access`.

| Route | Who | Answers |
|---|---|---|
| `GET /api/v1/onboarding/module-access` | OB Admin | the grid, keyset-paged |
| `POST /api/v1/onboarding/module-access` | OB Admin | 201, or 409 |
| `POST /api/v1/onboarding/module-access/{grantId}/revoke` | OB Admin | 200, or 422 |

## Four things about this package that are not obvious

### 1. Revoking is not deleting, and the verb says so

`POST .../revoke`, not `DELETE .../{grantId}`. The row survives with
`revoked_at` and `revoked_by` stamped.

A-109 chose that shape because an access audit is run *after* something has
been seen that should not have been, and the question it asks is "who could
reach this module in August". A `DELETE` leaves nothing to answer with. A
`DELETE` verb over a row that survives would describe the wrong act to every
reader and every generated client, so the verb follows the storage rather than
the other way round.

`ObModuleAccessRepository` has no `delete` method for the same reason, and none
should be added — a method that exists is a method somebody eventually calls.

### 2. The refusal is 403, and this is the only place in the module where it is

CLAUDE.md's rule is that an out-of-scope id answers **404, not 403**, so no
existence leaks. Every other onboarding feature follows it.

That rule is about **rows**. It protects the fact that a particular client,
journey or ticket exists. Here there is no row whose existence a 404 could
protect: the caller is refused organisation-wide administration, not a record.
They have also already passed `ModuleAccessGuard` to reach the route at all, so
they are known to hold onboarding access — and "you are not the administrator"
tells them nothing the missing screen would not.

The contract states the 403 on all three operations. The 404s on this surface
belong to the module gate above it, and to a `grantId` that genuinely does not
exist.

The **read** being Admin-only is the part most likely to be undone by someone
reading plan §3, which makes Viewer "everything, read-only". This is the one
exception, and it is deliberate: who administers a module is not onboarding
data, it is the access-control table for the module, and a Viewer able to
enumerate every administrator has been handed the list of accounts worth
attacking. `ObModuleAccessServiceTest.listIsAdminOnly` is the guard on it.

### 3. Neither a grant nor a revoke is instant, and the response says by how much

The `modules` and `moduleRoles` claims are minted at login (A-110, A-112) and
access tokens live fifteen minutes. So a grant made now is effective within
fifteen minutes, and — the direction that matters — **a revoke leaves a valid
token carrying the old entitlement for up to fifteen minutes**.

That is a deliberate consequence of putting entitlement in the claim rather
than reading `user_module_access` on every request, which is what A-111's
design chose and what keeps the gate off the hot path.

Every response carries `tokenLagSeconds` so OB-08 can say so. For a routine
role change the lag is fine. For a revocation prompted by something going
wrong it is not, and **the answer is not to make the guard hit the database** —
it is to revoke the user's refresh-token family, which ends the session rather
than narrowing it. The screen should offer that as the next action rather than
leave an admin to conclude the revoke failed.

### 4. The last administrator cannot be revoked

422, refused rather than allowed. A module with no administrator cannot grant
anybody access to itself — including the access needed to undo the revoke — so
recovering means editing the database by hand.

The check runs **before** the update, not after, and
`theLastAdminCheckPrecedesTheWrite` asserts the call order rather than the
final state. A rollback would make checking afterwards *nearly* equivalent,
and "nearly" over an audit table is what A-109's design refuses to rely on.

The counterweight test matters as much: `theProtectionIsNotBlanket` revokes the
second-to-last admin successfully. A guard written as "OB_ADMIN grants may
never be revoked" passes every other test in the file and is wrong.

## What enforces what

| Invariant | Enforced by | Backed by |
|---|---|---|
| one live grant per (user, module) | `uq_user_module_access_live` over the generated `live_key` | 409 in the service, for the *message* |
| revoked means somebody revoked it | `ck_user_module_access_revoked` | both columns written together |
| closed module and role vocabularies | `ck_user_module_access_module{,_role}` | 400 in the service, for the *message* |
| concurrent double revoke | `WHERE revoked_at IS NULL` on the UPDATE | 422 from the row count |

The pattern repeats: the database is the guarantee, and the service exists to
turn a constraint name into a sentence. Without the service check the caller
gets a 500 quoting a MySQL index — the failure A-125 had to correct on the
client master.

## Auditing

**Nothing in this package calls `AuditTrail.record`, and nothing should.**

A-071 derives the audit term from the route in `AuditInterceptor`, registered
across `/api/**`, so the grant and the revoke are logged the day they are
written. An explicit call would write a second row for the same act and start
the per-service pattern A-071's javadoc argues against — a log whose
completeness is as good as the last person who remembered.

The row-level audit — who granted, who revoked, and when — is the table's own
four columns. That is the part an access audit actually reads.

## Known gaps

- **`Idempotency-Key` is accepted and not honoured.** The 24-hour replay store
  does not exist. A retried grant answers 409 and a retried revoke answers 422;
  both are the wrong status for a replay, and neither corrupts anything.
- **The Admin check reads the claim, not the table**, so it is up to fifteen
  minutes stale like every other module-role decision. Stated on the wire as
  `tokenLagSeconds` rather than fixed by a per-request read, which is the
  trade A-111 made for the whole module.
