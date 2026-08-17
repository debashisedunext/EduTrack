#!/usr/bin/env python3
"""
Enforce CONVENTIONS.md against openapi.yaml.

Redocly checks that the document is valid OpenAPI. This checks that it is *our*
OpenAPI — the six conventions from blueprint §13 plus the two rules that protect
row scoping and the append-only tables.

    python3 contracts/check-conventions.py          # exits non-zero on violation
    python3 contracts/check-conventions.py --list   # show current exemptions

Every exemption below is deliberate and explained in CONVENTIONS.md. Adding one
means editing this file *and* that document, which is the point: an undocumented
exception gets "fixed" by the next person, and the exception was usually right.
"""
import os
import sys

try:
    import yaml
except ImportError:
    sys.exit("pip install pyyaml")

HERE = os.path.dirname(os.path.abspath(__file__))
SPEC = os.path.join(HERE, "openapi.yaml")
VERBS = ("get", "post", "put", "patch", "delete")

# §5 — idempotent setters, or a write already guarded another way.
NO_IF_MATCH = {
    "/me/password":                        "currentPassword already proves current state",
    "/users/{userId}/status":              "idempotent setter, last write wins is correct",
    "/clients/{clientId}/status":          "idempotent setter, last write wins is correct",
    "/clients/bulk-status":                "idempotent setter, like the single-client route it batches — and one If-Match cannot speak for 200 rows, while per-row tags would fail the whole batch because somebody touched one unrelated client",
    "/notifications/{notificationId}/read": "idempotent, a race is harmless",
    "/notifications/read-all":             "idempotent, a race is harmless",
    "/tickets/{ticketId}/priority":        "reason mandatory and every change logged, so concurrent changes are visible not lost",
    "/tickets/{ticketId}/comments/{commentId}": "author-only inside a 5-minute window",
    "/chat/threads/{threadId}/messages/{messageId}": "author-only inside a 5-minute window",
    "/me/notification-preferences":        "your own settings only, and partial by field so two tabs cannot overwrite each other's untouched switches",
    "/projects/{projectId}/members/{userId}": "two fields on one membership row, both last-write-wins by nature; the tag would have to come from a collection read with no ETag of its own, so the precondition would be machinery around a race whose loser typed a number later and meant it",
    "/clients/{clientId}/contacts/{contactId}": "B-027 — the tag would have to come from listClientContacts, a collection with no ETag of its own, exactly as for a project member. The client this contact hangs off does have one, and a contact write moves it (contactCount and hasPrimaryContact are inside it), so the S-33 form's own precondition still catches a stale editor one level up",
}

# §6 — bounded by a constraint the product already enforces.
NO_PAGINATION = {
    "/masters/task-types":               "11 rows",
    "/masters/priorities":               "4 rows",
    "/masters/modules":                  "8 rows, seeded; a ninth is a row somebody adds",
    "/masters/permissions":              "18 rows, and reference data — a nineteenth arrives by migration, not by a screen",
    "/masters/roles":                    "the six of blueprint §2 plus whatever an Admin adds; the S-09 matrix is unreadable long before it is unpageable",
    "/masters/workflow-templates":       "a handful per project",
    "/projects/{projectId}/sla-policies": "task types x 4 levels",
    "/clients/{clientId}/contacts":      "a short list per client",
    "/tickets/{ticketId}/attachments":   "capped at 20 per ticket",
    "/notifications/pending":            "a queue drained by acknowledging, not paged; a cursor would outlive the rows it points past",
    "/me/notification-preferences":      "one row per NotificationEvent — 25, and bounded by the enum",
    "/tickets/{ticketId}/status-requests": "open requests on one ticket, and at most one per manager who can ask about it",
    "/me/awaiting-response":             "your own unanswered asks; bounded server-side and ordered longest-wait-first, which a cursor would fight",
    "/chat/ticket-cards":                "bounded by the caller's own `codes` list, and capped below that; paging a lookup you supplied the keys for is not a page",
    "/projects/{projectId}/members":     "one project's team — tens of people, and the S-10 tab totals their allocations, so it needs the whole set on every read anyway",
    "/imports/{schema}/mapping-presets": "B-033 — one row per column mapping an Admin chose to name and reuse. Bounded by the only thing that creates them, which is a person typing a name on S-34 step 3: an organisation has one or two exports it imports from, not a hundred. The step-3 control is a picker, so it needs every preset on the one read anyway",
    "/reports":                          "the eighteen of §7.8 — a catalogue of what the deployment can run, not a query over rows. It is bounded by the code that declares it, so a nineteenth arrives by a release rather than by use, and the hub groups the whole set by category on one screen. Listed here rather than left to pass silently on `data` being an object: the exemption is the decision, and it should be reviewable if the shape ever changes",
    "/masters/notification-templates":   "at most one row per NotificationEvent per channel — 27 x 3, so bounded by two enums the way /me/notification-preferences is bounded by one; the S-15 grid groups by event and needs every channel of a group together, which a cursor cutting mid-event would break",
}

