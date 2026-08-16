import * as React from 'react'

import { useListUsers } from '@/api/generated/users/users'
import type { User } from '@/api/generated/model/user'

/**
 * C-030 · who the `@` type-ahead may offer.
 *
 * ## Why this adds no endpoint
 *
 * `GET /users` already is this list, and the contract says so in as many words:
 * *"the ticket-assignee picker (S-18) and `@mention` resolution are the same
 * data"*. It takes `projectId` and `q`, and `ResourceRepository` resolves
 * `projectId` through `EXISTS (… project_members … pm.is_active = 1)` — the same
 * predicate `CommentMentions.resolveProjectMembers` applies on the write path.
 *
 * That matters more than saving a file. A picker offering somebody the server
 * would then refuse is the worst version of this feature: you choose a name, the
 * comment posts, and nobody is notified — silently, because an unresolved
 * mention is indistinguishable from plain text by design. Reading the same table
 * through the same filter is what keeps the two ends honest, and it means no
 * contract change, no client regeneration, and nothing for the other three
 * streams to absorb.
 *
 * ## The query is server-side, not a filter over a cached directory
 *
 * `q` goes to the server rather than fetching every member once and filtering in
 * the browser. A project can have a hundred members, and the contract's `q`
 * already searches name, username, email and employee code — reimplementing a
 * subset of that client-side is how the type-ahead and the directory start
 * disagreeing about what "ravi" matches.
 */

/** Enough of a member to render a row and insert a handle. */
export interface MentionCandidate {
  id: number
  /** `users.username` — what gets inserted, and what the server parses back. */
  handle: string
  displayName: string
  role?: string
}

export interface UseMentionCandidatesResult {
  candidates: MentionCandidate[]
  isLoading: boolean
}

/**
 * The list is capped rather than paged. A type-ahead is a way of narrowing by
 * typing, so the answer to "too many results" is another character, not a
 * scrollbar — and a listbox long enough to need one is one nobody reads to the
 * end of.
 */
const MAX_SUGGESTIONS = 8

export function useMentionCandidates({
  projectId,
  query,
  enabled,
}: {
  /** The ticket's project. Undefined disables the query — see below. */
  projectId: number | undefined
  /** What has been typed after the `@`, possibly empty. */
  query: string
  enabled: boolean
}): UseMentionCandidatesResult {
  // Without a project there is no membership to scope to, and an unscoped
  // /users call would offer the whole company — exactly the fan-out the server
  // refuses. Offering nothing is the honest failure: the handle stays plain
  // text, which is what it would have done anyway.
  const active = enabled && projectId != null

  const { data, isFetching } = useListUsers(
    {
      projectId,
      q: query.length > 0 ? query : undefined,
      isActive: true,
      limit: MAX_SUGGESTIONS,
    },
    {
      query: {
        enabled: active,
        // The query key already varies by `q`, so react-query serves the last
        // page for a prefix instantly. `placeholderData` is what stops the
        // listbox emptying and re-filling on every keystroke, which reads as
        // flicker and moves the highlighted row out from under the arrow keys.
        placeholderData: (previous) => previous,
        staleTime: 30_000,
        retry: false,
      },
    },
  )

  const candidates = React.useMemo(
    () => (data?.data ?? []).map(toCandidate).filter(hasHandle).slice(0, MAX_SUGGESTIONS),
    [data],
  )

  return { candidates, isLoading: active && isFetching }
}

function toCandidate(user: User): MentionCandidate {
  return {
    id: user.id,
    handle: user.username ?? '',
    // `displayName` is required on `UserRef`, so unlike the server's
    // `CommentUserRefs` there is no null to fall back from here.
    displayName: user.displayName,
    role: user.role,
  }
}

/**
 * A user with no `username` cannot be mentioned — the handle is what gets typed
 * and what the server parses back out of the body — so they are dropped rather
 * than rendered as an unselectable row. The field is optional in the contract
 * because `User` extends `UserRef`, not because the column is nullable.
 */
function hasHandle(candidate: MentionCandidate): boolean {
  return candidate.handle.length > 0
}
