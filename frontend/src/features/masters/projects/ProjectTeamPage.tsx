import * as React from 'react'
import { useParams } from 'react-router-dom'
import { Plus, X } from 'lucide-react'

import { useListUsers } from '@/api/generated/users/users'
import type { ProjectMember } from '@/api/generated/model/projectMember'
import type { User } from '@/api/generated/model/user'
import { ApiError } from '@/api/http'

import { Chip } from '@/components/ui/chip'
import { EmptyState } from '@/components/ui/empty-state'
import { Input } from '@/components/ui/input'
import { SearchableDropdown } from '@/components/ui/searchable-dropdown'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import {
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { toast } from '@/components/ui/use-toast'

import { ProjectTabs } from './ProjectTabs'
import {
  allocationChangePatch,
  isRemovable,
  NO_PROJECT_ROLE,
  overridesGlobalRole,
  PROJECT_ROLE_OPTIONS,
  roleChangePatch,
  summariseAllocation,
} from './projectTeam'
import {
  useAddTeamMember,
  useProjectTeam,
  useRemoveTeamMember,
  useUpdateTeamMember,
} from './projectTeamQueries'

/**
 * S-10 Project Master — the Team tab. B-017.
 *
 * "Add resources + project role (PM / Dev / Support / QA / Deploy / Viewer) +
 * allocation %", which is one grid, three writes and two things that are easy
 * to get subtly wrong.
 *
 * <h2>Role and allocation edit in place, and save on change</h2>
 *
 * There is no Save button and no dirty state. Every cell is one `PATCH` of one
 * field, which is what makes the absent `If-Match` safe: two people editing
 * different members, or different fields of one member, both land. A form-shaped
 * Team tab would have to send the whole roster on every save, and then a stale
 * tab really could undo somebody's change.
 *
 * <h2>The removal refusal is shown before the click, not after it</h2>
 *
 * `openTicketCount` is on the roster, so a member holding open work has their
 * remove button disabled with the count in its label. B-014's lesson from the
 * resource grid: a refusal that arrives *after* the action reads as a failure of
 * the click rather than as a fact about the organisation. The server's guard
 * stays the authority — a count that goes stale while the tab is open comes back
 * as a 409, which is handled.
 *
 * <h2>What is deliberately not here</h2>
 *
 * - **A per-resource over-allocation warning.** This screen has one project's
 *   rows; whether somebody is committed past 100% across *their* projects is a
 *   question it cannot answer without reading every other project's team. The
 *   project total is shown because it is honest and free. The resource total
 *   belongs to B-061's capacity report — **flagged, not approximated**.
 * - **Bulk add.** S-10 does not ask for one, and the resource form's Projects
 *   section (B-011) is already the many-projects-one-person direction of the
 *   same table.
 */
export function ProjectTeamPage() {
  const params = useParams<{ projectId?: string }>()
  const projectId = params.projectId ? Number(params.projectId) : null

  const { data: members, isPending, isError } = useProjectTeam(projectId)
  const [bannerError, setBannerError] = React.useState<string | null>(null)

  if (projectId == null) {
    return <p className="p-6 text-sm text-danger-text">No project selected.</p>
  }

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6 p-6">
      <ProjectTabs active="Team" />

      {bannerError ? (
        <div
          role="alert"
          className="rounded-card border border-danger bg-surface px-4 py-3 text-sm text-danger-text"
        >
          {bannerError}
        </div>
      ) : null}

      {isPending ? (
        <Skeleton className="h-64 w-full" />
      ) : isError ? (
        <p className="text-sm text-danger-text">This project’s team could not be loaded.</p>
      ) : (
        <TeamGrid
          projectId={projectId}
          members={members}
          onError={setBannerError}
        />
      )}
    </div>
  )
}