# §8 — GET only. No mutation verb, ever.
APPEND_ONLY = ("/history", "/effort-logs", "/audit-logs")

# §7 — 403 is legitimate only where the failure does not depend on a row.
#
# Held as a dict rather than a set since B-015, because the violation message
# already says "add it to ROWLESS_403 with a reason" and there was nowhere to
# put one. Membership tests read the same either way.
ROWLESS_403 = {
    "/audit-logs":                        "Admin-only regardless of what is in it",
    "/masters/roles":                     "Admin-only screen; there is no row yet on a create",
    "/masters/roles/{roleId}":            "master data, not row-scoped — every role is already public through listRoles, so a 403 leaks nothing a 404 would hide",
    "/masters/roles/{roleId}/permissions": "same: the role's existence is public, only the write is Admin-only",
    "/masters/priorities":                 "master data, not row-scoped — every active level is already public through listPriorities, and there is no row yet on a create",
    "/masters/priorities/{priorityId}":    "same: the level's existence is public, only the write is Admin-only",
    "/masters/task-types":                 "master data, not row-scoped — every task type is already public through listTaskTypes, and there is no row yet on a create",
    "/masters/task-types/{taskTypeId}":    "same: the type's existence is public, only the write is Admin-only",
    "/masters/notification-templates":     "Admin-only screen, reads included — master data, not row-scoped, so there is no row whose existence a 404 could protect",
    "/masters/notification-templates/vocabulary": "same: a catalogue of enum values, Admin-only because the screen it feeds is",
    "/masters/notification-templates/{templateId}": "same: not row-scoped. Every read here is Admin-only, so a 403 tells a non-Admin only what the 401 already did",
    "/attachments/limits":                 "org-wide settings, not a row — `master.write` is a capability the caller either holds or does not, and the resource has no existence a 404 could conceal (every role already reads it)",
    "/clients/bulk-status":                "master data, not row-scoped — the ids in the body name rows listClients already returned, and §4B.2's ticket dropdown makes that readable by all six roles, so a 403 conceals nothing a 404 would have",
    "/clients":                            "master data, not row-scoped — every client is already public through listClients, which §4B.2's ticket dropdown makes readable by all six roles, and there is no row yet on a create",
    "/clients/{clientId}":                 "same: the client's existence is public through the same list, so only the write is Admin-only and a 403 on it conceals nothing a 404 would have",
    "/clients/{clientId}/contacts":         "B-027 — master data, not row-scoped. Every contact of every client is already public through listClientContacts, which §4B.2's reporter dropdown makes readable by all six roles, and there is no row yet on a create",
    "/clients/{clientId}/contacts/{contactId}": "same: the contact's existence is public through that list, so only the write is Admin-only. A contact under a *different* client is still 404 — that is the nesting, not the capability",
    "/imports/{schema}/upload":            "B-032 — an upload is not a row either, and at this point it is not even the caller's data in our tables: the file is theirs, staged and unwritten. `master.write` is a capability the caller holds or does not, decided before the multipart body is looked at, so the refusal cannot depend on anything a 404 would be concealing",
    "/imports/{schema}/fields":            "B-033 — a schema's column list is not a row at all: it is the shape of a Java class, the same declarations the template route already hands out as a workbook and the upload route already reads to auto-match. Nothing about the organisation is in it, so there is no existence a 404 could protect, and the refusal is about the screen for the reason recorded on the template route",
    "/imports/{schema}/mapping-presets":   "B-033 — org-wide master data, and Admin-only on every verb including the read. So a 403 tells a non-Admin exactly what the 401 already did: nobody without master.write can learn that any preset exists, whichever status we answer. The same argument as /masters/notification-templates",
    "/imports/{schema}/mapping-presets/{presetId}": "same, and worth being explicit that the row here does exist. The refusal still does not depend on it — master.write is decided before the id is looked up, so a Developer cannot distinguish a real preset id from an invented one either way",
    "/imports/{schema}/commit":            "B-035 — the one route on this path that genuinely is a bulk write to the client master, which makes master.write the capability by any reading. Rowless for the same reason the validate route above is: the refusal is decided before the uploadId in the body is resolved, so it cannot depend on anything a 404 would be concealing, and the batch row it would create does not exist yet",
    "/import-batches/{batchId}":           "B-035 — the row here does exist, and the refusal still does not depend on it: master.write is decided before the id is looked up, so a Developer cannot distinguish a real batch id from an invented one either way. The same argument as /imports/{schema}/mapping-presets/{presetId}, and an import batch is org-wide master data rather than a scoped row — there is no assignee, project or client on it for ScopeResolver to answer about",
    "/imports/{schema}/validate":          "B-034 — the dry run reads a file the caller staged themselves and writes nothing, so there is no row on either side of it for a 404 to protect. `master.write` is decided before the uploadId in the body is looked up, which is also why a 403 here cannot be confused with the 422 an expired upload answers: one is about the caller, the other about their own staging entry",
    "/imports/{schema}/template":          "B-031 — a blank template is not a row. It carries no organisation data at all: column headings, the enum values already public through the client list's own filters, and a fictional example row. There is nothing whose existence a 404 could protect, and the refusal is about the screen (S-34 is inside §7.4's Admin-only master data module) rather than about anything in the file",
    # C-028, and the only entry here that IS row-scoped — worth reading before
    # it is used as a precedent.
    #
    # Every other line above says "not a row, so there is no existence to
    # protect". This one concedes the row exists and refuses the verb anyway,
    # because by the time §4B.4's deletion rule is consulted the caller has
    # already passed ScopedTickets AND the attachment has been confirmed to be on
    # that ticket. They are looking at it in a listing they just fetched, with its
    # file name and its size. The 404 would be protecting an existence the
    # previous request disclosed.
    #
    # It would also be actively worse: the client would have to render "that
    # attachment no longer exists" for a file still on screen, and the user's next
    # move — asking a PM to remove it — is one the true answer suggests and the
    # false one hides.
    #
    # The line this preserves: 404 conceals whether a row exists, 403 concedes
    # that it does and refuses the verb. An out-of-scope ticket is still 404 here,
    # thrown before this refusal is reachable.
    "/tickets/{ticketId}/attachments/{attachmentId}": "row-scoped, but scope has already answered — a 404 here would deny a file the caller can see in the listing they just fetched; §4B.4's rule is about who may remove it, not whether it exists",
    # C-033, and now the SECOND row-scoped entry. Read the C-028 block above
    # first; the argument is identical and is not restated here.
    #
    # One difference worth recording, because it is why this feature has a 403 on
    # one verb and not the other. `deleteComment` is refused purely by who is
    # asking — there is no window on a comment deletion at all — which is what 403
    # means. `editComment` is refused by the state of the row: the five minutes
    # have run out, and the caller was entitled to it four minutes ago. That is a
    # 422, it is what the contract has said since D-001, and it needs no exemption
    # here.
    "/tickets/{ticketId}/comments/{commentId}": "row-scoped, but scope has already answered — the caller is looking at the comment in a thread they just fetched, with its author and its text; §4B.5's rule is about who may remove it, not whether it exists. The DELETE is the 403; the PATCH answers 422 because what refuses it is the row's age rather than the caller",
}


