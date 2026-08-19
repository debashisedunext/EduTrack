import { z } from 'zod'
import {
  createTicketBodyDescriptionMax,
  createTicketBodyFeatureMax,
  createTicketBodyScreenNameMax,
  createTicketBodyStepsToGenerateMax,
  createTicketBodyTitleMax,
  createTicketBodyTitleMin,
} from '@/api/generated/zod/tickets/tickets.zod'
import { Level } from '@/api/generated/model/level'
import type { TaskType } from '@/api/generated/model/taskType'
import type { TicketCreateRequest } from '@/api/generated/model/ticketCreateRequest'
import { isRichTextEmpty, sanitizeRichText } from '@/components/ui/rich-text'

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
 * C-068 · which task types make Module mandatory — blueprint §7.5:
 * "**Mandatory for bug-type task types**, optional for change requests and
 * internal work."
 *
 * **Matched on `code`, not on `name`, and that is the one difference from
 * `CLIENT_REQUIRING_TASK_TYPES` above.** That rule matches display strings and
 * says so apologetically — "a rename in the Task Type master silently disables
 * the rule". `TaskType.code` is documented in the contract as *immutable once
 * created* and is what both the migration seed and `db.ts` key on, so this
 * rule survives an admin renaming "Production Bug" to "Prod Bug" in S-13.
 *
 * The three codes are listed rather than derived from a `_BUG` suffix. A suffix
 * test would silently capture whatever a future admin happens to call a row,
 * and this is a validation rule — it should change when somebody decides it
 * changes, not when somebody picks a code.
 *
 * **Server Issue, Network Issue, Browser Issue and Performance Issue are
 * deliberately out.** §7.5's argument for the rule is routing ("a Production Bug
 * without a module is a bug nobody can route") and its argument against
 * over-applying it is sharper: forcing a choice where the honest answer is "all
 * of them" "teaches people to pick the first item in the list, which poisons the
 * very reporting the field exists for". A server or network issue is usually not
 * *in* a module at all. Under-applying costs a blank column on a ticket that
 * could have had one; over-applying costs made-up data in the one field the
 * whole feature was requested for. The cheaper mistake is the one to make.
 */
export const BUG_TASK_TYPE_CODES: readonly string[] = ['PRODUCTION_BUG', 'CLIENT_BUG', 'INTERNAL_BUG']

export function bugTaskTypeIds(taskTypes: readonly TaskType[]): ReadonlySet<number> {
  return new Set(
    taskTypes
      .filter((t) => t.code != null && BUG_TASK_TYPE_CODES.includes(t.code))
      .map((t) => t.id)
      .filter((id): id is number => id != null),
  )
}

/** The two task-type-derived rules the schema needs, named so they cannot be transposed. */
export interface TaskTypeRules {
  /** §4B.2 — task types that cannot be raised without a client. */
  clientRequired: ReadonlySet<number>
  /** §7.5 — task types that cannot be raised without a module. */
  bugTypes: ReadonlySet<number>
}

/**
 * The three save actions blueprint §7.5 asks for — C-013.
 *
 * `assign` is the primary and `another` differs from it only in what happens
 * *after* the save, so the two validate identically. `draft` is the one that
 * changes what the form will accept.
 */
export type TicketSaveAction = 'assign' | 'draft' | 'another'

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
  /**
   * §7.5's "Where it happened" group — C-068. All four are nullable columns
   * (§7.5: "Tickets raised before these fields existed have no honest value"),
   * so mandatoriness is a rule of *this form* and of nothing below it.
   */
  moduleId: number | null
  screenName: string
  feature: string
  /** Rich text, same editor and same sanitising round trip as the description. */
  stepsToGenerate: string
  clientId: number | null
  clientContactId: number | null
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
  moduleId: null,
  screenName: '',
  feature: '',
  stepsToGenerate: '',
  clientId: null,
  clientContactId: null,
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
 *
 * `action` decides how much the form insists on. A draft is only worth having
 * if it accepts work in progress, so it relaxes the three rules that are the
 * blueprint's rather than the contract's:
 *
 * - **Description** — §7.5 marks it mandatory; `TicketCreateRequest` has it
 *   optional. The strict path keeps the blueprint's rule.
 * - **Estimated effort** — same shape, same reasoning.
 * - **The client rule for client-facing task types** — §4B.2's, not the
 *   contract's. You often save a draft precisely because you are still chasing
 *   which client it belongs to.
 *
 * It relaxes *nothing else*, and that is a contract fact rather than a choice:
 * `TicketCreateRequest.required` is `[projectId, title, taskTypeId, level]`, so
 * a draft missing any of those is a 400 whatever the UI allows. Level pre-fills
 * from the task type, so in practice a draft costs project + task type + title.
 */