function TeamGrid({
  projectId,
  members,
  onError,
}: {
  projectId: number
  members: ProjectMember[]
  onError: (message: string | null) => void
}) {
  const summary = summariseAllocation(members)

  return (
    <>
      <AddMember projectId={projectId} members={members} onError={onError} />

      {members.length === 0 ? (
        <EmptyState
          title="Nobody on this team yet"
          description="Add the resources who work on this project. Their project role can differ from their global one — a Developer can be mapped as QA here."
        />
      ) : (
        <>
          <TableContainer>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead scope="col">Resource</TableHead>
                  <TableHead scope="col">Project role</TableHead>
                  <TableHead scope="col">Allocation</TableHead>
                  <TableHead scope="col">Open tickets</TableHead>
                  <TableHead scope="col">
                    <span className="sr-only">Actions</span>
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {members.map((member) => (
                  <MemberRow
                    key={member.userId}
                    projectId={projectId}
                    member={member}
                    onError={onError}
                  />
                ))}
              </TableBody>
            </Table>
          </TableContainer>

          {/*
            Stated, not assumed. Folding the unstated ones in at 100 would make
            almost every real project read as wildly over-committed the day this
            shipped — every membership written before this screen has no
            allocation, because nothing had an input for one.
          */}
          <p className="text-sm text-content-muted">
            {summary.statedCount > 0
              ? `${summary.totalPct}% allocated across ${summary.statedCount} of ${members.length} members.`
              : 'No allocations stated yet.'}
            {summary.unstatedCount > 0
              ? ` ${summary.unstatedCount} ${summary.unstatedCount === 1 ? 'has' : 'have'} no allocation set.`
              : ''}
          </p>
        </>
      )}
    </>
  )
}

// ---------------------------------------------------------------------------
// add
// ---------------------------------------------------------------------------

function AddMember({
  projectId,
  members,
  onError,
}: {
  projectId: number
  members: ProjectMember[]
  onError: (message: string | null) => void
}) {
  // Active resources only. A deactivated one is refused server-side with a
  // message naming them, so offering them here would be offering a choice whose
  // only outcome is an error.
  const { data } = useListUsers({ isActive: true, limit: 200 })
  const onTeam = new Set(members.map((m) => m.userId))
  const available = React.useMemo(
    () => (data?.data ?? []).filter((u) => !onTeam.has(u.id)),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [data, members],
  )

  const addMember = useAddTeamMember(projectId)

  function add(user: User) {
    onError(null)
    addMember.mutate(
      // Role and allocation are deliberately not asked for here. "On the team"
      // is the fact being recorded; the other two are refinements, and a
      // three-field dialog to add one person is friction on the common case.
      // Both are editable in the row the moment it appears.
      { userId: user.id },
      {
        onSuccess: () => toast({ title: `${user.displayName} added to the team` }),
        onError: (error: ApiError) =>
          onError(
            error.fieldErrors.userId?.[0]
              ?? error.problem.detail
              ?? 'That resource could not be added.',
          ),
      },
    )
  }

  return (
    // Always visible rather than behind an "Add resource" button that reveals
    // it. The button was two clicks to add one person, and the second one was
    // the one that opened the picker — a disclosure step in front of a control
    // that is itself already a disclosure.
    <div className="flex max-w-md items-center gap-2">
      <Plus className="h-4 w-4 shrink-0 text-content-muted" aria-hidden />
      <span id="add-member-label" className="sr-only">
        Add a resource to the team
      </span>
      <SearchableDropdown
        options={[...available]}
        value={null}
        onChange={add}
        getKey={(u) => String(u.id)}
        getLabel={(u) => u.displayName}
        getSearchable={(u) => [u.email ?? '']}
        placeholder="Add a resource…"
        emptyText={
          available.length === 0 ? 'Every active resource is already on this team' : 'No results'
        }
        disabled={addMember.isPending}
        aria-labelledby="add-member-label"
      />
    </div>
  )
}

// ---------------------------------------------------------------------------
// one row
// ---------------------------------------------------------------------------