def main():
    spec = yaml.safe_load(open(SPEC, encoding="utf-8"))
    schemas = spec["components"]["schemas"]
    paths = spec["paths"]
    fail = []

    def ops():
        for p, item in paths.items():
            for m, o in item.items():
                if m in VERBS:
                    yield p, m, o

    def params(p, o):
        return str(o.get("parameters", [])) + str(paths[p].get("parameters", []))

    def body_schema(o, code):
        c = o.get("responses", {}).get(code, {}).get("content", {})
        return c.get("application/json", {}).get("schema", {})

    def resolved(schema):
        """
        Follow a `$ref`, or hand back an inline schema unchanged.

        The second half was missing until A-029, and its absence was a **false
        positive that failed somebody else's build**. Every operation until then
        answered with a named component, so this was only ever handed a `$ref`;
        the first inline 2xx body resolved to `{}`, reported no `data` property,
        and the §2 envelope rule fired on a response that was correctly wrapped.
        Its author then went looking for a defect that was never there.

        A checker that is wrong in the strict direction is worse than no
        checker: it spends other people's time and teaches them to distrust it.
        """
        ref = schema.get("$ref") if isinstance(schema, dict) else None
        if not ref:
            return schema if isinstance(schema, dict) else {}
        return schemas.get(ref.split("/")[-1], {})

    if "--list" in sys.argv:
        print("If-Match exemptions:")
        for k, v in NO_IF_MATCH.items():
            print("  %-46s %s" % (k, v))
        print("\nPagination exemptions:")
        for k, v in NO_PAGINATION.items():
            print("  %-46s %s" % (k, v))
        print("\nRowless 403 exemptions:")
        for k, v in ROWLESS_403.items():
            print("  %-46s %s" % (k, v))
        return 0

    for p, m, o in ops():
        opid = o.get("operationId", "%s %s" % (m.upper(), p))

        # §2 — every 2xx JSON body wrapped in { data }
        for code, r in o.get("responses", {}).items():
            if not str(code).startswith("2"):
                continue
            sch = (r.get("content") or {}).get("application/json", {}).get("schema")
            if sch and "data" not in (resolved(sch).get("properties") or {}):
                fail.append("%s: 2xx body is not wrapped in { data }" % opid)

        # §3 — errors are problem+json
        for code, r in o.get("responses", {}).items():
            if str(code)[0] in "45":
                types = set((r.get("content") or {}).keys())
                if types and types != {"application/problem+json"}:
                    fail.append("%s: %s uses %s, not application/problem+json"
                                % (opid, code, sorted(types)))

        # §4 — creates take Idempotency-Key
        if m == "post" and "201" in o.get("responses", {}) \
                and "IdempotencyKey" not in params(p, o):
            fail.append("%s: create without Idempotency-Key" % opid)

        # §5 — risky writes take If-Match and can answer 412
        if m in ("patch", "put") and p not in NO_IF_MATCH:
            if "IfMatch" not in params(p, o):
                fail.append("%s: write without If-Match (add an exemption with a reason "
                            "if a lost update genuinely does not matter here)" % opid)
            elif "412" not in o.get("responses", {}):
                fail.append("%s: takes If-Match but never answers 412" % opid)

        # §5 — detail reads carry ETag
        if m == "get" and (p.rstrip("/").endswith("}") or p.endswith("/full")):
            if "ETag" not in str(o.get("responses", {}).get("200", {})):
                fail.append("%s: detail read without ETag" % opid)

        # §6 — collections paginate
        if m == "get" and p not in NO_PAGINATION:
            data = (resolved(body_schema(o, "200")).get("properties") or {}).get("data", {})
            if data.get("type") == "array":
                if "Cursor" not in params(p, o):
                    fail.append("%s: collection without cursor pagination" % opid)
                if "meta" not in (resolved(body_schema(o, "200")).get("properties") or {}):
                    fail.append("%s: paginated collection without meta" % opid)

        # §7 — 403 only where the failure does not depend on a row
        if "403" in o.get("responses", {}) and p not in ROWLESS_403:
            fail.append("%s: declares 403 — out-of-scope rows must return 404, "
                        "or add it to ROWLESS_403 with a reason" % opid)

        # codegen names every client function from this
        if not o.get("operationId"):
            fail.append("%s %s: no operationId" % (m.upper(), p))

    # §8 — append-only paths expose no mutation verb
    for p, item in paths.items():
        if any(a in p for a in APPEND_ONLY):
            bad = sorted(set(item) & {"put", "patch", "delete", "post"} - {"post"} )
            if p.endswith("/history") or p.endswith("/effort-logs") or p.endswith("/audit-logs"):
                bad = sorted(set(item) & {"put", "patch", "delete", "post"})
            if bad:
                fail.append("%s: %s on an append-only path. A correction is a new "
                            "compensating entry, not a mutation." % (p, bad))

    ops_count = sum(1 for _ in ops())
    if fail:
        print("CONVENTIONS: %d violation(s) across %d operations\n" % (len(fail), ops_count))
        for f in fail:
            print("  ✗ " + f)
        print("\nSee contracts/CONVENTIONS.md")
        return 1
    print("CONVENTIONS: %d operations, %d paths — all conform." % (ops_count, len(paths)))
    print("  exemptions: %d If-Match, %d pagination (see --list)"
          % (len(NO_IF_MATCH), len(NO_PAGINATION)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