export function ticketFormSchema(rules: TaskTypeRules, action: TicketSaveAction = 'assign') {
  const isDraft = action === 'draft'

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
      // A draft is the one case where that asterisk is waived.
      //
      // Rich text since C-066, which changes both halves of this check.
      // `.min(1)` would pass on an *empty* editor: the browser leaves
      // `<p><br></p>` behind the moment the field is focused, and that is 13
      // characters of nothing. And the bound is measured over the sanitised
      // HTML rather than the raw value, because that is the string the column
      // stores and Bean Validation rejects — a value can only shrink through
      // the sanitiser, so checking the raw form would reject a description the
      // server would have accepted.
      description: z
        .string()
        .refine(
          (html) => isDraft || !isRichTextEmpty(html),
          'Describe the task — this is what the assignee reads first',
        )
        .refine(
          (html) => sanitizeRichText(html).length <= createTicketBodyDescriptionMax,
          `Keep the description under ${createTicketBodyDescriptionMax} characters`,
        ),
      taskTypeId: requiredId('Select a task type'),
      level: z
        .nativeEnum(Level)
        .nullable()
        .refine((v) => v !== null, { message: 'Select a priority level' }),
      // §7.5's "Where it happened" group. Optional on the wire and optional
      // here for everything except a bug-type task type, which the
      // `superRefine` below handles — the rule needs the task type, and a
      // per-field schema cannot see its siblings.
      moduleId: optionalId,
      screenName: z
        .string()
        .trim()
        .max(createTicketBodyScreenNameMax, `Keep the screen name under ${createTicketBodyScreenNameMax} characters`),
      feature: z
        .string()
        .trim()
        .max(createTicketBodyFeatureMax, `Keep the feature under ${createTicketBodyFeatureMax} characters`),
      // Measured over the sanitised HTML for the same reason the description
      // is: that is the string the column stores and Bean Validation rejects,
      // and a value can only shrink through the sanitiser — bounding the raw
      // form value would refuse steps the server would have accepted.
      stepsToGenerate: z
        .string()
        .refine(
          (html) => sanitizeRichText(html).length <= createTicketBodyStepsToGenerateMax,
          `Keep the steps under ${createTicketBodyStepsToGenerateMax} characters`,
        ),
      clientId: optionalId,
      clientContactId: optionalId,
      assigneeId: optionalId,
      watcherIds: z.array(z.number().int().positive()),
      // Blank is reachable only on the draft path, where it means "not
      // estimated yet" and the mapper omits the field. Anything the user
      // actually typed is still format-checked, draft or not — a draft is
      // permission to leave a field empty, not permission to store "4h".
      estimatedHrs: z
        .string()
        .trim()
        .min(isDraft ? 0 : 1, 'Estimated effort is required')
        .refine((s) => (isDraft && s === '') || HOURS.test(s), 'Enter hours as a decimal — 4, 4.5 or 0.25')
        .refine((s) => (isDraft && s === '') || Number(s) > 0, 'Estimated effort must be greater than zero'),
      plannedCloseDate: z
        .string()
        .refine((s) => s === '' || !Number.isNaN(Date.parse(s)), 'Enter a valid date and time'),
    })
    .superRefine((values, ctx) => {
      // §4B.2's rule, not the contract's — and chasing down which client a
      // half-written ticket belongs to is a common reason to park it as a
      // draft in the first place. It applies in full on every other path.
      if (
        !isDraft &&
        values.taskTypeId != null &&
        rules.clientRequired.has(values.taskTypeId) &&
        values.clientId == null
      ) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          path: ['clientId'],
          message: 'This task type is client-facing — pick the client it was raised for',
        })
      }
      // §7.5, and the same shape as the description rule directly above it:
      // a blueprint rule rather than a contract one, so a draft waives it.
      // "Save as Draft waives it either way" is the blueprint's own sentence.
      if (
        !isDraft &&
        values.taskTypeId != null &&
        rules.bugTypes.has(values.taskTypeId) &&
        values.moduleId == null
      ) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          path: ['moduleId'],
          message: 'Pick the module this bug is in — it is what routes it to the right team',
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
 * Only ever called with values that passed the schema for the *same* action;
 * the guard states that invariant rather than papering over it with non-null
 * assertions, so a future caller that skips validation fails loudly instead of
 * sending `null` as an id.
 *
 * Every optional field follows one rule: **blank is omitted, never sent empty.**
 * `plannedCloseDate: null` means "this ticket has no planned close date" and
 * takes it out of every delay calculation, where omitting it asks the server to
 * compute one. `estimatedHrs: 0` is a genuine zero-hour estimate, where
 * omitting it says "not estimated yet" — which is exactly the difference a
 * draft needs to be able to express.
 */
