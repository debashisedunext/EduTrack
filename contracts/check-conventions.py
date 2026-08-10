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
    "/notifications/{notificationId}/read": "idempotent, a race is harmless",
    "/notifications/read-all":             "idempotent, a race is harmless",
    "/tickets/{ticketId}/priority":        "reason mandatory and every change logged, so concurrent changes are visible not lost",
    "/tickets/{ticketId}/comments/{commentId}": "author-only inside a 5-minute window",
    "/chat/threads/{threadId}/messages/{messageId}": "author-only inside a 5-minute window",
}

# §6 — bounded by a constraint the product already enforces.
NO_PAGINATION = {
    "/masters/task-types":               "11 rows",
    "/masters/priorities":               "4 rows",
    "/masters/workflow-templates":       "a handful per project",
    "/projects/{projectId}/sla-policies": "task types x 4 levels",
    "/clients/{clientId}/contacts":      "a short list per client",
    "/tickets/{ticketId}/attachments":   "capped at 20 per ticket",
    "/notifications/pending":            "a queue drained by acknowledging, not paged; a cursor would outlive the rows it points past",
}

# §8 — GET only. No mutation verb, ever.
APPEND_ONLY = ("/history", "/effort-logs", "/audit-logs")

# §7 — 403 is legitimate only where the failure does not depend on a row.
ROWLESS_403 = {"/audit-logs"}


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

    def resolved(ref):
        return schemas.get(ref.get("$ref", "").split("/")[-1], {})

    if "--list" in sys.argv:
        print("If-Match exemptions:")
        for k, v in NO_IF_MATCH.items():
            print("  %-46s %s" % (k, v))
        print("\nPagination exemptions:")
        for k, v in NO_PAGINATION.items():
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
