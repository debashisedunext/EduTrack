import { Link } from 'react-router-dom'

import { Chip } from '@/components/ui/chip'
import type { User } from '@/api/generated/model/user'
import { ROLE_LABEL } from '@/lib/roleLabel'

/**
 * B-010 · the S-07 columns, in blueprint §7.4's order.
 *
 * Emp Code · Name · Email · Role · Department · Reporting Manager · Projects ·
 * Status · Last login · Actions. Selection is a separate leading column the
 * page owns, because it is a control rather than data.
 */
export interface ResourceColumn {
  key: string
  header: string
  widthClassName?: string
  align?: 'left' | 'right'
  render: (resource: User) => React.ReactNode
}

/**
 * Renders a stored UTC instant in the viewer's own zone.
 *
 * PLAN.md §3.1: storage is UTC everywhere and the timezone is applied in the
 * presentation layer. This is that layer. The export is the deliberate
 * exception — its column is labelled "(UTC)" because a spreadsheet leaving the
 * building has no viewer whose zone is knowable.
 */
export function formatLastLogin(value: string | null | undefined): string {
  if (!value) return 'Never'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return '—'
  return parsed.toLocaleString(undefined, {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

/** A dash rather than an empty cell, so a blank column reads as "not set". */
function orDash(value: string | null | undefined) {
  return value == null || value === '' ? '—' : value
}

export const RESOURCE_COLUMNS: ResourceColumn[] = [
  {
    key: 'employeeCode',
    header: 'Emp Code',
    widthClassName: 'w-28',
    render: (r) => <span className="font-mono text-caption text-content-muted">{orDash(r.employeeCode)}</span>,
  },
  {
    key: 'name',
    header: 'Name',
    widthClassName: 'min-w-[12rem]',
    render: (r) => (
      <div className="flex flex-col">
        <span className="font-medium text-content">{r.displayName}</span>
        {r.designation && <span className="text-caption text-content-muted">{r.designation}</span>}
      </div>
    ),
  },
  {
    key: 'email',
    header: 'Email',
    widthClassName: 'min-w-[14rem]',
    render: (r) => (
      <a href={`mailto:${r.email}`} className="text-primary hover:underline">
        {r.email}
      </a>
    ),
  },
  {
    key: 'role',
    header: 'Role',
    widthClassName: 'w-32',
    render: (r) => <Chip variant="info">{r.role ? ROLE_LABEL[r.role] : '—'}</Chip>,
  },
  {
    key: 'department',
    header: 'Department',
    widthClassName: 'w-36',
    render: (r) => <span className="text-content">{orDash(r.department)}</span>,
  },
  {
    key: 'reportingManager',
    header: 'Reporting Manager',
    widthClassName: 'w-40',
    render: (r) => <span className="text-content">{orDash(r.reportingManager?.displayName)}</span>,
  },
  {
    key: 'projects',
    header: 'Projects',
    widthClassName: 'min-w-[12rem]',
    // Codes, not full names: three project names per row is a column nobody can
    // scan. The full name is on the title, which is where somebody who does not
    // recognise a code will look.
    render: (r) =>
      r.projects && r.projects.length > 0 ? (
        <div className="flex flex-wrap gap-1">
          {r.projects.map((p) => (
            <Chip key={p.id} variant="neutral" title={p.name}>
              {p.projectCode}
            </Chip>
          ))}
        </div>
      ) : (
        <span className="text-content-muted">—</span>
      ),
  },
  {
    key: 'status',
    header: 'Status',
    widthClassName: 'w-28',
    render: (r) =>
      r.isActive ? <Chip variant="success">Active</Chip> : <Chip variant="neutral">Inactive</Chip>,
  },
  {
    key: 'lastLoginAt',
    header: 'Last login',
    widthClassName: 'w-44',
    render: (r) => (
      <span className={r.lastLoginAt ? 'text-content' : 'text-content-muted'}>
        {formatLastLogin(r.lastLoginAt)}
      </span>
    ),
  },
  {
    // B-011. Declared in §7.4's column list and empty until now, because B-010
    // had no screen to send anybody to.
    key: 'actions',
    header: 'Actions',
    widthClassName: 'w-20',
    render: (r) => (
      // A real link, not a button with an onClick. Middle-click, Cmd-click and
      // "copy link address" all work, and an admin working through a list of
      // people to correct opens them in tabs.
      <Link
        to={`/masters/resources/${r.id}/edit`}
        className="rounded-control px-2 py-1 text-sm text-primary hover:bg-subtle hover:underline focus-visible:outline-none focus-visible:ring-2"
        aria-label={`Edit ${r.displayName}`}
      >
        Edit
      </Link>
    ),
  },
]