export function toCreateRequest(
  values: TicketFormValues,
  action: TicketSaveAction = 'assign',
): TicketCreateRequest {
  const { projectId, taskTypeId, level } = values
  if (projectId == null || taskTypeId == null || level == null) {
    throw new Error('toCreateRequest received values that never passed validation')
  }

  // Sanitised on the way out as well as on the way in. The editor already
  // emits clean HTML, but `values` can also come from a draft loaded off the
  // wire, and §3.9's rule is that the client sanitises whatever it handles —
  // its copy is advice, but advice that skips a path is worse than none.
  // `isRichTextEmpty` rather than a truthiness check: an editor the user
  // focused and left empty holds `<p><br></p>`, and sending that would store a
  // description that reads as blank and validates as present.
  const description = isRichTextEmpty(values.description) ? '' : sanitizeRichText(values.description).trim()
  // Same round trip for the second rich-text field — §3.9 applies to whatever
  // the client handles, and a rule that skips one of its two fields is the
  // shape of bug this stream has already fixed twice.
  const stepsToGenerate = isRichTextEmpty(values.stepsToGenerate)
    ? ''
    : sanitizeRichText(values.stepsToGenerate).trim()
  const screenName = values.screenName.trim()
  const feature = values.feature.trim()
  const estimatedHrs = values.estimatedHrs.trim()

  return {
    projectId,
    title: values.title.trim(),
    taskTypeId,
    level,
    clientId: values.clientId,
    clientContactId: values.clientContactId,
    // §4B.2: "When the client is set and the reporter is a client contact, the
    // ticket is marked client-raised" — derived, never a choice the user
    // makes. `clientContactId` *is* "the reporter is a client contact" here:
    // it is the person S-19's Client contact dropdown names as who reported
    // it, so its presence alongside a client is the whole rule. The server is
    // still the authority (C-022's mock recomputes rather than trusting this),
    // the same trust boundary C-012's SLA preview draws for a value the
    // browser could get right most of the time and be silently wrong the rest.
    isClientRaised: values.clientId != null && values.clientContactId != null,
    assigneeId: values.assigneeId,
    watcherIds: values.watcherIds,
    // §7.5's four. Omitted when blank rather than sent as null, the rule every
    // optional field here follows — and on these four the two are genuinely
    // the same state, since all four columns are nullable and "not recorded"
    // is their only empty meaning.
    ...(values.moduleId != null ? { moduleId: values.moduleId } : {}),
    ...(screenName ? { screenName } : {}),
    ...(feature ? { feature } : {}),
    ...(stepsToGenerate ? { stepsToGenerate } : {}),
    ...(description ? { description } : {}),
    ...(estimatedHrs ? { estimatedHrs: Number(estimatedHrs) } : {}),
    ...(values.plannedCloseDate
      ? { plannedCloseDate: new Date(values.plannedCloseDate).toISOString() }
      : {}),
    saveAsDraft: action === 'draft',
  }
}

/**
 * What Save & Create Another carries into the next blank form.
 *
 * The split is "what does a batch of tickets share" — someone raising five from
 * one client's email re-picks the project, client, contact and task type five
 * times otherwise, which is the entire reason the action exists. Everything
 * that describes *this* ticket is cleared, because a title or an estimate
 * surviving into the next one is how a batch ends up with five copies of the
 * same summary.
 *
 * Assignee and watchers come along: a batch usually lands on one person, and
 * clearing an assignee is one click where re-picking one is several.
 */
export function retainedForNextTicket(values: TicketFormValues): Partial<TicketFormValues> {
  return {
    projectId: values.projectId,
    clientId: values.clientId,
    clientContactId: values.clientContactId,
    taskTypeId: values.taskTypeId,
    level: values.level,
    assigneeId: values.assigneeId,
    // **Module is deliberately not carried over**, unlike task type and level
    // beside it. It is the field §7.5's mandatoriness rule exists to make
    // somebody think about, and the note under that rule is precisely about not
    // letting people accept a default they did not choose: "forcing a choice
    // there just teaches people to pick the first item in the list, which
    // poisons the very reporting the field exists for". A module pre-filled from
    // the previous ticket makes accepting-the-default the path of least
    // resistance on exactly that field, on the one screen where a stale value is
    // least likely to be noticed — the form has just reset and the eye goes to
    // the empty title. Screen, feature and steps describe *this* concern and go
    // for the ordinary reason the title and the estimate do.
    // Copied, not aliased — the array in the request body that was just sent
    // must not be the one the next ticket's picker mutates.
    watcherIds: [...values.watcherIds],
  }
}
