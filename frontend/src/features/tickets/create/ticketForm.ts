import { z } from 'zod'
import {
  createTicketBodyDescriptionMax,
  createTicketBodyFeatureMax,
  createTicketBodyScreenNameMax,
  createTicketBodyStepsToGenerateMax,
  createTicketBodyTitleMax,
  createTicketBodyTitleMin,
} from '@/api/generated/zod/tickets/tickets.zod'
import type { Level } from '@/api/generated/model/level'
import type { ProjectSettings } from '@/api/generated/model/projectSettings'
import type { TaskType } from '@/api/generated/model/taskType'
import type { TicketCreateRequest } from '@/api/generated/model/ticketCreateRequest'
import type { TicketFieldCode } from '@/api/generated/model/ticketFieldCode'
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
 * C-068 · blueprint §7.5's bug-type task types: "**Mandatory for bug-type task
 * types**, optional for change requests and internal work."
 *
 * **These no longer decide whether Module is mandatory.** The schema requires a
 * module on every task type — see the `superRefine` below for why §7.5 was
 * widened. What this set still decides is how the refusal is worded, which is
 * worth keeping precise: a Production Bug is told to pick "the module this bug
 * is in", a change request "the module this ticket belongs to".
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
  /**
   * §7.5's bug-type task types. **No longer a gate** — every task type needs
   * a module now — but still the difference between "this bug" and "this
   * ticket" in the message the reporter reads.
   */
  bugTypes: ReadonlySet<number>
  /**
   * D-066 · the level codes the priority master currently declares.
   *
   * The `Level` schema stopped being a fixed enum because S-12 promises an
   * Admin can add one without a release, so there is no compile-time set left
   * to validate against — and there never should have been: the server has
   * always checked this against the master (`UnknownLevelException`), and the
   * enum only stopped the client sending a level somebody had just created.
   *
   * Empty while the master is loading, which is deliberate — see the schema's
   * own note. `LevelPicker` is fed the same list, so this cannot disagree with
   * what the user was offered.
   */
  levels: ReadonlySet<string>
}

/**
 * C-071 · what the *selected project* asks of this ticket — B-019's Settings
 * tab, which until now nothing obeyed.
 *
 * Two rules, and the difference between them is worth stating: the task-type
 * rules above come from the task-type master and are the same on every project,
 * while these change the moment the project picker changes. Everything derived
 * from them is therefore keyed on `projectId` and starts empty, which is the
 * safe direction — an unconfigured project and a project whose settings have not
 * arrived yet must both behave exactly like today.
 */
export interface ProjectRules {
  /**
   * Task type ids this project accepts.
   *
   * **`null` means unrestricted, and an empty set would mean the opposite.**
   * That distinction is the whole of B-019's design decision, restated as a type
   * so it cannot be lost: every project has no `project_task_types` rows until
   * somebody configures one, and reading that absence as "none are allowed"
   * would empty the task-type picker on every project in the organisation.
   * `restrictsTaskTypes` on the wire exists for exactly this, so it is read
   * rather than inferred from the array's length.
   */
  allowedTaskTypeIds: ReadonlySet<number> | null
  /** Otherwise-optional fields this project requires. Empty until the settings load. */
  mandatoryFields: ReadonlySet<TicketFieldCode>
}

/** No project picked, or its settings not back yet — today's behaviour, unchanged. */
export const noProjectRules: ProjectRules = {
  allowedTaskTypeIds: null,
  mandatoryFields: new Set<TicketFieldCode>(),
}

export function projectRulesFrom(settings: ProjectSettings | undefined): ProjectRules {
  if (!settings) return noProjectRules
  return {
    allowedTaskTypeIds: settings.restrictsTaskTypes
      ? new Set(settings.taskTypes.filter((t) => t.isAllowed).map((t) => t.taskTypeId))
      : null,
    mandatoryFields: new Set(settings.mandatoryFields),
  }
}

/**
 * The task types this project will actually accept.
 *
 * **Filtered out of the picker rather than shown and refused**, which is the
 * opposite of what the Client control does two fields down — and the two are
 * right for opposite reasons. A client blocked by B-028 is shown greyed because
 * the person raising the ticket is usually the person who can go and fix the
 * client master, so naming the obstacle is actionable. A task type this project
 * does not accept is not an obstacle to fix: it is a deliberate configuration
 * that only a PM or Admin can change, on a screen most of the six roles cannot
 * open. Offering it would be offering a refusal.
 *
 * An **inactive** type is dropped either way. It stays in the settings response
 * on purpose — B-019's checkbox grid must render an allowed-but-retired row or
 * the next save would silently drop it — but a retired type is one nobody may
 * raise regardless of what this project's allow-list still says.
 */