function MemberRow({
  projectId,
  member,
  onError,
}: {
  projectId: number
  member: ProjectMember
  onError: (message: string | null) => void
}) {
  const updateMember = useUpdateTeamMember(projectId)
  const removeMember = useRemoveTeamMember(projectId)

  // Local, so typing "4" on the way to "40" does not fire a request per
  // keystroke or fight the server's echo. Committed on blur.
  const [allocation, setAllocation] = React.useState(
    member.allocationPct == null ? '' : String(member.allocationPct),
  )
  React.useEffect(() => {
    setAllocation(member.allocationPct == null ? '' : String(member.allocationPct))
  }, [member.allocationPct])

  const removable = isRemovable(member)
  const openTickets = member.openTicketCount ?? 0

  function failed(error: ApiError, fallback: string) {
    onError(error.problem.detail ?? fallback)
  }

  function commitRole(next: string) {
    onError(null)
    updateMember.mutate(
      { userId: member.userId, patch: roleChangePatch(next) },
      { onError: (e: ApiError) => failed(e, 'That role could not be saved.') },
    )
  }

  function commitAllocation() {
    const patch = allocationChangePatch(allocation)
    if (patch == null) {
      // Out of range or not a whole number: say so and put the stored value
      // back, rather than sending something the server will refuse.
      onError('Allocation must be a whole number between 0 and 100.')
      setAllocation(member.allocationPct == null ? '' : String(member.allocationPct))
      return
    }
    if (patch.allocationPct === (member.allocationPct ?? null)) return

    onError(null)
    updateMember.mutate(
      { userId: member.userId, patch },
      { onError: (e: ApiError) => failed(e, 'That allocation could not be saved.') },
    )
  }

  function remove() {
    onError(null)
    removeMember.mutate(member.userId, {
      onSuccess: () => toast({ title: `${member.displayName} removed from the team` }),
      onError: (e: ApiError) =>
        // The pre-flight uses a count that can go stale while the tab is open;
        // the server's guard is the authority and this is where it lands.
        failed(e, `${member.displayName} could not be removed.`),
    })
  }

  return (
    <TableRow>
      <TableCell>
        <div className="flex flex-col">
          <span className="font-medium text-content">{member.displayName}</span>
          <span className="text-caption text-content-muted">
            {member.email ?? ''}
            {member.email ? ' · ' : ''}
            {member.role}
            {!member.isActive ? ' · deactivated' : ''}
          </span>
        </div>
      </TableCell>

      <TableCell>
        <div className="flex items-center gap-2">
          <Select
            value={member.projectRole ?? NO_PROJECT_ROLE}
            disabled={updateMember.isPending}
            onValueChange={commitRole}
          >
            <SelectTrigger className="w-44" aria-label={`Project role for ${member.displayName}`}>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {/*
                "Same as their global role" is the column's null and the common
                case. A required dropdown would make every membership restate
                the person's role, and the first time somebody's global role
                changed, every restatement would be a stale override nobody knew
                was there — B-011's reasoning, unchanged.
              */}
              <SelectItem value={NO_PROJECT_ROLE}>Same as global role</SelectItem>
              {PROJECT_ROLE_OPTIONS.map((option) => (
                <SelectItem key={option.value} value={option.value}>
                  {option.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>

          {/*
            The one thing this tab exists to make visible: a Developer mapped as
            QA here. A membership with no project role is not a difference and
            is not chipped, or the signal would drown.
          */}
          {overridesGlobalRole(member) ? (
            <Chip variant="info" title={`Globally a ${member.role}`}>
              override
            </Chip>
          ) : null}
        </div>
      </TableCell>

      <TableCell>
        <div className="flex items-center gap-1">
          <Input
            type="number"
            min={0}
            max={100}
            value={allocation}
            disabled={updateMember.isPending}
            onChange={(e) => setAllocation(e.target.value)}
            onBlur={commitAllocation}
            aria-label={`Allocation percent for ${member.displayName}`}
            // Empty is a real value here — "not stated" — and clearing the box
            // sends an explicit null rather than omitting the field.
            placeholder="—"
            className="w-20"
          />
          <span aria-hidden className="text-sm text-content-muted">%</span>
        </div>
      </TableCell>

      <TableCell>
        {openTickets > 0 ? (
          <Chip variant="warning">{openTickets}</Chip>
        ) : (
          <span className="text-sm text-content-muted">0</span>
        )}
      </TableCell>

      <TableCell>
        <button
          type="button"
          onClick={remove}
          disabled={!removable || removeMember.isPending}
          aria-label={
            removable
              ? `Remove ${member.displayName} from the team`
              : `${member.displayName} holds ${openTickets} open ticket${openTickets === 1 ? '' : 's'} on this project and cannot be removed`
          }
          title={
            removable
              ? undefined
              : `Reassign their ${openTickets} open ticket${openTickets === 1 ? '' : 's'} first.`
          }
          className="rounded-control p-1.5 text-content-muted hover:bg-subtle hover:text-content disabled:cursor-not-allowed disabled:opacity-40 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
        >
          <X className="h-4 w-4" aria-hidden />
        </button>
      </TableCell>
    </TableRow>
  )
}
