import { z } from 'zod'
import {
  createTicketBodyDescriptionMax,
  createTicketBodyTitleMax,
  createTicketBodyTitleMin,
} from '@/api/generated/zod/tickets/tickets.zod'
import { Level } from '@/api/generated/model/level'
import type { TaskType } from '@/api/generated/model/taskType'
import type { TicketCreateRequest } from '@/api/generated/model/ticketCreateRequest'

/**
 * S-19 Create Ticket — form state, validation and the mapping onto the wire.
 *
 * The length bounds are imported from the generated Zod rather than retyped.
 * They came out of `contracts/openapi.yaml` and will come out of the Java Bean
 * Validation annotations once springdoc emits the spec (PLAN.md §2.2, D-4), so
 * a copy here would be a second source of truth that drifts the first time a
 * bound changes on the backend. `ticketForm.test.ts` also parses the mapper's
 * output against the generated `createTicketBody`, which is what stops the two
 * from diverging silently.
 *
 * Blueprint §7.5 says the title is 200 characters; the contract says 300. The
 * contract's is the one the server enforces, so refusing at 200 would reject
 * input the API accepts. Flagged for Stream D in this folder's README.
 */

/**
 * Task types that cannot be raised without a client — blueprint §4B.2:
 * "Client Request, Client Bug and Production Bug require a client; Internal
 * Bug does not."
 *
 * §4B.2 calls the rule *configurable per task type*, but `TaskType` carries no
 * `requiresClient` flag in the contract yet, so this matches on the master's
 * name. Move it onto the flag the day Stream D adds one — matching a display
 * string means a rename in the Task Type master silently disables the rule.
 */
export const CLIENT_REQUIRING_TASK_TYPES: readonly string[] = [
  'Client Request',
  'Client Bug',
  'Production Bug',
]

export function clientRequiringTaskTypeIds(taskTypes: readonly TaskType[]): ReadonlySet<number> {
  return new Set(
    taskTypes
      .filter((t) => t.name != null && CLIENT_REQUIRING_TASK_TYPES.includes(t.name))
      .map((t) => t.id)
      .filter((id): id is number => id != null),
  )
}

/**
 * What React Hook Form holds.
 *
 * Every "not chosen yet" is `null` rather than `undefined` so the controls stay
 * controlled from first render, and the two numeric fields are strings because
 * that is what an `<input>` gives back — an empty numeric input yielding `NaN`
 * is the classic way a required-field error turns into "expected number".
 */
export interface TicketFormValues {
  projectId: number | null
  title: string
  description: string
  taskTypeId: number | null
  level: Level | null
  clientId: number | null
  clientContactId: number | null
  isClientRaised: boolean
  assigneeId: number | null
  watcherIds: number[]
  estimatedHrs: string
  /** `datetime-local` value, or '' to let the server compute it from the SLA policy. */
  plannedCloseDate: string
}

export const emptyTicketForm: TicketFormValues = {
  projectId: null,
  title: '',
  description: '',
  taskTypeId: null,
  level: null,
  clientId: null,
  clientContactId: null,
  isClientRaised: false,
  assigneeId: null,
  watcherIds: [],
  estimatedHrs: '',
  plannedCloseDate: '',
}

const requiredId = (message: string) =>
  z
    .number()
    .int()
    .positive()
    .nullable()
    .refine((v) => v !== null, { message })

const optionalId = z.number().int().positive().nullable()

// Hours as the user types them: "4", "4.5", "0.25". Rejecting "4,5" and "4h"
// here is friendlier than letting Number() turn them into NaN downstream.
const HOURS = /^\d+(\.\d{1,2})?$/

/**
 * Built per render from the loaded task-type master, because the client rule
 * depends on which task type was picked and that mapping only exists at
 * runtime. Cheap — it is four `z.*` calls, not a network round trip.
 */
export function ticketFormSchema(clientRequiredTaskTypeIds: ReadonlySet<number>) {
  return z
    .object({
      projectId: requiredId('Select the project this ticket belongs to'),
      title: z
        .string()
        .trim()
        .min(createTicketBodyTitleMin, `Give the ticket a title of at least ${createTicketBodyTitleMin} characters`)
        .max(createTicketBodyTitleMax, `Keep the title under ${createTicketBodyTitleMax} characters`),
      // Optional on the wire, mandatory here: blueprint §7.5 marks Task
      // Description with an asterisk, and the blueprint wins on behaviour.
      description: z
        .string()
        .trim()
        .min(1, 'Describe the task — this is what the assignee reads first')
        .max(createTicketBodyDescriptionMax, `Keep the description under ${createTicketBodyDescriptionMax} characters`),
      taskTypeId: requiredId('Select a task type'),
      level: z
        .nativeEnum(Level)
        .nullable()
        .refine((v) => v !== null, { message: 'Select a priority level' }),
      clientId: optionalId,
      clientContactId: optionalId,
      isClientRaised: z.boolean(),
      assigneeId: optionalId,
      watcherIds: z.array(z.number().int().positive()),
      estimatedHrs: z
        .string()
        .trim()
        .min(1, 'Estimated effort is required')
        .refine((s) => HOURS.test(s), 'Enter hours as a decimal — 4, 4.5 or 0.25')
        .refine((s) => Number(s) > 0, 'Estimated effort must be greater than zero'),
      plannedCloseDate: z
        .string()
        .refine((s) => s === '' || !Number.isNaN(Date.parse(s)), 'Enter a valid date and time'),
    })
    .superRefine((values, ctx) => {
      if (values.taskTypeId != null && clientRequiredTaskTypeIds.has(values.taskTypeId) && values.clientId == null) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          path: ['clientId'],
          message: 'This task type is client-facing — pick the client it was raised for',
        })
      }
      // A contact without its client is a dangling foreign key. The UI clears
      // the contact when the client changes, so this only fires if that ever
      // stops being true.
      if (values.clientContactId != null && values.clientId == null) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          path: ['clientContactId'],
          message: 'Select the client before its contact',
        })
      }
    })
}

/**
 * Form state → `POST /tickets` body.
 *
 * Only ever called with values that passed the schema; the guard states that
 * invariant rather than papering over it with non-null assertions, so a future
 * caller that skips validation fails loudly instead of sending `null` as an id.
 */
export function toCreateRequest(values: TicketFormValues): TicketCreateRequest {
  const { projectId, taskTypeId, level } = values
  if (projectId == null || taskTypeId == null || level == null) {
    throw new Error('toCreateRequest received values that never passed validation')
  }

  return {
    projectId,
    title: values.title.trim(),
    description: values.description.trim(),
    taskTypeId,
    level,
    clientId: values.clientId,
    clientContactId: values.clientContactId,
    isClientRaised: values.isClientRaised,
    assigneeId: values.assigneeId,
    watcherIds: values.watcherIds,
    estimatedHrs: Number(values.estimatedHrs),
    // Omitted entirely when blank. That is the contract's signal to compute it
    // from the SLA policy against the working calendar — sending `null` would
    // instead be an explicit "no planned close date", which is a different
    // thing and would leave the ticket outside every delay calculation.
    ...(values.plannedCloseDate
      ? { plannedCloseDate: new Date(values.plannedCloseDate).toISOString() }
      : {}),
    // Save as Draft, Save & Assign and Save & Create Another are C-013.
    saveAsDraft: false,
  }
}
