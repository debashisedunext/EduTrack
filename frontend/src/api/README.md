# Using the generated API client

`generated/` is produced by [`orval`](../../orval.config.ts) from
[`contracts/openapi.yaml`](../../../contracts/openapi.yaml). **Never hand-edit
it** — CI regenerates and fails the build if the committed output is stale
(D-005).

```bash
npm run api:generate
```

Owned by Stream D (D-003). Everything else here is hand-written and small on
purpose.

| | |
|---|---|
| `http.ts` | The one fetch every generated call goes through |
| `generated/<tag>/` | Typed functions + TanStack Query hooks, one file per tag |
| `generated/model/` | Types for every schema |
| `generated/zod/` | Zod schemas for React Hook Form |

---

## Reading

```tsx
import { useGetTicketDetail } from '@/api/generated/tickets/tickets';

const { data, isLoading, error } = useGetTicketDetail('CRM-26-00347');
const ticket = data?.data.ticket;   //  ← two `data`s, and that is correct
```

**The double `data` is not a mistake.** The outer one is TanStack Query's; the
inner one is the API envelope. Every success response is `{ data, meta? }` so a
field can be added later without breaking clients (CONVENTIONS.md §2). The
mutator deliberately does *not* unwrap it — unwrapping would make the generated
types lie about what the server sends.

## Writing, and the idempotency rule

```tsx
import { useCreateTicket } from '@/api/generated/tickets/tickets';
import { newIdempotencyKey } from '@/api/http';

const { mutate } = useCreateTicket();

// Generate the key ONCE, outside the mutation.
const key = newIdempotencyKey();
mutate({ data: body, headers: { 'Idempotency-Key': key } });
```

**Generating the key inside the mutation function defeats the entire point.**
TanStack Query re-invokes that function on retry, so a key created per attempt
is different every time and the server sees each retry as a new request. The
specific failure this prevents — duplicate effort logs — **cannot be cleaned up
afterwards**, because effort logs are append-only. There is no delete.

## Errors

Every non-2xx throws `ApiError`, carrying the RFC 9457 problem document.

```tsx
import { ApiError } from '@/api/http';

if (error instanceof ApiError) {
  if (error.is('stage-owner-required')) { /* not your stage */ }
  form.setError('title', { message: error.fieldErrors.title?.[0] });
}
```

Branch on `problem.type`, which is a stable URI. **Never match on `title` or
`detail`** — they are written for humans and get reworded.

`404` means *not found or out of your scope*, deliberately indistinguishable. Do
not write a "you don't have permission" message for a 404; you cannot tell.

## Validation

```tsx
import { zodResolver } from '@hookform/resolvers/zod';
import { createTicketBody } from '@/api/generated/zod/tickets/tickets.zod';

const form = useForm({ resolver: zodResolver(createTicketBody) });
```

These carry the `minLength`, `pattern` and `required` that springdoc emits from
the Java DTOs' Bean Validation annotations. **Do not hand-write a validation
rule** — if the frontend needs a constraint the backend does not enforce, the
backend is missing it, and adding it in one place gets you both.

## Infinite scrolling

`useInfinite` is **off** in the orval config, on purpose. It generates an
infinite hook for every `GET` — including the twenty that are single resources
or deliberately unpaginated — and orval 7.21's output does not typecheck against
`@tanstack/react-query` 5.101 (`UseInfiniteQueryOptions` accepts at most five
generics; it passes six). That was 144 type errors for hooks nobody asked for.

The few lists that genuinely need it compose it directly, paging on
`meta.nextCursor`:

```tsx
import { useInfiniteQuery } from '@tanstack/react-query';
import { listTickets } from '@/api/generated/tickets/tickets';

useInfiniteQuery({
  queryKey: ['tickets', filters],
  queryFn: ({ pageParam }) => listTickets({ ...filters, cursor: pageParam }),
  initialPageParam: undefined as string | undefined,
  getNextPageParam: (last) => last.meta?.nextCursor ?? undefined,
});
```

Cursor, never offset. Offset paging over a table being written to skips and
repeats rows — insert a ticket while someone is on page 2 and one row shifts to
page 3 unseen. On a ticket list that is a ticket nobody works on.

## Auth

`http.ts` attaches the bearer token and sends `credentials: 'include'` so the
HttpOnly refresh cookie rides along. The auth store calls `setAccessToken()` on
login, refresh and logout.

Refresh-on-401 is **not** here yet — it belongs with the auth feature (A-024,
including reuse detection and family revocation), not in the transport.