export function allowedTaskTypes(
  taskTypes: readonly TaskType[],
  rules: ProjectRules,
): readonly TaskType[] {
  const allowed = rules.allowedTaskTypeIds
  if (allowed == null) return taskTypes
  return taskTypes.filter((t) => t.id != null && allowed.has(t.id))
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
 * if it accepts work in progress, so it relaxes the four rules that are this
 * screen's rather than the contract's:
 *
 * - **Description** — §7.5 marks it mandatory; `TicketCreateRequest` has it
 *   optional. The strict path keeps the blueprint's rule.
 * - **Estimated effort** — same shape, same reasoning.
 * - **The client rule for client-facing task types** — §4B.2's, not the
 *   contract's. You often save a draft precisely because you are still chasing
 *   which client it belongs to.
 * - **Assignee** — a recorded deviation rather than a blueprint rule; see the
 *   field. A draft is often parked precisely because who picks it up is the
 *   part not yet decided.
 *
 * It relaxes *nothing else*, and that is a contract fact rather than a choice:
 * `TicketCreateRequest.required` is `[projectId, title, taskTypeId, level]`, so
 * a draft missing any of those is a 400 whatever the UI allows. Level pre-fills
 * from the task type, so in practice a draft costs project + task type + title.
 *
 * `project` is C-071's third input and the only one that changes while the form
 * is open — it arrives with the selected project's settings and is
 * `noProjectRules` until they do. A draft does not relax it; the `superRefine`
 * says why.
 */
export function ticketFormSchema(
  rules: TaskTypeRules,
  action: TicketSaveAction = 'assign',
  project: ProjectRules = noProjectRules,
) {
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
        .string()
        .nullable()
        .refine((v) => v !== null, { message: 'Select a priority level' })
        // Against the master, not against a literal union — D-066. An empty
        // set means the master has not loaded, and refusing every level in
        // that window would put "Select a priority level" under a control the
        // user has not been given any options in yet. The server checks the
        // same thing and answers 400, so the floor does not move.
        .refine((v) => v === null || rules.levels.size === 0 || rules.levels.has(v), {
          message: 'That priority level no longer exists — pick one of the ones offered',
        }),
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
      // Mandatory on this form, and a **recorded deviation** — §7.5 does not
      // put an asterisk on Assigned To, so the README says so in as many words
      // rather than letting it look like the blueprint asked for it.
      //
      // The argument for it is the one §7.5 makes about Module: a ticket
      // nobody owns is a ticket nobody routes. The argument against was that
      // §14's "Unassigned > 2h" escalation and C-015's Unassigned saved view
      // both presuppose unassigned tickets exist — and they still do. They are
      // raised by the paths a person is not standing in front of: D-036's
      // email-to-ticket, B-053's Excel import, and anything the API creates
      // directly. **This rule is the create *screen*'s, not the wire's**, which
      // is why nothing in `toCreateRequest` or the contract changes and why the
      // Unassigned view still has a job.
      //
      // Waived by Save as Draft, the same shape as the description and the
      // estimate above it: a blueprint-level rule about what a finished ticket
      // ought to say, and a draft exists to hold one that cannot say it yet.
      // Deciding who picks a half-written ticket up is a common reason to park
      // it in the first place.
      assigneeId: z
        .number()
        .int()
        .positive()
        .nullable()
        .refine((v) => isDraft || v !== null, {
          message: 'Pick who this ticket is assigned to',
        }),
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
      // §7.5 widened: **a module is mandatory on every task type now**, not
      // only the bug-type three. This is a deliberate deviation from the
      // blueprint, which argues the other way — a change request may genuinely
      // span three modules, and forcing a choice there "teaches people to pick
      // the first item in the list". It was widened anyway because an
      // unroutable ticket costs the desk more than an imprecise module does the
      // reporting, and because a rule that applies to some task types and not
      // others is one people read past.
      //
      // `rules.bugTypes` survives and still earns its keep: it now decides the
      // *wording* rather than whether the rule fires. "This bug" on a change
      // request reads as a bug in the form.
      //
      // Otherwise the same shape as the description rule directly above it: a
      // blueprint rule rather than a contract one, so a draft waives it. "Save
      // as Draft waives it either way" is the blueprint's own sentence, and the
      // waiver is the reason widening the rule is affordable — a ticket parked
      // precisely because nobody knows yet where it happened still parks.
      if (!isDraft && values.moduleId == null) {
        const isBugType = values.taskTypeId != null && rules.bugTypes.has(values.taskTypeId)
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          path: ['moduleId'],
          message: isBugType
            ? 'Pick the module this bug is in — it is what routes it to the right team'
            : 'Pick the module this ticket belongs to — it is what routes it to the right team',
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
      // C-071 · a task type the project does not accept. The picker no longer
      // offers one, so this fires on the path a filtered list cannot cover:
      // a type chosen on one project and carried into another by Save & Create
      // Another, whose `retainedForNextTicket` keeps the task type on purpose.
      // The server refuses it either way; catching it here is what puts the
      // message on the control rather than in a toast after a round trip.
      if (
        values.taskTypeId != null &&
        project.allowedTaskTypeIds != null &&
        !project.allowedTaskTypeIds.has(values.taskTypeId)
      ) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          path: ['taskTypeId'],
          message: 'This project does not accept that task type — pick one from the list',
        })
      }
      // C-071 · B-019's mandatory fields.
      //
      // **A draft does not waive these, where it waives the description, the
      // estimate and the client rule above.** Those three are the blueprint's
      // rules about what a ticket ought to say, and a draft exists precisely to
      // hold a ticket that cannot say them yet. These are a *project's* rules,
      // and `saveAsDraft` is accepted by the server and acted on nowhere — a
      // draft is stored as an ordinary ticket today. Waiving them on the flag
      // would mean one click turns a project's configuration off and produces a
      // ticket indistinguishable from any other, which is the same "setting that
      // does nothing" this task exists to fix. `ProjectSettingsGate` refuses a
      // draft the same way, so the form and the server agree.
      for (const [code, path, rich] of MANDATORY_FIELD_PATHS) {
        if (project.mandatoryFields.has(code) && isEmptyValue(values[path], rich)) {
          ctx.addIssue({
            code: z.ZodIssueCode.custom,
            path: [path],
            message: PROJECT_REQUIRES[code],
          })
        }
      }
    })
}

