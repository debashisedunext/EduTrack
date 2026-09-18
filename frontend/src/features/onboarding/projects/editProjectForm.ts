import type { ObProjectDetail } from '@/api/generated/model/obProjectDetail'
import type { ObProjectUpdateRequest } from '@/api/generated/model/obProjectUpdateRequest'

/**
 * The Edit project dialog's values and rules, kept pure and tested apart from
 * the dialog that draws them.
 *
 * <h2>What can be edited, and what cannot</h2>
 *
 * <p>Name, start date, the three people, and the status with its reason. Not the
 * client or the product — the contract is explicit that "the identity is the
 * (client, product) pair", so changing either is a different project. Not the
 * module services either: each one is an instantiated journey with its own
 * history, and the `PATCH` has no field for them by design.
 *
 * <h2>The request is the whole representation</h2>
 *
 * <p>`ObProjectUpdateRequest`'s own rule: all three people are always sent, so an
 * absent `implementorUserId` means <em>cleared</em>. This builder therefore
 * sends `null` rather than leaving a key out, which is the difference between
 * unassigning somebody on purpose and the server reading a gap.
 *
 * <h2>Completed is earned, not set</h2>
 *
 * <p>The server refuses `status: COMPLETED` with a 422, so it is never offered.
 * And a project that is already complete is not sent a status at all — the
 * field is optional, and echoing `COMPLETED` back would be refused too, which
 * would make its name un-editable for no reason anybody could see.
 */

/** The three a person may set. `COMPLETED` is the server's to award. */
export const SETTABLE_STATUSES = ['RUNNING', 'ON_HOLD', 'DROPPED'] as const
export type SettableStatus = (typeof SETTABLE_STATUSES)[number]

export const STATUS_LABEL: Record<SettableStatus, string> = {
  RUNNING: 'Running',
  ON_HOLD: 'On hold',
  DROPPED: 'Dropped',
}

export interface ProjectEditValues {
  name: string
  /** `YYYY-MM-DD`, as the date input holds it. */
  startDate: string
  salesPersonId: number | null
  implementorUserId: number | null
  implementorManagerUserId: number | null
  /** Null for a completed project, where status is not on offer. */
  status: SettableStatus | null
  statusReason: string
}

export type ProjectEditErrors = Partial<Record<keyof ProjectEditValues, string>>

function settable(status: string): SettableStatus | null {
  return (SETTABLE_STATUSES as readonly string[]).includes(status) ? (status as SettableStatus) : null
}

/** The dialog's starting point — what the project says today. */
export function projectEditValuesOf(project: ObProjectDetail): ProjectEditValues {
  return {
    name: project.name,
    startDate: project.startDate,
    salesPersonId: project.salesPerson?.id ?? null,
    implementorUserId: project.implementor?.id ?? null,
    implementorManagerUserId: project.implementorManager?.id ?? null,
    status: settable(project.status),
    statusReason: project.statusReason ?? '',
  }
}

/** Whether the chosen status has to say why. */
export function statusNeedsReason(status: SettableStatus | null): boolean {
  return status === 'ON_HOLD' || status === 'DROPPED'
}

export function validateProjectEdit(values: ProjectEditValues): ProjectEditErrors {
  const found: ProjectEditErrors = {}
  if (!values.name.trim()) found.name = 'Give the project a name.'
  if (!values.startDate) found.startDate = 'Give the project a start date.'
  /*
    The server insists on a reason for On hold and Dropped
    (`ObProjectStatus.requiresReason`), and asking here saves a round trip to
    learn it. A reason on a running project is simply not sent.
  */
  if (statusNeedsReason(values.status) && !values.statusReason.trim()) {
    found.statusReason = `Say why the project is ${values.status === 'ON_HOLD' ? 'on hold' : 'dropped'}.`
  }
  return found
}

export function projectEditRequestOf(values: ProjectEditValues): ObProjectUpdateRequest {
  const request: ObProjectUpdateRequest = {
    name: values.name.trim(),
    startDate: values.startDate,
    salesPersonId: values.salesPersonId,
    implementorUserId: values.implementorUserId,
    implementorManagerUserId: values.implementorManagerUserId,
  }
  if (values.status != null) {
    request.status = values.status
    request.statusReason = statusNeedsReason(values.status) ? values.statusReason.trim() : null
  }
  return request
}