/**
 * The contract's `TicketFieldCode`, paired with the form field that answers it —
 * `ProjectTicketRules.RequiredField` on the server is the same table, keyed to
 * the same names, and the two have to stay in step or a field is required on one
 * side only.
 *
 * **`PLANNED_CLOSE_DATE` is deliberately absent.** Blank does not mean absent
 * there: it means "compute it from the SLA policy", the server does, and the
 * ticket ends up with a date. The control is read-only for four of the six roles
 * anyway, so a rule here would refuse a form those roles cannot fix. The server
 * measures that code against the *resolved* date and fails only when no rung of
 * the ladder answered either — which is a project misconfiguration rather than
 * something the person raising the ticket left out.
 */
const MANDATORY_FIELD_PATHS: ReadonlyArray<
  readonly [TicketFieldCode, keyof TicketFormValues, boolean]
> = [
  ['DESCRIPTION', 'description', true],
  ['MODULE', 'moduleId', false],
  ['SCREEN_NAME', 'screenName', false],
  ['FEATURE', 'feature', false],
  ['STEPS_TO_GENERATE', 'stepsToGenerate', true],
  ['CLIENT', 'clientId', false],
  ['CLIENT_CONTACT', 'clientContactId', false],
  // Redundant on the three live save actions since the assignee became
  // mandatory on this form, and kept anyway: the draft path waives the form's
  // rule and does not waive a project's, so a project that ticks ASSIGNEE is
  // the one way to refuse an unassigned *draft* too. It is also the code the
  // server measures, and a row missing here would make the two disagree.
  ['ASSIGNEE', 'assigneeId', false],
  ['ESTIMATED_HRS', 'estimatedHrs', false],
]

/** Worded as the project's rule, not the form's — it is why the asterisk is there. */
const PROJECT_REQUIRES: Record<TicketFieldCode, string> = {
  DESCRIPTION: 'This project requires a task description on every ticket',
  MODULE: 'This project requires a module on every ticket',
  SCREEN_NAME: 'This project requires a screen name on every ticket',
  FEATURE: 'This project requires a feature on every ticket',
  STEPS_TO_GENERATE: 'This project requires steps to generate on every ticket',
  CLIENT: 'This project requires a client on every ticket',
  CLIENT_CONTACT: 'This project requires a client contact on every ticket',
  ASSIGNEE: 'This project requires an assignee on every ticket',
  ESTIMATED_HRS: 'This project requires an effort estimate on every ticket',
  PLANNED_CLOSE_DATE: 'This project requires a planned close date on every ticket',
}

/**
 * Empty across the shapes a form value takes here.
 *
 * The two rich-text fields are measured with `isRichTextEmpty` rather than by
 * length, for C-066's reason: an editor the user focused and left holds
 * `<p><br></p>`, and thirteen characters of nothing is not an answer.
 * `ProjectTicketRules.hasRichText` is the same rule server-side, down to
 * counting a lone pasted image as content.
 *
 * **The plain fields are not measured that way, and the flag is what keeps them
 * apart.** `isRichTextEmpty` sanitises before it looks, so a screen name a user
 * typed as `<3` would be stripped to nothing and reported as blank — a rule
 * refusing a value the server stores happily.
 */
function isEmptyValue(value: TicketFormValues[keyof TicketFormValues], rich: boolean): boolean {
  if (value == null) return true
  if (Array.isArray(value)) return value.length === 0
  if (typeof value === 'string') return rich ? isRichTextEmpty(value) : value.trim() === ''
  return false
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
