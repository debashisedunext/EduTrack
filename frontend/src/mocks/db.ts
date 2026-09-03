/**
 * In-memory database behind the mock API. D-004.
 *
 * **Stateful on purpose.** A mock that returns static JSON lets you build a
 * screen; a mock that holds state lets you build a *flow* — create a ticket and
 * it appears in the list, hand it off and the ribbon advances, log effort and
 * the journey roll-up changes. Stream C's hardest work is the ribbon, and it
 * cannot be built against a frozen payload.
 *
 * Seeded deterministically: same data on every reload, so a screenshot in a bug
 * report still matches next week and tests do not need to pin values.
 *
 * This is *not* B-007. Ayush's fixture corpus is 200 tickets loaded into a real
 * database for backend and SLA testing. This is the frontend's mock — smaller,
 * and shaped to make the UI's hard cases visible.
 */

// ── deterministic randomness ────────────────────────────────────────────────
// Math.random would give a different ticket list on every reload, which makes
// "it looked different a minute ago" a permanent low-grade confusion.
function mulberry32(seed: number) {
  return () => {
    seed |= 0;
    seed = (seed + 0x6d2b79f5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const SEED = 20260803;
let rnd = mulberry32(SEED);
const pick = <T,>(xs: readonly T[]): T => xs[Math.floor(rnd() * xs.length)];
const int = (lo: number, hi: number) => lo + Math.floor(rnd() * (hi - lo + 1));

/**
 * B-029 · rewound at the start of every `createDb()`, which is what makes the
 * seed mean what its comment says.
 *
 * The generator was created once at module load and never reset, so the *stream*
 * was reproducible but each `createDb()` continued from wherever the last one
 * stopped. `resetDb()` runs after every test, so ticket N's level, assignee and
 * dates depended on **how many tests had run before it** — add a test anywhere
 * in a file and every test after it gets a different fixture.
 *
 * That is not a theoretical hazard. B-029 added one test to
 * `TicketListPage.test.tsx` and `C-016 · gives every Critical row a soft red
 * left border` began failing three tests later, in another `describe`, on an
 * assertion it does not own: the shift left Ravi with no CRITICAL ticket at
 * all, so the grid rendered its empty state and the row under test did not
 * exist. Both tests pass alone and the failure names neither cause — the worst
 * possible shape for a fixture bug, and it would have been charged to whoever
 * next touched that file.
 *
 * Rewinding here makes each `createDb()` identical, which is what every test in
 * the repository already assumes. **Flagged for Stream D** — `mocks/` is their
 * directory and the seeding strategy is theirs; this is three lines and it
 * makes the existing comment on `mulberry32` true.
 */
function rewindFixtureRandom(): void {
  rnd = mulberry32(SEED);
}

// ── types ───────────────────────────────────────────────────────────────────
export type RoleCode = 'ADMIN' | 'PM' | 'DEVELOPER' | 'QA' | 'DEPLOYMENT' | 'SUPPORT';
export type Level = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
export type StatusCode =
  | 'NEW' | 'IN_PROGRESS' | 'ON_HOLD' | 'AWAITING_INFO'
  | 'REWORK' | 'RESOLVED' | 'CLOSED' | 'REOPENED';
export type SegmentState =
  | 'COMPLETED' | 'CURRENT' | 'PENDING' | 'REWORKED' | 'SKIPPED' | 'BLOCKED';

export interface User {
  id: number; displayName: string; username: string; email: string;
  role: RoleCode; employeeCode: string; avatarUrl: string | null;
  reportingManagerId: number | null; projectIds: number[]; isActive: boolean;
  timezone: string;
  /** S-07 grid columns (B-010). `lastLoginAt` is null until the first login. */
  department: string | null; designation: string | null; lastLoginAt: string | null;
  /**
   * S-08 form fields (B-011), returned only by `GET /users/{id}`.
   *
   * `weeklyOff: null` means "inherit the org working week" and is different
   * from `[]`, which means this person has no weekly off at all. ISO day
   * numbers, 1=Mon … 7=Sun — never JavaScript's Sunday-zero.
   */
  mobile: string | null; dateOfJoining: string | null; location: string | null;
  dailyCapacityHrs: number; weeklyOff: number[] | null; skills: string[];
  /** Per-project role, `null` meaning "same as their global role". */
  projectRoles: Record<number, ProjectRoleCode | null>;
  /**
   * B-017 · per-project allocation %, keyed the same way `projectRoles` is.
   *
   * **A project missing from this map is "not stated", and that is not 100.**
   * The column is nullable for the same reason: every membership written before
   * the Team tab existed has no allocation because no screen had an input for
   * one, and defaulting them would tell the capacity report that somebody on
   * three projects is committed at 300%.
   */
  projectAllocations: Record<number, number | null>;
  /** B-017 · when each membership started, so the roster can order and show it. */
  projectMemberSince: Record<number, string>;
  mustChangePassword: boolean;
}

/**
 * A resource's role **on one project** — not `RoleCode`.
 *
 * `VIEWER` is a project role and not a global one; `ADMIN` is a global role and
 * not a project one. See the contract's `ProjectRoleCode` for why the two sets
 * differ in both directions.
 */
export type ProjectRoleCode = 'PM' | 'DEVELOPER' | 'SUPPORT' | 'QA' | 'DEPLOYMENT' | 'VIEWER';
export type ProjectStatus = 'ACTIVE' | 'ON_HOLD' | 'CLOSED';
export type AutoAssignRule = 'ROUND_ROBIN' | 'LEAST_LOADED' | 'MANUAL';
/**
 * B-019 · exactly the optional fields of `TicketCreateRequest`, and the
 * contract's `TicketFieldCode`.
 *
 * The always-required ones — project, title, task type, level — are absent
 * deliberately: a project cannot make them "more required", so a code for one
 * would be a value nothing could act on.
 */
export type TicketFieldCode =
  | 'DESCRIPTION' | 'MODULE' | 'SCREEN_NAME' | 'FEATURE' | 'STEPS_TO_GENERATE'
  | 'CLIENT' | 'CLIENT_CONTACT' | 'ASSIGNEE' | 'ESTIMATED_HRS' | 'PLANNED_CLOSE_DATE';
/**
 * B-016 · `status` replaced the stored `isActive`.
 *
 * Blueprint S-10 gives projects three states and a boolean cannot hold On Hold.
 * `isActive` is still on the wire — five screens filter on it — but it is
 * **derived** (`status !== 'CLOSED'`) rather than stored, so the two can no
 * longer disagree. See `projectDto` in `handlers/rest.ts`.
 */
export interface Project {
  id: number; projectCode: string; name: string; description: string | null;
  clientName: string | null; projectManagerId: number;
  colourTag: string; status: ProjectStatus;
  startDate: string | null; endDate: string | null;
  autoAssignRule: AutoAssignRule; ticketSeq: number;
  /**
   * B-019 · S-10 Settings tab. `null` and `[]` both mean "nothing beyond the
   * fields every ticket already requires" — the real column is nullable and
   * the screen writes `[]`, so the two states exist and the handler collapses
   * them exactly as the repository does.
   */
  mandatoryFields: TicketFieldCode[] | null;
}

/**
 * B-019 · one row of a project's task-type allow-list.
 *
 * **No rows for a project means every active task type is allowed, not none.**
 * That is the rule the whole Settings tab turns on, and the mock has to hold it
 * as literally as the database does — a seed that gave every project a full set
 * of rows would make the unrestricted state untestable, and it is the state
 * every project in the real system is in.
 */
export interface ProjectTaskType {
  projectId: number; taskTypeId: number;
}
/**
 * B-026 · widened to S-33's field set.
 *
 * **`status` replaces `isActive` as the stored fact**, and the DTO derives the
 * boolean from it as the server does — `status !== 'INACTIVE'`, not
 * `status === 'ACTIVE'`. A fixture that kept the boolean could not represent a
 * Prospect at all, so nothing in the browser would ever exercise the third state
 * the form now offers.
 *
 * **Support plans are upper-case codes.** They were title-case (`'Premium'`)
 * while `ReferenceDataFixture` and every server write have always stored
 * `'PREMIUM'` — a disagreement nothing caught because the grid renders the
 * stored string and B-025's filter matches case-insensitively. S-33's `<Select>`
 * binds to the codes, so against the old fixture it would have shown nothing
 * selected in `npm run dev` and been perfectly correct against a real backend.
 */
export interface Client {
  id: number; clientCode: string; name: string; domain: string;
  accountManagerId: number | null; supportPlan: string | null; timezone: string;
  status: 'ACTIVE' | 'INACTIVE' | 'PROSPECT';
  shortName?: string | null; logoUrl?: string | null; industry?: string | null;
  primaryEmail?: string | null; supportEmail?: string | null; phone?: string | null;
  addressLine1?: string | null; addressLine2?: string | null; city?: string | null;
  state?: string | null; country?: string | null; postalCode?: string | null;
  contractStart?: string | null; contractEnd?: string | null;
  billingReference?: string | null; billingEmail?: string | null;
  notes?: string | null; tags?: string[]; slaPolicyId?: number | null;
}
/**
 * B-025 · which clients are reachable from which project.
 *
 * S-32's Projects column and its project filter both read this, and the ticket
 * form's client dropdown is filtered by it (C-021). Seeded to agree with each
 * project's own `clientName`, so the two ways the fixture names the same
 * relationship cannot disagree.
 */
export interface ClientProject {
  clientId: number; projectId: number; isDefault: boolean;
}
/**
 * B-027 · `designation` and `isActive` arrived with the child grid.
 *
 * `designation` is a column that has been on `client_contacts` since the
 * baseline and that no schema carried, so the one field telling a desk whether
 * they are speaking to the IT Director or to a helpdesk operator was
 * unreadable. `isActive` exists because removal *deactivates* —
 * `tickets.client_contact_id` is an FK with no cascade — so the store has to be
 * able to hold a removed contact that `?includeInactive=true` still returns.
 */
export interface Contact {
  id: number; clientId: number; name: string; designation: string | null;
  email: string | null; phone: string;
  isPrimary: boolean; notificationOptIn: boolean; portalAccess: boolean;
  isActive: boolean;
}
/**
 * S-11's master row. `code` and `seq` arrived with B-020, which is the screen
 * that edits these — `code` because it is the stable identifier a client should
 * key off rather than the name an Admin can now change, and `seq` because it is
 * the picker's display order and this screen sets it.
 */
export interface TaskType {
  id: number; code: string; name: string; icon: string | null; colour: string;
  defaultLevel: Level; defaultSlaHrs: number | null; seq: number; isActive: boolean;
}
/**
 * D-045 — one browser that has granted push permission.
 *
 * Keyed by `endpoint`, like the real table, because the endpoint identifies the
 * browser rather than the account: a second user subscribing from the same
 * machine must take the row over, not add one.
 */
export interface PushSubscription {
  endpoint: string; userId: number; p256dh: string; auth: string; userAgent: string | null;
}

/** §7.5 — the product area a concern was raised against. */
export interface Module {
  id: number; code: string; name: string; seq: number; isActive: boolean;
}

/**
 * B-021 · S-12. One priority level, as `GET /masters/priorities` returns it.
 *
 * Rows in the store rather than a literal in the handler, which is what they
 * were until B-021: the master screen creates and edits them, and a handler
 * returning a frozen array has nothing for a `PATCH` to write to.
 *
 * `level` is the wire name for the `code` column — see the contract's `Priority`
 * schema for why the two differ and why renaming it would break two of Stream
 * C's screens.
 */
export interface Priority {
  id: number; level: Level; name: string; colour: string;
  defaultSlaHrs: number | null; autoEscalates: boolean;
  seq: number; isActive: boolean;
}

/**
 * B-039 · S-13 tab 1. One status, as `GET /masters/statuses` returns it.
 *
 * `ticketCount` and `transitionCount` are **not** here, deliberately — the
 * handler derives both from `tickets` and `workflowTransitions`, exactly as the
 * server's SQL does. Storing them would let the fixture and the server disagree
 * about whether a status can be retired, and the fixture would be the one the
 * screen's tests believed.
 *
 * `category` cannot be derived from `isOpen`/`isTerminal` and is stored for the
 * same reason the column exists: NEW and REOPENED are TODO while ON_HOLD is
 * IN_PROGRESS, and all three carry `isOpen: true, isTerminal: false`.
 */
export interface Status {
  id: number; code: StatusCode; name: string; category: StatusCategory;
  colour: string; seq: number;
  isOpen: boolean; isTerminal: boolean; isActive: boolean;
}

export type StatusCategory = 'TODO' | 'IN_PROGRESS' | 'DONE';

/**
 * B-039 · one cell of the allowed-transition matrix.
 *
 * **The collection is a whitelist**: a missing `(from, to, role)` means the move
 * is impossible for that role. `fromStatus: null` is "on creation" — the only
 * way into NEW, and the row the `PUT` refuses to let you clear entirely.
 */
export interface WorkflowTransitionRow {
  id: number; fromStatus: StatusCode | null; toStatus: StatusCode;
  roleCode: string; requiresReason: boolean; requiresEffort: boolean;
  isActive: boolean;
}

/**
 * B-022 · S-15. One notification template, as
 * `GET /masters/notification-templates` returns it.
 *
 * `isMandatory` is **not** here, deliberately. The server derives it from the
 * event's category and the channel, so storing it in the fixture would let the
 * mock and the server disagree about which templates are locked — and the mock
 * would be the one the screen's tests believed. `templateDto` in the handlers
 * computes it the same way the service does.
 */
export interface NotificationTemplateRow {
  id: number; eventCode: string; category: NotificationCategory;
  channel: NotificationChannelCode; recipients: string[];
  subjectTemplate: string | null; bodyTemplate: string; isActive: boolean;
}

export type NotificationCategory =
  | 'MENTION' | 'ASSIGNMENT' | 'ESCALATION' | 'STATUS_REQUEST' | 'OTHER';

/** D-042's three. The bell is not one — it renders the `IN_APP` wording. */
export type NotificationChannelCode = 'IN_APP' | 'EMAIL' | 'PUSH';

/**
 * B-015 · S-09. One capability, as `GET /masters/permissions` returns it.
 *
 * `category` is the **application** area the Role Master groups by — not
 * `Module` above, which is the *product* area a ticket is raised against.
 * Blueprint §7 overloads the word and the two must never be joined.
 */
export interface Permission {
  id: number; code: string; name: string; description: string | null;
  category: string;
  /**
   * False means no role may hold it and S-09 renders the checkbox disabled.
   * Computed by the server from a constant, not stored — it is a property of
   * the append-only guarantee, not a flag anyone can flip.
   */
  isGrantable: boolean;
}

/** B-015 · S-09. A role, plus the two counts the grid renders. */
export interface Role {
  id: number; code: string; name: string; description: string | null;
  isSystem: boolean; isActive: boolean;
}
export interface Stage {
  stageCode: string; displayName: string; sequence: number; ownerRole: RoleCode;
  icon: string; stageSlaHrs: number | null; isOptional: boolean; canReturnTo: string[];
}

/**
 * B-040 · S-13 tab 2 — one template, and one row of one template's ribbon.
 *
 * **Separate from `Stage` above, and the separation is a finding rather than a
 * preference.** `db.stages` is Stream C's flat ribbon fixture and its codes are
 * `DEVELOPMENT` and `VERIFICATION`; the database seeds `DEV` and `VERIFY`
 * (`V20260807_1700`). So the mock and the real backend disagree about the
 * vocabulary of the ribbon, and a screen tested only against the mock would ship
 * looking right. Reconciling it means renaming codes that Stream C's reopen
 * fixture and `ReopenDialog.test.tsx` assert on, which is their file — recorded
 * in the backlog note instead.
 *
 * These rows follow the database, because tab 2 is the screen that edits the
 * database's rows.
 */
/**
 * B-041 · S-13 tab 3 - one routing rule of blueprint 4A.9.
 *
 * **A null id means "any", not "unknown".** That is the whole reason both
 * columns are nullable in `workflow_template_mappings`, and it is what lets the
 * resolver's four-rung ladder be expressed in rows instead of code.
 *
 * The mock holds only the two ids; `projectCode`, `projectName`, `taskTypeCode`
 * and `taskTypeName` are joined in by the handler the way the server joins them,
 * so a screen that read them off the fixture instead of off the response would
 * still be exercising the shape it will get in production.
 */
export interface TemplateMappingRow {
  id: number; templateId: number;
  projectId: number | null; taskTypeId: number | null;
}
export interface WorkflowTemplateRow {
  id: number; name: string; description: string | null;
  isDefault: boolean; isActive: boolean;
  /**
   * B-041 - how many tickets ever started on this template. The delete's whole
   * guard.
   *
   * **Held on the row rather than counted off `tickets`**, which is the same
   * call `TemplateStage.transitionCount` makes one type down and for the same
   * reason: the mock's `Ticket` has no `workflowTemplateId` at all, so a count
   * over the fixture would be zero for every template and the in-use refusal
   * would be unreachable from the screen. The server counts the column.
   *
   * Seeded above zero on Standard Dev Flow only - it is the one every other
   * template is a reduction of, so it is where history would actually be. The
   * other two are deletable, which is the ordinary case a create lands in.
   */
  ticketCount: number;
}
export interface TemplateStage {
  id: number; templateId: number; stageCode: string; displayName: string;
  ownerRole: string; slaHours: number | null; isOptional: boolean;
  canReturnTo: string[]; icon: string | null; seq: number;
  /** What freezes the code. Seeded above zero on one stage so the rule is reachable. */
  transitionCount: number; openTicketCount: number;
  /**
   * B-042 · §7.4's "deprecated, never deleted".
   *
   * **Seeded false on all eighteen, deliberately.** A retired row here would be
   * one `TicketListPage` drops from S-25's stage filter and one tab 2 renders
   * greyed — both correct, and both making it impossible for a test to tell "the
   * screen handles a deprecated stage" from "the fixture came with one". The
   * screen's own tests retire a stage first and then assert.
   */
  isDeprecated: boolean; deprecatedAt: string | null;
}
export interface Ticket {
  ticketId: string; title: string; description: string;
  projectId: number; clientId: number | null; clientContactId: number | null;
  isClientRaised: boolean; taskTypeId: number;
  /** §7.5 "where it happened". Null on tickets raised before the fields existed. */
  moduleId: number | null; screenName: string | null; feature: string | null;
  /** Sanitised HTML — PLAN.md §3.9. */
  stepsToGenerate: string | null;
  level: Level; originalLevel: Level; status: StatusCode;
  currentStageCode: string | null; assigneeId: number | null; reportedById: number;
  cycleNo: number; iterationNo: number; reopenCount: number;
  plannedCloseDate: string | null; actualCloseDate: string | null;
  isDelayed: boolean; delayedSince: string | null;
  estimatedHrs: number | null; pctComplete: number;
  /**
   * ⚠ For Stream D. `TicketCreateRequest.watcherIds` and
   * `TicketPatchRequest.watcherIds` have existed since D-001 and
   * `TicketDetailResponse.data.watchers` returns them, but nothing stored
   * them: `POST /tickets` dropped the field and `/full` answered `watchers: []`
   * unconditionally, so C-010's watcher picker looked wired end to end and
   * silently was not. Held on the ticket rather than in a join table because
   * that is all the mock needs to round-trip the contract.
   */
  watcherIds: number[];
  createdAt: string; updatedAt: string; version: number;
}
export interface Cycle {
  ticketId: string; cycleNo: number; isSealed: boolean;
  startedAt: string; closedAt: string | null; reason: string | null;
}
/**
 * C-064 · a relationship between two tickets, blueprint §16 item 17.
 *
 * One row per relationship, stored **canonical** — see `canonicalizeLink` in
 * `handlers/tickets.ts`, which mirrors `TicketLinkService.canonicalize` on the
 * real backend so "A blocks B" and "B is blocked by A" always land on the
 * same row rather than depending on which ticket the caller described it
 * from. `linkType` is one of `BLOCKS | DUPLICATE_OF | RELATES_TO` in this
 * store — `BLOCKED_BY` is a submittable direction that never survives
 * canonicalisation, so it is never a *stored* value here either.
 */
export interface TicketLink {
  id: number; sourceTicketId: string; targetTicketId: string; linkType: string;
  createdById: number | null; createdAt: string;
}
export interface Transition {
  id: number; ticketId: string; cycleNo: number; iterationNo: number;
  stageCode: string; ownerId: number | null; ownerRole: RoleCode;
  enteredAt: string; exitedAt: string | null; durationMins: number | null;
  action: string; note: string | null; skipReason: string | null;
}
export interface EffortLog {
  id: number; ticketId: string; userId: number; hours: number; workDate: string;
  note: string | null; stageCode: string | null; cycleNo: number; iterationNo: number;
  isCorrection: boolean; correctsEntryId: number | null; createdAt: string;
}
export interface HistoryEntry {
  id: number; ticketId: string; action: string; actorId: number | null;
  actorType: 'USER' | 'SYSTEM';
  fieldName: string | null; oldValue: string | null; newValue: string | null;
  note: string | null; stageCode: string | null;
  cycleNo: number; iterationNo: number;
  isCorrection: boolean; correctsEntryId: number | null;
  entryHash: string; createdAt: string;
}
export interface Comment {
  id: number; ticketId: string; body: string; originalBody: string | null;
  authorId: number; isClientVisible: boolean; isEdited: boolean; isDeleted: boolean;
  stageCode: string | null; cycleNo: number; iterationNo: number;
  mentionIds: number[]; createdAt: string;
  /**
   * C-032 · the author's role AT THE TIME OF WRITING, stamped alongside
   * `stageCode`/`cycleNo`/`iterationNo` — never joined from the live user, or
   * a promotion after the fact would rewrite what the comment looked like
   * when it was posted. Optional because the fixtures below predate this
   * field; `commentDto` falls back to the author's current role for those,
   * mirroring `CommentDto.of`'s identical fallback for a pre-C-032 row.
   */
  authorRole?: RoleCode | null;
  /**
   * C-033 · the tombstone. Both null until the comment is removed, and both
   * stamped by the same write — a row carrying one without the other is not a
   * state the server produces. Deliberately the same shape as `Attachment`'s
   * pair, which the two are meant to be read beside.
   *
   * Unlike an attachment there is **no window and no silent case**: §4B.5
   * attaches its five minutes to editing and says of deletion only that it
   * leaves a tombstone, so every removal here is recorded — including an
   * author's own, seconds after posting.
   */
  deletedById?: number | null;
  deletedAt?: string | null;
  /**
   * D-14 · when it was last rewritten, null if never.
   *
   * On the wire since the five-minute window was lifted: "edited" no longer
   * implies "moments after posting", so without this a reader cannot tell a
   * typo fixed a minute later from a claim rewritten three months on.
   */
  editedAt?: string | null;
}
export interface Attachment {
  id: number; ticketId: string; fileName: string; contentType: string;
  sizeBytes: number; scanStatus: 'PENDING' | 'CLEAN' | 'INFECTED';
  isClientVisible: boolean; isDeleted: boolean; uploadedById: number;
  stageCode: string | null; cycleNo: number; createdAt: string;
  /**
   * C-028 · the tombstone. Both null until the file is removed, and both stamped
   * by the same write — a row carrying one without the other is not a state the
   * server produces.
   *
   * Whether a removal is *shown* is derived from these against `uploadedById`
   * and `createdAt`, not stored: the uploader removing their own file inside
   * §4B.4's fifteen minutes leaves nothing behind, and anyone else — or the same
   * person later — leaves "file removed by X on date".
   */
  deletedById?: number | null; deletedAt?: string | null;
}
export interface Notification {
  id: number; userId: number; eventKey: string; title: string; body: string;
  ticketId: string | null; isRead: boolean; deepLink: string; createdAt: string;
  /** D-046. Null means still queued to pop — distinct from unread. */
  deliveredAt?: string | null;
}
/** D-042. Sparse overrides, as on the server — absence means enabled. */
export interface NotificationPreference {
  userId: number; eventKey: string; inApp: boolean; email: boolean;
  /** D-045. Absence means enabled, like the other two — the table stores deviations. */
  push: boolean;
}
export interface EmailLogEntry {
  id: number; ticketId: string; toAddress: string; subject: string; eventKey: string;
  status: 'QUEUED' | 'SENT' | 'BOUNCED' | 'FAILED'; retryCount: number;
  nextAttemptAt: string | null; providerMessageId: string | null; sentAt: string | null;
}
export interface ChatThread {
  id: number; kind: 'TICKET' | 'DIRECT' | 'PROJECT'; title: string;
  ticketId: string | null; participantIds: number[]; lastMessageAt: string | null;
}
/**
 * D-053 · a file shared into a thread (§7.6).
 *
 * Held on the db rather than in a handler module for `resetDb()`'s reason —
 * state outside this object survives a reset and leaks between tests.
 */
export interface ChatAttachment {
  id: number; threadId: number;
  /** Null between the upload and the message that carries it — two requests, by design. */
  messageId: number | null;
  fileName: string; contentType: string; sizeBytes: number;
  /** PENDING | CLEAN | INFECTED. The mock seals CLEAN immediately; the server waits for clamd. */
  scanStatus: 'PENDING' | 'CLEAN' | 'INFECTED';
  uploadedById: number; createdAt: string;
}
export interface ChatMessage {
  id: number; threadId: number; body: string; authorId: number;
  kind: 'TEXT' | 'STATUS_REQUEST' | 'SYSTEM'; isEdited: boolean; isDeleted: boolean;
  readBy: number[]; createdAt: string;
}
/**
 * D-055 / D-056 · one "Ask Status" and, once answered, how long it took.
 *
 * `note` is not stored: the server reads it from the request message so §7.6's
 * tombstone reaches it, and a mock that kept its own copy would show a note the
 * real API withholds. `requestMessageId` is the link, exactly as on the server.
 *
 * `responseWorkingMinutes` is **working** minutes. The seeded answered request
 * spans 09:20 to 09:35 on a working morning, so the two happen to agree — but a
 * fixture that only ever exercises a window inside working hours is one nobody
 * can tell apart from wall clock, so the second seeded row deliberately spans a
 * weekend.
 */
export interface StatusRequest {
  id: number; ticketId: string; ticketTitle: string;
  threadId: number; requestMessageId: number;
  requestedById: number; askedOfId: number; requestedAt: string;
  answerMessageId: number | null; answeredAt: string | null;
  responseWorkingMinutes: number | null;
}

/** B-065 · one row per (userId, weekStartDate) — uq_timesheet_approvals_week's shape. */
export interface TimesheetApproval {
  id: number; userId: number; weekStartDate: string;
  approvedById: number; approvedAt: string; note: string | null;
}

// ── A-118 · client onboarding ───────────────────────────────────────────────
// The second module's fixtures. `Ob`-prefixed like the contract's schemas and
// the `ob_` tables, so the two modules stay separable by grep here too.

/** `ob_products` — the catalogue journey templates bind to. */
export interface ObProduct {
  id: number; code: string; name: string; isActive: boolean;
  /** Whether an active journey template exists. The OB-04 picker requires it. */
  hasActiveTemplate: boolean;
  /** Σ of the active template's service TATs, in working days. */
  totalTatDays: number | null;
}

/** `ob_client_contacts` — the SPOCs. Exactly one `isPrimary` per client. */
export interface ObContact {
  id: number; name: string; designation: string | null;
  email: string; phone: string | null; whatsappOptIn: boolean; isPrimary: boolean;
}

/**
 * `ob_client_applications` — one purchased product.
 *
 * A purchase fact, **not a commercial one**: no amount, no invoice, no payment
 * status anywhere in this module by explicit product decision (plan §1.2).
 */
export interface ObApplication {
  id: number; productId: number; licenseType: string | null; units: number | null;
  licenseStart: string | null; licenseEnd: string | null;
}

/** One service on a journey — a RAG dot on the OB-05 accordion strip. */
export interface ObStep {
  id: number; sequence: number; name: string;
  status: 'PENDING' | 'IN_PROGRESS' | 'BLOCKED' | 'WAITING_ON_CLIENT' | 'DONE' | 'SKIPPED';
  /** TAT in working **days** — the v1.2 rename, not hours. */
  tatDays: number;
  /** Working hours consumed. Waiting-on-client time is excluded. */
  usedHours: number;
  /** The one service this waits for; null runs in parallel. */
  dependsOnStepId: number | null;
}

/** `ob_journeys` — one per purchased product, `UNIQUE(client_id, product_id)`. */
export interface ObJourney {
  id: number; productId: number;
  gateStatus: 'LOCKED' | 'OPEN';
  /** Set while held behind a sibling journey's completion (plan §5.5). */
  heldByJourneyId: number | null;
  steps: ObStep[];
}

/** `ob_clients`. No payment columns — see {@link ObApplication}. */
export interface ObClient {
  id: number; name: string; description: string | null; onboardingDate: string;
  /** Stored unmasked here; every read masks it. Unique — the duplicate guard's key. */
  pan: string | null;
  address: string | null; licenseType: string | null;
  salesPersonId: number | null;
  status: 'ONBOARDING' | 'LIVE' | 'ON_HOLD' | 'DROPPED';
  liveAt: string | null;
  hasPortalLogin: boolean;
  contacts: ObContact[];
  applications: ObApplication[];
  requirements: string[];
  journeys: ObJourney[];
  createdById: number; createdAt: string;
}

// ── the store ───────────────────────────────────────────────────────────────
export interface Db {
  users: User[]; projects: Project[]; clients: Client[]; contacts: Contact[];
  clientProjects: ClientProject[];
  taskTypes: TaskType[]; modules: Module[]; priorities: Priority[]; stages: Stage[];
  /** B-039 · S-13 tab 1 — the status vocabulary and the whitelist that governs moves between them. */
  statuses: Status[]; workflowTransitions: WorkflowTransitionRow[];
  /** B-040 · S-13 tab 2 — the three templates of §4A.9 and their stages. */
  workflowTemplates: WorkflowTemplateRow[]; templateStages: TemplateStage[];
  /** B-041 - S-13 tab 3. Which template a project x task type routes to. */
  templateMappings: TemplateMappingRow[];
  /** B-015 · S-09. `roleGrants` is keyed by role id — the matrix, one row per role. */
  permissions: Permission[]; roles: Role[]; roleGrants: Record<number, string[]>;
  /** B-022 · S-15. One row per (event, channel) — the wording of everything sent. */
  notificationTemplates: NotificationTemplateRow[];
  slaPolicies: SlaPolicy[];
  projectTaskTypes: ProjectTaskType[];
  pushSubscriptions: PushSubscription[];
  tickets: Ticket[]; cycles: Cycle[]; transitions: Transition[];
  effortLogs: EffortLog[]; history: HistoryEntry[]; comments: Comment[];
  attachments: Attachment[]; ticketLinks: TicketLink[]; notifications: Notification[];
  notificationPreferences: NotificationPreference[];
  emailLog: EmailLogEntry[]; chatThreads: ChatThread[]; chatMessages: ChatMessage[];
  chatAttachments: ChatAttachment[];
  statusRequests: StatusRequest[];
  timesheetApprovals: TimesheetApproval[];
  /**
   * A-118 · the Client Onboarding module.
   *
   * Held on `Db` rather than in `handlers/onboarding.ts` for the reason
   * `calendar` gives below: state outside this object survives `resetDb()`,
   * and the failure that produces is a test which passes alone and fails in a
   * full run.
   *
   * **Disjoint from `clients` and `contacts` on purpose.** Those are the
   * ticketing master; these are `ob_clients` and `ob_client_contacts`, a
   * separate table with no foreign key between them (plan §1.2). The ids do
   * not correspond and nothing here should ever join them — the mock is the
   * first place that separation is either kept or quietly lost.
   */
  obProducts: ObProduct[];
  obClients: ObClient[];
  currentUserId: number;
  seq: Record<string, number>;
  /**
   * B-023 · the working calendar (S-14).
   *
   * Held here rather than in the handler module so `resetDb()` covers it. State
   * that lives outside this object does not get reset between tests, and the
   * failure that produces is a test which passes alone and fails in a full run.
   *
   * `weeklyOff` is **ISO-8601**: Mon=1 … Sun=7, so `[6, 7]` is Sat + Sun — not
   * the `[0, 6]` a JS `Date.getDay()` would give. Times carry seconds, matching
   * what a Java `LocalTime` serialises to.
   */
  calendar: {
    week: { weeklyOff: number[]; workDayStart: string; workDayEnd: string; timezone: string };
    holidays: Holiday[];
    leaves: ResourceLeave[];
  };
  /**
   * A-065 · scheduled report emails (§7.8).
   *
   * ⚠️ Stream A, in Stream D's `mocks/` — the mock-coverage test refuses a
   * contract operation with no handler, so the three routes A-065 adds bring
   * their handlers with them. Flagged rather than quiet, like the rest of that
   * task's cross-stream edits.
   *
   * Held here rather than as a module-level `let` in the handler for the
   * calendar's stated reason: state outside this object survives `resetDb()`,
   * and one test's saved schedule would become the next test's starting point.
   *
   * Empty to start, which is the honest state — nobody has scheduled anything.
   */
  reportSchedules: ReportScheduleRow[];
  /**
   * A-029 · two-factor enrolment, keyed by user id. No entry means never
   * enrolled — which is the starting state for every seeded user, because 2FA
   * is opt-in.
   *
   * Held here for the same reason the calendar is: `resetDb()` has to clear it,
   * or one test's enrolment becomes the next test's starting state.
   *
   * `secret` and `enabled` are separate because the server's are. Setup issues
   * a secret and enables nothing; only a correct code at confirm flips
   * `enabled`. Collapsing the two into one flag would let S-04 be built against
   * a flow the backend does not have — one where showing the QR is enrolment.
   */
  twoFactor: Record<number, { secret: string; enabled: boolean }>;
  /**
   * C-027 · §4B.4's three attachment caps, as the server would have them
   * configured. Held here for the calendar's reason — `resetDb()` has to clear
   * them, or a test that raises the limits leaves them raised for the next one.
   *
   * `ceilingBytes` mirrors the real server's `spring.servlet.multipart
   * .max-file-size`: it bounds `maxFileBytes` however the setting is written,
   * and the `PUT` refuses rather than clamps so the mock rejects the same
   * request the server would.
   */
  attachmentLimits: { maxFileBytes: number; maxTicketBytes: number; maxFiles: number; ceilingBytes: number };
  /**
   * B-033 · S-34 step 3's saved column mappings — `import_mapping_presets`.
   *
   * Held here for the calendar's reason: `resetDb()` has to clear them, or a test
   * that saves a preset leaves it in the picker for the next one. Keyed by schema,
   * because the real unique key is `(schema_key, name)` and a store keyed only by
   * name could not show that two schemas may reuse one.
   *
   * Empty on purpose. §4B.3's presets are created by a person naming one, so a
   * seeded organisation has none — and a screen test that found one already there
   * could not tell "the list renders" from "the list renders what I saved".
   */
  mappingPresets: Record<string, { presetId: number; name: string; mapping: Record<string, string>; updatedAt: string }[]>;

  /**
   * B-035 · commit runs, keyed by batch id.
   *
   * Stateful rather than a constant, because the one control step 5 exists to
   * show is a progress bar — and a handler that answered COMPLETED to its first
   * poll would make it impossible to see one move, which is precisely the case
   * a screen gets wrong. Each poll advances the run by a row.
   *
   * **Seeded since B-037, where it was empty on purpose before.** The old reason
   * was the progress bar: a batch is created by pressing Import, and a seeded one
   * would let a step-5 test pass that never committed anything. That still holds
   * for the runs the poll advances, and nothing here is one of those.
   *
   * The history panel inverts the argument. Its whole point is showing runs from
   * *before* this session — an empty history can only be seen by importing first,
   * and a reversal could not be exercised at all without running an import,
   * waiting for it to finish and only then reversing. So two finished runs are
   * seeded and every new batch is still created by pressing Import.
   */
  importBatches: Record<number, ImportBatchRow>;
}

/** One row of `import_batches`, as the contract's `ImportBatch` shapes it. */
export interface ImportBatchRow {
  batchId: number;
  entity: string;
  fileName: string;
  status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED';
  processed: number;
  total: number;
  created: number;
  updated: number;
  rejected: number;
  errorReportUrl: string | null;
  /** B-037 · provenance — the history panel's first three columns. */
  startedAt: string;
  importedBy: number | null;
  importedByName: string | null;
  /**
   * B-037 · reversal. `reversedAt` is the whole state machine, exactly as on the
   * real row: there is no `REVERSED` status, because `status` records how the
   * *run* ended and a reversal is a later fact about a run that already ended.
   */
  reversedAt: string | null;
  reversedRows: number;
  retainedRows: number;
  /**
   * The server decides whether the Reverse button is enabled, and the mock has
   * to as well — a mock that always sent `true` would let a screen ship that
   * offers to reverse a running import. Kept as a stored field rather than
   * derived on read for the same reason the real DTO computes it server-side:
   * one place decides, and the client never re-derives it.
   */
  reversible: boolean;
}

export interface Holiday {
  id: number; date: string; name: string;
  projectId: number | null; isRecurring: boolean; isActive: boolean;
}

/**
 * A-065 · one scheduled report and the runs it has produced.
 *
 * Deliberately carries `createdBy` and no stored scope, mirroring
 * `report_schedules`: the server re-resolves the owner's role and projects on
 * every run, and a mock that held a frozen scope would be modelling a design
 * the schema went out of its way not to have.
 */
export interface ReportScheduleRow {
  id: number; reportKey: string; reportTitle: string;
  cadence: 'DAILY' | 'WEEKLY' | 'MONTHLY';
  format: 'xlsx' | 'csv' | 'pdf';
  recipients: string[]; parameters: Record<string, unknown>;
  active: boolean; createdBy: number; createdByName: string | null;
  nextRunAt: string; lastRunAt: string | null;
  recentRuns: ReportScheduleRunRow[];
}

export interface ReportScheduleRunRow {
  id: number; runAt: string; periodFrom: string; periodTo: string;
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED';
  rowCount: number | null; appliedScope: string | null; errorText: string | null;
  downloadable: boolean;
}

/**
 * C-012 · the SLA matrix, layered exactly as `sla_policies` is.
 *
 * `projectId: null` is the org-wide default and `taskTypeId: null` means "any
 * type", so resolution runs most-specific-first:
 * `(project, taskType, level) → (project, level) → (null, level)`.
 *
 * Held as rows rather than derived from `taskTypes` — which is what
 * `GET /projects/:id/sla-policies` did until now — because a derived matrix
 * gives every level the same hours, and a preview that does not move when the
 * level changes is the one thing C-012 exists to make visible.
 *
 * `responseHrs` and `resolutionHrs` are **working** hours. They are consumed
 * through the calendar walk, never as wall-clock.
 */
export interface SlaPolicy {
  id: number; projectId: number | null; taskTypeId: number | null; level: Level;
  responseHrs: number | null; resolutionHrs: number; isActive: boolean;
}

export interface ResourceLeave {
  id: number; userId: number; startDate: string; endDate: string;
  leaveType: string; isHalfDay: boolean; status: string; reason: string | null;
}

export const nextId = (db: Db, key: string): number => (db.seq[key] = (db.seq[key] ?? 0) + 1);

const iso = (d: string) => new Date(`${d}Z`).toISOString();

// ── seed ────────────────────────────────────────────────────────────────────
/**
 * Deliberately varied for B-010's grid: two departments, one resource on a
 * single project rather than all three, one deactivated, and one who has never
 * logged in. A directory where every row is identical proves nothing about the
 * screen that renders it.
 */
/**
 * B-017 · stated allocations, and most memberships deliberately have none.
 *
 * Ravi is the over-allocated case (70 + 50 = 120%), which the Team tab has to be
 * able to *show* rather than prevent; Anil is the 0% case, which must not render
 * as "not stated"; everybody else has nothing, which is what every row written
 * before this screen looks like. A roster where each member has a tidy number
 * proves nothing about either edge.
 */
const SEEDED_ALLOCATIONS: Record<string, Record<number, number | null>> = {
  ravi: { 1: 70, 2: 50 },
  anil: { 1: 0 },
  meera: { 1: 100 },
};

const USERS: User[] = [
  ['Anita Rao', 'anita', 'ADMIN', 'EMP-001', null, 'Leadership', 'Head of Delivery', [1, 2, 3], true, '2026-08-11T04:15:00.000Z'],
  ['Meera Iyer', 'meera', 'PM', 'EMP-002', 1, 'Delivery', 'Project Manager', [1, 2, 3], true, '2026-08-10T11:02:00.000Z'],
  ['Ravi Kumar', 'ravi', 'DEVELOPER', 'EMP-003', 2, 'Engineering', 'Senior Engineer', [1, 2], true, '2026-08-10T06:30:00.000Z'],
  ['Anil Shah', 'anil', 'QA', 'EMP-004', 2, 'Quality', 'QA Engineer', [1, 2, 3], true, '2026-08-09T13:45:00.000Z'],
  ['Karan Bose', 'karan', 'DEPLOYMENT', 'EMP-005', 2, 'Platform', 'Release Engineer', [3], true, null],
  ['Priya Nair', 'priya', 'SUPPORT', 'EMP-006', 2, 'Support', 'Support Lead', [1, 2, 3], true, '2026-08-11T03:20:00.000Z'],
  ['Sunil Menon', 'sunil', 'DEVELOPER', 'EMP-007', 2, 'Engineering', 'Engineer', [2], false, '2026-05-02T09:10:00.000Z'],
].map(([displayName, username, role, employeeCode, mgr, department, designation, projectIds, isActive, lastLoginAt], i) => ({
  id: i + 1,
  displayName: displayName as string,
  username: username as string,
  email: `${username as string}@edunext.example`,
  role: role as RoleCode,
  employeeCode: employeeCode as string,
  avatarUrl: null,
  reportingManagerId: mgr as number | null,
  projectIds: projectIds as number[],
  isActive: isActive as boolean,
  timezone: 'Asia/Kolkata',
  department: department as string,
  designation: designation as string,
  lastLoginAt: lastLoginAt as string | null,
  // B-011's S-08 fields. Varied on purpose, the same way the row above is:
  // one person on a non-standard week, one with no skills, one who has never
  // changed their temporary password — a form seeded from seven identical
  // records proves nothing about the form.
  mobile: i % 3 === 0 ? null : `+91 90000 000${String(i).padStart(2, '0')}`,
  dateOfJoining: i % 4 === 0 ? null : `202${4 + (i % 3)}-0${1 + (i % 9)}-15`,
  location: i % 2 === 0 ? 'Pune' : 'Bengaluru',
  dailyCapacityHrs: i === 4 ? 6 : 8,
  // null = inherit the org week, which is everybody except the support lead,
  // whose rota runs Tuesday-to-Saturday.
  weeklyOff: (username as string) === 'priya' ? [7, 1] : null,
  skills: i % 3 === 0 ? [] : ['Java', 'React', 'MySQL'].slice(0, 1 + (i % 3)),
  projectRoles: {},
  projectAllocations: SEEDED_ALLOCATIONS[username as string] ?? {},
  projectMemberSince: Object.fromEntries(
    (projectIds as number[]).map((id) => [id, `2026-0${1 + (i % 6)}-1${(id % 9)}T09:00:00.000Z`]),
  ),
  // Karan has never logged in, so he is still on the password he was issued.
  mustChangePassword: lastLoginAt == null,
}));

/**
 * B-016 · the three all have a non-zero `ticketSeq`, which is what fixes their
 * `projectCode` — so the immutability refusal is exercisable out of the box.
 * `ARCH` is the fourth, deliberately: it is `CLOSED` and has issued nothing, so
 * the S-10 grid has a retired row to render and the code-editable path has a
 * project to exercise. It is excluded from `?isActive=true`, which is what the
 * five pickers send.
 */
const PROJECTS: Project[] = [
  { id: 1, projectCode: 'CRM', name: 'Client CRM Platform', description: 'The client-facing CRM, and the busiest project on the desk.', clientName: 'Acme Retail Ltd', projectManagerId: 2, colourTag: '#4F46E5', status: 'ACTIVE', startDate: '2026-01-05', endDate: '2026-12-18', autoAssignRule: 'LEAST_LOADED', ticketSeq: 347, mandatoryFields: ['MODULE', 'ESTIMATED_HRS'] },
  { id: 2, projectCode: 'PAY', name: 'Payments Gateway', description: null, clientName: 'Northwind Logistics', projectManagerId: 2, colourTag: '#F59E0B', status: 'ACTIVE', startDate: '2026-02-02', endDate: null, autoAssignRule: 'MANUAL', ticketSeq: 128, mandatoryFields: null },
  { id: 3, projectCode: 'WEB', name: 'Marketing Website', description: null, clientName: null, projectManagerId: 2, colourTag: '#06B6D4', status: 'ON_HOLD', startDate: null, endDate: null, autoAssignRule: 'MANUAL', ticketSeq: 64, mandatoryFields: [] },
  { id: 4, projectCode: 'ARCH', name: 'Archived Pilot', description: 'Retired. Kept because its tickets are still readable.', clientName: 'Oldco Industries', projectManagerId: 1, colourTag: '#8B5CF6', status: 'CLOSED', startDate: '2025-04-01', endDate: '2025-11-30', autoAssignRule: 'MANUAL', ticketSeq: 0, mandatoryFields: null },
];

/**
 * B-026 · shaped to exercise the cases rather than to be tidy.
 *
 * - **Acme is filled in** — every S-33 field populated, so the edit form has
 *   something to render on all four tabs and a round-trip test can prove nothing
 *   was dropped.
 * - **Northwind is sparse** — the state an imported client is really in, and the
 *   one where a form that confuses "absent" with "cleared" shows it.
 * - **Kestrel is a PROSPECT**, which is the whole reason `status` is stored: a
 *   fixture with only ACTIVE and INACTIVE cannot tell a screen that handles the
 *   third state from one that has never met it. It has **no primary contact**
 *   either, so the Contacts tab's B-028 warning is exercised.
 * - **Oldco is inactive** and stays that way — §4B.2's "never hides historical
 *   tickets".
 */
const CLIENTS: Client[] = [
  {
    id: 1, clientCode: 'ACME', name: 'Acme Retail Ltd', domain: 'acme.example',
    accountManagerId: 2, supportPlan: 'PREMIUM', timezone: 'Asia/Kolkata', status: 'ACTIVE',
    shortName: 'Acme', logoUrl: null, industry: 'Retail',
    primaryEmail: 'hello@acme.example', supportEmail: 'support@acme.example',
    phone: '+91 98200 11111',
    addressLine1: '14 Linking Road', addressLine2: 'Bandra West', city: 'Mumbai',
    state: 'Maharashtra', country: 'India', postalCode: '400050',
    contractStart: '2025-04-01', contractEnd: '2027-03-31',
    billingReference: 'PO-2025-0142', billingEmail: 'accounts@acme.example',
    notes: 'Escalates through their IT Director. Quarterly review every January.',
    tags: ['retail', 'strategic'], slaPolicyId: null,
  },
  {
    id: 2, clientCode: 'NORTH', name: 'Northwind Logistics', domain: 'northwind.example',
    accountManagerId: 2, supportPlan: 'STANDARD', timezone: 'Europe/London', status: 'ACTIVE',
  },
  {
    id: 3, clientCode: 'BLUE', name: 'Bluewave Media', domain: 'bluewave.example',
    accountManagerId: 1, supportPlan: 'STANDARD', timezone: 'America/New_York', status: 'ACTIVE',
  },
  {
    id: 4, clientCode: 'OLDCO', name: 'Oldco Industries', domain: 'oldco.example',
    accountManagerId: 1, supportPlan: 'BASIC', timezone: 'Asia/Kolkata', status: 'INACTIVE',
  },
  {
    id: 5, clientCode: 'KESTREL', name: 'Kestrel Analytics', domain: 'kestrel.example',
    accountManagerId: null, supportPlan: null, timezone: 'Asia/Kolkata', status: 'PROSPECT',
    industry: 'Technology', tags: ['pre-sales'],
  },
];

/**
 * B-027 · shaped to exercise the cases rather than to be tidy.
 *
 * - **Acme has a removed contact (Ravi Menon).** A fixture in which every
 *   contact is live cannot tell a grid that renders `isActive` from one that has
 *   never met a removed row, and it cannot exercise `?includeInactive=` at all —
 *   which is the parameter separating S-33's grid from C-021's picker.
 * - **Erin Walsh has no phone and no designation**, so the two nullable columns
 *   are rendered in both states.
 * - **Kestrel (client 5) has no contacts at all**, which is why its Contacts tab
 *   shows B-028's warning — `db.ts` already documents that client as the one
 *   PROSPECT without a primary. B-028 maps it to project 1 so the S-19 client
 *   dropdown can exercise the gate as well as the tab.
 * - **Ravi Menon is removed and not primary.** Worth stating because B-028's
 *   gate turns on the pair: a *primary* contact who has been removed must read
 *   as no primary at all, which is what `primaryContact` filtering on
 *   `isActive` is for.
 */
const CONTACTS: Contact[] = [
  { id: 1, clientId: 1, name: 'Sara Kapoor', designation: 'IT Director', email: 'sara@acme.example', phone: '+91 98200 11111', isPrimary: true, notificationOptIn: true, portalAccess: true, isActive: true },
  { id: 2, clientId: 1, name: 'Dev Patel', designation: 'Helpdesk Lead', email: 'dev@acme.example', phone: '+91 98200 22222', isPrimary: false, notificationOptIn: true, portalAccess: false, isActive: true },
  { id: 5, clientId: 1, name: 'Ravi Menon', designation: 'Ops Manager', email: 'ravi@acme.example', phone: '+91 98200 33333', isPrimary: false, notificationOptIn: false, portalAccess: false, isActive: false },
  { id: 3, clientId: 2, name: 'Tom Fletcher', designation: 'Head of Service', email: 'tom@northwind.example', phone: '+44 20 7946 0000', isPrimary: true, notificationOptIn: true, portalAccess: false, isActive: true },
  { id: 4, clientId: 3, name: 'Erin Walsh', designation: null, email: 'erin@bluewave.example', phone: '', isPrimary: true, notificationOptIn: false, portalAccess: false, isActive: true },
];

/**
 * B-025 · the project mappings.
 *
 * **`/clients?projectId=` was previously ignored by the mock**, so the §4B.2
 * client dropdown on the create form looked unfiltered in `npm run dev` and
 * would have narrowed on first contact with a real backend —
 * `CreateTicketPage.tsx` says so in its own comment. This table is what lets the
 * handler honour the parameter, so the mock and the server now narrow the same
 * way.
 *
 * Shaped to exercise the cases rather than to be tidy:
 *
 * - **Acme is on two projects** — S-32's Projects column must render that as one
 *   row carrying both, not as two rows.
 * - **CRM serves two clients** — a project with a single client cannot tell a
 *   dropdown that filters correctly from one that never had to, and the S-19
 *   create form switches between them.
 * - **Bluewave is on none** — the empty cell, which must read "None" rather than
 *   render blank.
 * - **B-028 · Kestrel is on project 1**, and it is the row that makes the S-19
 *   client dropdown testable at all. It is a **PROSPECT with no contacts**, so
 *   one mapping exercises both of B-028's findings on the one screen the rule
 *   is about: it must be *offered* (`?isActive=true` is `status <> 'INACTIVE'`,
 *   so prospects are in the list — the server was filtering `= 'ACTIVE'` and
 *   dropping them) and it must not be *selectable* (`hasPrimaryContact: false`).
 *   Before this row, every client any project mapped had a primary contact, so
 *   a picker that ignored the gate entirely would have passed every test.
 */
const CLIENT_PROJECTS: ClientProject[] = [
  { clientId: 1, projectId: 1, isDefault: true },
  { clientId: 1, projectId: 3, isDefault: false },
  { clientId: 2, projectId: 1, isDefault: false },
  { clientId: 2, projectId: 2, isDefault: true },
  { clientId: 4, projectId: 4, isDefault: true },
  { clientId: 5, projectId: 1, isDefault: false },
];

/**
 * The eleven B-002 seeds, plus one retired row.
 *
 * `Fax Request` is deactivated, exactly as `Transport` is in the module
 * fixture, and for the same reason: `/masters/task-types` deliberately returns
 * inactive rows, so a fixture in which every row is active cannot tell a screen
 * that filters correctly from one that never had to.
 *
 * The codes are B-002's, so the mock and the seeded database agree on the one
 * field a client is now told to key off.
 */
const TASK_TYPES: TaskType[] = [
  ['CHANGE_REQUEST', 'Change Request', 'git-pull-request', '#4F46E5', 'MEDIUM', 72, true],
  ['PRODUCTION_BUG', 'Production Bug', 'flame', '#BE185D', 'HIGH', 8, true],
  ['CLIENT_REQUEST', 'Client Request', 'message-square', '#06B6D4', 'MEDIUM', 48, true],
  ['FUTURE_RELEASE', 'Future Release', 'calendar', '#84CC16', 'LOW', 240, true],
  ['INTERNAL_BUG', 'Internal Bug', 'bug', '#9A3412', 'MEDIUM', 24, true],
  ['CLIENT_BUG', 'Client Bug', 'bug', '#BE185D', 'HIGH', 16, true],
  ['SERVER_ISSUE', 'Server Issue', 'server', '#9333EA', 'CRITICAL', 4, true],
  ['NETWORK_ISSUE', 'Network Issue', 'wifi', '#0891B2', 'HIGH', 4, true],
  ['BROWSER_ISSUE', 'Browser Issue', 'globe', '#F59E0B', 'LOW', 72, true],
  ['PERFORMANCE_ISSUE', 'Performance Issue', 'gauge', '#9A3412', 'HIGH', 24, true],
  ['OTHER', 'Other', 'circle-help', '#7C859C', 'LOW', 120, true],
  ['FAX_REQUEST', 'Fax Request', 'printer', '#7C859C', 'LOW', 240, false],
].map(([code, name, icon, colour, defaultLevel, defaultSlaHrs, isActive], i) => ({
  id: i + 1,
  code: code as string,
  name: name as string,
  icon: icon as string,
  colour: colour as string,
  defaultLevel: defaultLevel as Level,
  defaultSlaHrs: defaultSlaHrs as number,
  seq: (i + 1) * 10,
  isActive: isActive as boolean,
}));

/**
 * B-019 · the allow-list, and it covers one project out of four.
 *
 * **The three projects with no rows are the point.** No rows means every active
 * task type is allowed, not none — the state every project in the real system is
 * in, because the table did not exist before this task. A seed that gave all
 * four a full set would make that state untestable and would quietly encode the
 * opposite reading.
 *
 * PAY (id 2) is the restricted one. It allows two of the eleven, which is enough
 * to render a restricted project and few enough that "restricted" is obvious at
 * a glance.
 *
 * **No task type is seeded inactive**, though a retired-but-allowed type is this
 * screen's hardest case. Retiring one here would change `CreateTicketPage`'s
 * picker — it filters on `isActive` — and that is Stream C's screen; a masters
 * task should not move another stream's fixture to make its own case easier to
 * reach. `ProjectSettingsPage.test.tsx` deactivates one in the test that needs
 * it, which is where the cost belongs.
 */
const PROJECT_TASK_TYPES: ProjectTaskType[] = [
  { projectId: 2, taskTypeId: 2 },
  { projectId: 2, taskTypeId: 5 },
];

/**
 * §7.5's eight modules, plus one that has been retired.
 *
 * `Transport` is deactivated and is referenced by exactly one seeded ticket. It
 * is here because the endpoint deliberately returns inactive rows: a picker
 * offers only the active ones, while a grid still has to render the name of a
 * module some old ticket was raised against. A fixture with only active rows
 * cannot tell those two behaviours apart, so the bug ships.
 */
const MODULES: Module[] = [
  'Student', 'Admission', 'Fees', 'Examination',
  'Attendance', 'Library', 'Inventory', 'Parent App',
].map((name, i) => ({
  id: i + 1,
  code: name.toUpperCase().replace(/ /g, '_'),
  name,
  seq: (i + 1) * 10,
  isActive: true,
}));
MODULES.push({ id: 9, code: 'TRANSPORT', name: 'Transport', seq: 90, isActive: false });

/**
 * B-021 · S-12's four levels, matching what B-002 seeds into `priorities`.
 *
 * **The colours are corrected here.** The handler that this replaces returned
 * `#84CC16 / #F59E0B / #9A3412 / #BE185D`, which are not the blueprint's. §12.1
 * states the level chips exactly — Low `#10B981`, Medium `#3B82F6`, High
 * `#F59E0B`, Critical `#EF4444` — and that is what the migration seeds. The mock
 * has disagreed with the server on the one colour mapping the blueprint gives
 * rather than leaves to be designed, since D-001. Nothing caught it because
 * nothing had ever served this table: `LevelPicker` renders the frozen
 * `level-*` design tokens rather than the hex, so the wrong values reached a
 * screen and were never displayed.
 *
 * **`defaultSlaHrs` is deliberately *not* aligned to the seed's 72/24/8/4.**
 * Those figures are fixture values, not specification — and `SLA_POLICIES`
 * below states that its org-wide rows match this list, because on a real server
 * rung 3 and rung 4 have to agree wherever both exist. Changing them here
 * without changing those would break a stated invariant of the fixture to chase
 * a number the blueprint never gives.
 *
 * **`autoEscalates` was true on two rows and is now true on one.** The handler
 * this replaces flagged High *and* Critical. §6 auto-promotes a ticket crossing
 * its Planned Close Date *to* the flagged level, so two of them is not a
 * stronger signal — it is an ambiguous pointer, and which one won would have
 * been whatever order Stream D's scanner happened to read. The seed has only
 * ever flagged Critical; `PriorityService` now refuses to let the count be
 * anything but one, and the fixture has to be able to satisfy the rule it is
 * used to exercise.
 *
 * Every row is active. A retired level is created by the S-12 screen at runtime
 * rather than seeded, because `CreateTicketPage` and `TicketListPage` consume
 * the *default* list — which excludes retired rows — and a seeded retired level
 * would make those two screens look like they were filtering when they are not.
 */
/**
 * B-022 · S-15's templates, matching what `V20260815_1100` seeds.
 *
 * Written as a compact matrix and expanded below rather than as fifty literal
 * rows, because fifty literals is where a fixture stops being checkable against
 * the thing it mirrors. The shape is blueprint §11's table: an event, its
 * category, who it goes to, its in-app wording, and its subject and body when
 * the event has an email at all.
 *
 * **§11's popup and bell columns collapse into one `IN_APP` row**, per D-042 —
 * the bell renders the same title and body the toast does. An event ticked
 * bell-only in §11 (a reassignment away, an 80%-elapsed warning) still has an
 * `IN_APP` template here; what makes it bell-only is D-043's popup rule.
 *
 * **The bodies are shorter than the server's.** These exist to make the S-15
 * editor's behaviour visible — merge tags present, a long body scrolling, a
 * locked toggle — not to be the wording anything sends. The subjects *are* the
 * server's, because the grid renders them.
 *
 * `MAIL_DELIVERY_FAILED` has no email row, matching the migration: mailing
 * somebody about a mail that would not send is a loop whose best case is that it
 * also fails. No event has a `PUSH` row — §11 has no push column, and creating
 * one is what the S-15 create dialog is for.
 */
const TEMPLATE_MATRIX: Array<{
  event: string;
  category: NotificationCategory;
  to: string[];
  inApp: string | null;
  email: { subject: string; body: string } | null;
}> = [
  { event: 'TICKET_ASSIGNED', category: 'ASSIGNMENT', to: ['ASSIGNEE'],
    inApp: '{{ticket_id}} assigned to you — {{level}}. {{ticket_title}}. Due {{planned_close}}.',
    email: { subject: 'New ticket assigned to you — {{level}}',
      body: '<p>{{actor}} assigned <strong>{{ticket_id}}</strong> to you.</p><p>{{ticket_title}}</p><p>Level {{level}} · {{project}} · {{client}} · stage {{stage}} · planned close {{planned_close}}</p><p><a href="{{ticket_url}}">Open ticket</a></p>' } },
  { event: 'HANDOFF_RECEIVED', category: 'ASSIGNMENT', to: ['STAGE_OWNER'],
    inApp: '{{ticket_id}} handed to you at {{stage}} by {{actor}}. {{ticket_title}}.',
    email: { subject: 'Handed to you at {{stage}} by {{actor}}',
      body: '<p>{{actor}} moved <strong>{{ticket_id}}</strong> to <strong>{{stage}}</strong>, and you own that stage.</p><p>{{comment}}</p><p><a href="{{ticket_url}}">Open ticket</a></p>' } },
  { event: 'QA_FAILED_REWORK', category: 'ASSIGNMENT', to: ['ASSIGNEE', 'PROJECT_MANAGER'],
    inApp: '{{ticket_id}} failed QA and is back with you — iteration {{iteration}}.',
    email: { subject: 'QA failed — returned for rework',
      body: '<p>{{actor}} sent <strong>{{ticket_id}}</strong> back from QA. This is iteration {{iteration}}.</p><p>{{comment}}</p><p><a href="{{ticket_url}}">Open ticket</a></p>' } },
  { event: 'DEPLOYMENT_DONE_VERIFY', category: 'ASSIGNMENT', to: ['ASSIGNEE'],
    inApp: '{{ticket_id}} deployed to production by {{actor}} — please verify.',
    email: { subject: 'Deployed to production — please verify',
      body: '<p>{{actor}} deployed <strong>{{ticket_id}}</strong>.</p><p>{{comment}}</p><p><a href="{{ticket_url}}">Open ticket</a></p>' } },
  { event: 'TICKET_REASSIGNED_AWAY', category: 'ASSIGNMENT', to: ['PREVIOUS_ASSIGNEE'],
    inApp: '{{ticket_id}} is no longer yours — {{actor}} reassigned it to {{assignee}}.',
    email: null },
  { event: 'TICKET_REOPENED', category: 'ASSIGNMENT', to: ['ASSIGNEE', 'PROJECT_MANAGER'],
    inApp: '{{ticket_id}} reopened — cycle {{cycle}}. {{ticket_title}}.',
    email: { subject: 'Reopened — cycle {{cycle}}',
      body: '<p>{{actor}} reopened <strong>{{ticket_id}}</strong>. It is now on cycle {{cycle}} and assigned to {{assignee}}.</p><p><a href="{{ticket_url}}">Open ticket</a></p>' } },
  { event: 'NEW_UNASSIGNED_TICKET', category: 'ASSIGNMENT', to: ['PROJECT_MANAGER', 'SUPPORT_DESK'],
    inApp: '{{ticket_id}} raised on {{project}} with nobody assigned — {{level}}.',
    email: { subject: 'Raised with nobody assigned — {{level}}',
      body: '<p><strong>{{ticket_id}}</strong> was raised by {{actor}} on {{project}} and has no assignee.</p><p><a href="{{ticket_url}}">Open ticket</a></p>' } },

  { event: 'SLA_BREACHED', category: 'ESCALATION',
    to: ['REPORTING_MANAGER', 'PROJECT_MANAGER', 'ASSIGNEE'],
    inApp: '{{ticket_id}} is overdue by {{overdue_by}} — assigned to {{assignee}}.',
    email: { subject: 'Overdue by {{overdue_by}}',
      body: '<p><strong>{{ticket_id}}</strong> passed its planned close date of {{planned_close}} and is overdue by {{overdue_by}}.</p><p><a href="{{ticket_url}}">Open ticket</a></p>' } },
  { event: 'SLA_80_PERCENT_ELAPSED', category: 'ESCALATION', to: ['ASSIGNEE'],
    inApp: '{{ticket_id}} has used 80% of its SLA — due {{sla_due}}.',
    email: { subject: 'Due {{sla_due}} — 80% of the SLA has elapsed',
      body: '<p><strong>{{ticket_id}}</strong> has used 80% of its allowed time. It is due {{sla_due}}.</p><p><a href="{{ticket_url}}">Open ticket</a></p>' } },
  { event: 'STAGE_SLA_BREACHED', category: 'ESCALATION',
    to: ['STAGE_OWNER', 'PROJECT_MANAGER', 'REPORTING_MANAGER'],
    inApp: '{{ticket_id}} is stuck in {{stage}} past its stage SLA — {{overdue_by}}.',
    email: { subject: 'Stuck in {{stage}} past SLA',
      body: '<p><strong>{{ticket_id}}</strong> has been in <strong>{{stage}}</strong> for {{overdue_by}} longer than that stage allows.</p><p><a href="{{ticket_url}}">Open ticket</a></p>' } },
  { event: 'LEVEL_RAISED_CRITICAL', category: 'ESCALATION',
    to: ['ASSIGNEE', 'REPORTING_MANAGER', 'PROJECT_MANAGER'],
    inApp: '{{ticket_id}} escalated to CRITICAL by {{actor}}.',
    email: { subject: 'Escalated to CRITICAL',
      body: '<p>{{actor}} raised <strong>{{ticket_id}}</strong> to <strong>Critical</strong>.</p><p>{{comment}}</p><p><a href="{{ticket_url}}">Open ticket</a></p>' } },
  { event: 'ITERATION_LIMIT_REACHED', category: 'ESCALATION',
    to: ['PROJECT_MANAGER', 'REPORTING_MANAGER'],
    inApp: '{{ticket_id}} has reached iteration {{iteration}} — it keeps coming back.',
    email: { subject: 'Iteration {{iteration}} — repeated rework',
      body: '<p><strong>{{ticket_id}}</strong> has been through {{iteration}} iterations of the same stage.</p><p><a href="{{ticket_url}}">Open ticket</a></p>' } },
  { event: 'DEPLOYMENT_FAILED', category: 'ESCALATION',
    to: ['ASSIGNEE', 'PROJECT_MANAGER', 'REPORTING_MANAGER'],
    inApp: '{{ticket_id}} failed to deploy — {{actor}} reported it.',
    email: { subject: 'Deployment failed',
      body: '<p>{{actor}} reported a failed deployment on <strong>{{ticket_id}}</strong>.</p><p>{{comment}}</p><p><a href="{{ticket_url}}">Open ticket</a></p>' } },
  { event: 'CHAIN_VERIFICATION_FAILED', category: 'ESCALATION', to: ['ADMIN'],
    inApp: 'The nightly hash-chain verification failed. {{comment}}',
    email: { subject: 'Hash chain verification failed on {{org}}',
      body: '<p>The nightly verifier could not reproduce the hash chain over the append-only history.</p><p>{{comment}}</p>' } },

  { event: 'STATUS_REQUESTED', category: 'STATUS_REQUEST', to: ['ASSIGNEE'],
    inApp: '{{actor}} asked for a status update on {{ticket_id}}.',
    email: { subject: 'Status requested by {{actor}}',
      body: '<p>{{actor}} asked for a status update on <strong>{{ticket_id}}</strong>.</p><p>{{comment}}</p><p><a href="{{ticket_url}}">Reply on the ticket</a></p>' } },
  { event: 'STATUS_REQUEST_ANSWERED', category: 'STATUS_REQUEST', to: ['REQUESTER'],
    inApp: '{{actor}} answered your status request on {{ticket_id}}. {{comment}}',
    email: null },

  { event: 'MENTIONED', category: 'MENTION', to: ['MENTIONED_USER'],
    inApp: '{{actor}} mentioned you on {{ticket_id}}. {{comment}}',
    email: { subject: 'You were mentioned by {{actor}}',
      body: '<p>{{actor}} mentioned you on <strong>{{ticket_id}}</strong>.</p><p>{{comment}}</p><p><a href="{{ticket_url}}">Open ticket</a></p>' } },

  { event: 'TICKET_CLOSED', category: 'OTHER', to: ['REPORTER', 'WATCHERS'],
    inApp: '{{ticket_id}} was resolved and closed by {{actor}}.',
    email: { subject: 'Resolved and closed',
      body: '<p>{{actor}} closed <strong>{{ticket_id}}</strong>.</p><p>{{comment}}</p><p><a href="{{ticket_url}}">Open ticket</a></p><p>Replying to this mail adds a comment to the ticket.</p>' } },
  { event: 'COMMENT_ADDED', category: 'OTHER', to: ['ASSIGNEE', 'WATCHERS'],
    inApp: '{{actor}} commented on {{ticket_id}}. {{comment}}',
    email: { subject: 'New comment from {{actor}}',
      body: '<p>{{actor}} commented on <strong>{{ticket_id}}</strong>.</p><p>{{comment}}</p><p><a href="{{ticket_url}}">Open ticket</a></p>' } },
  { event: 'COMMENT_MARKED_CLIENT_VISIBLE', category: 'OTHER', to: ['CLIENT_CONTACT'],
    inApp: 'An update was published on {{ticket_id}}. {{comment}}',
    email: { subject: 'An update on your ticket',
      body: '<p>There is a new update on <strong>{{ticket_id}}</strong>.</p><p>{{comment}}</p><p><a href="{{ticket_url}}">Open ticket</a></p>' } },
  { event: 'ATTACHMENT_ADDED', category: 'OTHER', to: ['ASSIGNEE', 'WATCHERS'],
    inApp: '{{actor}} attached a file to {{ticket_id}}.',
    email: { subject: 'New attachment from {{actor}}',
      body: '<p>{{actor}} added an attachment to <strong>{{ticket_id}}</strong>.</p><p><a href="{{ticket_url}}">Open ticket</a></p>' } },
  { event: 'PRIORITY_CHANGED', category: 'OTHER', to: ['ASSIGNEE', 'PROJECT_MANAGER'],
    inApp: '{{ticket_id}} is now {{level}} — changed by {{actor}}.',
    email: { subject: 'Level changed to {{level}}',
      body: '<p>{{actor}} changed the level on <strong>{{ticket_id}}</strong> to <strong>{{level}}</strong>.</p><p>{{comment}}</p><p><a href="{{ticket_url}}">Open ticket</a></p>' } },
  { event: 'STALE_TICKET_NUDGE', category: 'OTHER', to: ['ASSIGNEE'],
    inApp: 'Nobody has touched {{ticket_id}} in a while. It is still yours.',
    email: { subject: 'No activity on this ticket for a while',
      body: '<p><strong>{{ticket_id}}</strong> has had no activity recently and is still assigned to you.</p><p><a href="{{ticket_url}}">Open ticket</a></p>' } },
  { event: 'MAIL_DELIVERY_FAILED', category: 'OTHER', to: ['ASSIGNEE'],
    inApp: 'A notification mail about {{ticket_id}} could not be delivered. {{comment}}',
    email: null },
  { event: 'EMAIL_ADDRESS_SUPPRESSED', category: 'OTHER', to: ['ADMIN'],
    inApp: 'An email address was suppressed by the provider. {{comment}}',
    email: { subject: 'An email address has been suppressed',
      body: '<p>The mail provider told us to stop writing to an address, so it has been suppressed.</p><p>{{comment}}</p>' } },
  { event: 'DAILY_DIGEST', category: 'OTHER', to: ['ALL_USERS'], inApp: null,
    email: { subject: 'Your open tickets',
      body: '<p>Good morning {{recipient}}.</p><p>Here is where your open tickets stand this morning.</p><p>{{comment}}</p>' } },
  { event: 'WEEKLY_MANAGER_SUMMARY', category: 'OTHER',
    to: ['REPORTING_MANAGER', 'PROJECT_MANAGER'], inApp: null,
    email: { subject: 'Team summary',
      body: '<p>Good morning {{recipient}}.</p><p>Here is how your team&rsquo;s week looks across {{org}}.</p><p>{{comment}}</p>' } },
];

/** The matrix flattened into rows, in the `(eventCode, channel)` order the
 *  server returns them. `EMAIL` sorts before `IN_APP` alphabetically, which is
 *  what the real `ORDER BY` produces and what the grid's tests assert. */
const NOTIFICATION_TEMPLATES: NotificationTemplateRow[] = TEMPLATE_MATRIX
  .flatMap((entry) => {
    const rows: Array<Omit<NotificationTemplateRow, 'id'>> = [];
    if (entry.email) {
      rows.push({
        eventCode: entry.event, category: entry.category, channel: 'EMAIL',
        recipients: [...entry.to], subjectTemplate: entry.email.subject,
        bodyTemplate: entry.email.body, isActive: true,
      });
    }
    if (entry.inApp) {
      rows.push({
        eventCode: entry.event, category: entry.category, channel: 'IN_APP',
        recipients: [...entry.to], subjectTemplate: null,
        bodyTemplate: entry.inApp, isActive: true,
      });
    }
    return rows;
  })
  .sort((a, b) => a.eventCode.localeCompare(b.eventCode)
    || a.channel.localeCompare(b.channel))
  .map((row, index) => ({ id: index + 1, ...row }));

const PRIORITIES: Priority[] = [
  { id: 1, level: 'LOW', name: 'Low', colour: '#10B981', defaultSlaHrs: 120, autoEscalates: false, seq: 10, isActive: true },
  { id: 2, level: 'MEDIUM', name: 'Medium', colour: '#3B82F6', defaultSlaHrs: 48, autoEscalates: false, seq: 20, isActive: true },
  { id: 3, level: 'HIGH', name: 'High', colour: '#F59E0B', defaultSlaHrs: 16, autoEscalates: false, seq: 30, isActive: true },
  { id: 4, level: 'CRITICAL', name: 'Critical', colour: '#EF4444', defaultSlaHrs: 4, autoEscalates: true, seq: 40, isActive: true },
];

/**
 * B-039 · the eight statuses B-003 seeds, with the categories V20260818_1720
 * backfilled.
 *
 * **The category mapping is the reason to read this list rather than skim it.**
 * NEW and REOPENED are TODO; ON_HOLD, AWAITING_INFO and REWORK are IN_PROGRESS.
 * All five carry `isOpen: true, isTerminal: false` — identical on both booleans,
 * three categories apart — which is why the migration added a column instead of
 * deriving one.
 *
 * RESOLVED is DONE while `isOpen` stays true: the category describes the work,
 * `isOpen` describes the ticket record, and the gap is deliberate.
 */
const STATUSES: Status[] = [
  { id: 1, code: 'NEW', name: 'New', category: 'TODO', colour: '#4F46E5', seq: 10, isOpen: true, isTerminal: false, isActive: true },
  { id: 2, code: 'IN_PROGRESS', name: 'In Progress', category: 'IN_PROGRESS', colour: '#3B82F6', seq: 20, isOpen: true, isTerminal: false, isActive: true },
  { id: 3, code: 'ON_HOLD', name: 'On Hold', category: 'IN_PROGRESS', colour: '#F59E0B', seq: 30, isOpen: true, isTerminal: false, isActive: true },
  { id: 4, code: 'AWAITING_INFO', name: 'Awaiting Info', category: 'IN_PROGRESS', colour: '#6B7280', seq: 40, isOpen: true, isTerminal: false, isActive: true },
  { id: 5, code: 'REWORK', name: 'Rework', category: 'IN_PROGRESS', colour: '#8B5CF6', seq: 50, isOpen: true, isTerminal: false, isActive: true },
  { id: 6, code: 'RESOLVED', name: 'Resolved', category: 'DONE', colour: '#14B8A6', seq: 60, isOpen: true, isTerminal: false, isActive: true },
  { id: 7, code: 'CLOSED', name: 'Closed', category: 'DONE', colour: '#10B981', seq: 70, isOpen: false, isTerminal: true, isActive: true },
  { id: 8, code: 'REOPENED', name: 'Reopened', category: 'TODO', colour: '#EF4444', seq: 80, isOpen: true, isTerminal: false, isActive: true },
];

/**
 * B-039 · the transition whitelist, transcribed from B-003's seed and B-008's
 * correction.
 *
 * **`SUPPORT`, never `SUPPORT_DESK`.** The seed shipped thirteen rows with the
 * wrong code and nothing failed — `role_code` has no foreign key, so the rows
 * simply matched no caller and the Support Desk could make no status move at
 * all. Getting it wrong here would reproduce that defect in the mock world,
 * where it would look like a screen bug.
 *
 * **G-3 is expressed as absence**: there is no `RESOLVED -> CLOSED` row for
 * DEVELOPER, QA or DEPLOYMENT, and that is the governance decision rather than
 * an omission. The same for `CLOSED -> REOPENED`.
 */
const WORKFLOW_TRANSITIONS: WorkflowTransitionRow[] = (() => {
  const ALL = ['ADMIN', 'PM', 'SUPPORT', 'DEVELOPER', 'QA', 'DEPLOYMENT'];
  const MANAGERS = ['ADMIN', 'PM', 'SUPPORT'];
  const spec: [StatusCode | null, StatusCode, string[], boolean, boolean][] = [
    [null, 'NEW', ALL, false, false],
    ['NEW', 'IN_PROGRESS', ALL, false, false],
    ['NEW', 'RESOLVED', MANAGERS, true, false],
    ['IN_PROGRESS', 'ON_HOLD', ALL, true, false],
    ['ON_HOLD', 'IN_PROGRESS', ALL, false, false],
    ['IN_PROGRESS', 'AWAITING_INFO', ALL, true, false],
    ['AWAITING_INFO', 'IN_PROGRESS', ALL, false, false],
    ['IN_PROGRESS', 'RESOLVED', ALL, false, true],
    ['RESOLVED', 'REWORK', ['ADMIN', 'PM', 'QA'], true, false],
    ['REWORK', 'IN_PROGRESS', ALL, false, false],
    ['REWORK', 'RESOLVED', ALL, false, true],
    ['RESOLVED', 'CLOSED', MANAGERS, false, false],
    ['CLOSED', 'REOPENED', MANAGERS, true, false],
    ['REOPENED', 'IN_PROGRESS', ALL, false, false],
  ];
  let id = 0;
  return spec.flatMap(([fromStatus, toStatus, rolesFor, requiresReason, requiresEffort]) =>
    rolesFor.map((roleCode) => ({
      id: ++id, fromStatus, toStatus, roleCode, requiresReason, requiresEffort, isActive: true,
    })),
  );
})();

/**
 * B-001's eighteen capabilities, transcribed from the same §2 matrix the
 * migration seeds — so the mock world and a real database render S-09
 * identically.
 *
 * `history.edit_delete` is here **and held by nobody**, which is the point of
 * including it: blueprint §2 says "Edit / delete history or ribbon — ❌ (nobody
 * can)", and the screen renders it disabled rather than omitting it. A fixture
 * without it cannot tell "disabled" and "absent" apart, so the bug ships.
 */
const PERMISSIONS: Permission[] = [
  ['resource.manage', 'Manage resources, roles, reporting manager', 'admin', 'Create/edit resources, roles and reporting-manager assignment.'],
  ['project.manage', 'Manage projects', 'admin', 'Create/edit projects; map resources to a project.'],
  ['audit.view', 'Audit log viewer', 'audit', 'View the audit log.'],
  ['history.edit_delete', 'Edit / delete history or ribbon', 'history', 'Granted to nobody — ticket history is append-only.'],
  ['history.view_team', 'View team member history', 'history', 'View ticket history belonging to other team members.'],
  ['master.write', 'Master data', 'master', 'Create/edit task types, SLA, workflow, holidays and other masters.'],
  ['reports.view', 'Reports section', 'reports', 'View the reports section, scope resolved per role.'],
  ['ticket.assign', 'Assign / reassign ticket', 'ticket', 'Assign or reassign a ticket to a resource.'],
  ['ticket.close', 'Close ticket', 'ticket', 'Close a ticket.'],
  ['ticket.create', 'Create ticket', 'ticket', 'Raise a new ticket.'],
  ['ticket.force_move', 'Force-move ribbon backwards', 'ticket', 'Force-move the ribbon backwards outside the normal flow.'],
  ['ticket.handoff', 'Hand off to next stage', 'ticket', 'Move a ticket forward along the ribbon.'],
  ['ticket.reopen', 'Reopen ticket', 'ticket', 'Reopen a closed ticket.'],
  ['ticket.rework', 'Send back for rework', 'ticket', 'Return a ticket to a prior stage for rework.'],
  ['ticket.skip_stage', 'Skip a stage', 'ticket', 'Skip a ribbon stage, with a mandatory reason.'],
  ['ticket.update_progress', 'Update status/effort on assigned ticket', 'ticket', 'Update status or log effort on an assigned ticket.'],
  ['ticket.view_all', 'View all tickets', 'ticket', 'View tickets beyond only those assigned to the caller.'],
  ['ticket.view_assigned', 'View only assigned tickets', 'ticket', 'View tickets assigned to the caller.'],
  // (category, code) — the order the server returns and the screen groups by.
].map(([code, name, category, description], i) => ({
  id: i + 1, code, name, category, description,
  isGrantable: code !== 'history.edit_delete',
}));

/** The six of blueprint §2, all `isSystem` and therefore all undeletable. */
const ROLES: Role[] = [
  ['ADMIN', 'Admin', 'Full system access: masters, roles, audit log, all tickets.'],
  ['DEPLOYMENT', 'Deployment', 'Deploys assigned tickets through the ribbon.'],
  ['DEVELOPER', 'Developer', 'Works assigned tickets through the ribbon.'],
  ['PM', 'PM', 'Owns projects; assigns, escalates and closes within them.'],
  ['QA', 'QA', 'Verifies assigned tickets through the ribbon.'],
  ['SUPPORT', 'Support Desk', 'Intake, assignment and closure within assigned projects.'],
].map(([code, name, description], i) => ({
  id: i + 1, code, name, description, isSystem: true, isActive: true,
}));

/**
 * The §2 grant matrix, one entry per role. Keyed by role code rather than id so
 * a reordered `ROLES` cannot silently reassign somebody's permissions.
 *
 * `history.edit_delete` appears in none of them, deliberately.
 */
const ROLE_GRANTS: Record<string, string[]> = {
  ADMIN: [
    'resource.manage', 'project.manage', 'master.write', 'audit.view', 'reports.view',
    'history.view_team', 'ticket.create', 'ticket.assign', 'ticket.handoff', 'ticket.rework',
    'ticket.skip_stage', 'ticket.force_move', 'ticket.view_all', 'ticket.update_progress',
    'ticket.close', 'ticket.reopen',
  ],
  PM: [
    'project.manage', 'master.write', 'reports.view', 'history.view_team', 'ticket.create',
    'ticket.assign', 'ticket.handoff', 'ticket.rework', 'ticket.skip_stage', 'ticket.view_all',
    'ticket.update_progress', 'ticket.close', 'ticket.reopen',
  ],
  SUPPORT: [
    'reports.view', 'ticket.create', 'ticket.assign', 'ticket.handoff', 'ticket.view_all',
    'ticket.update_progress', 'ticket.close', 'ticket.reopen',
  ],
  DEVELOPER: ['ticket.handoff', 'ticket.view_assigned', 'ticket.update_progress', 'reports.view'],
  QA: ['ticket.handoff', 'ticket.rework', 'ticket.view_assigned', 'ticket.update_progress', 'reports.view'],
  DEPLOYMENT: ['ticket.handoff', 'ticket.rework', 'ticket.view_assigned', 'ticket.update_progress', 'reports.view'],
};

/**
 * The SLA matrix, seeded so that **every rung of the ladder is reachable in
 * `npm run dev`** — a fixture where only the org-wide default ever answers
 * cannot tell a working resolution from one that skips straight to the bottom.
 *
 * The org-wide rows match `GET /masters/priorities`'s `defaultSlaHrs`, because
 * on the real server rung 3 and rung 4 must agree wherever both exist. CRM has
 * a tighter Production Bug policy (rung 1) and PAY a tighter Critical default
 * for every type (rung 2).
 */
const SLA_POLICIES: SlaPolicy[] = [
  { id: 1, projectId: null, taskTypeId: null, level: 'LOW', responseHrs: 24, resolutionHrs: 120, isActive: true },
  { id: 2, projectId: null, taskTypeId: null, level: 'MEDIUM', responseHrs: 8, resolutionHrs: 48, isActive: true },
  { id: 3, projectId: null, taskTypeId: null, level: 'HIGH', responseHrs: 4, resolutionHrs: 16, isActive: true },
  { id: 4, projectId: null, taskTypeId: null, level: 'CRITICAL', responseHrs: 1, resolutionHrs: 4, isActive: true },
  // Rung 1 — CRM's Production Bug (task type 2) is tighter than the org default.
  { id: 5, projectId: 1, taskTypeId: 2, level: 'HIGH', responseHrs: 2, resolutionHrs: 8, isActive: true },
  { id: 6, projectId: 1, taskTypeId: 2, level: 'CRITICAL', responseHrs: 0.5, resolutionHrs: 2, isActive: true },
  // Rung 2 — PAY tightens Critical for every task type.
  { id: 7, projectId: 2, taskTypeId: null, level: 'CRITICAL', responseHrs: 0.5, resolutionHrs: 3, isActive: true },
];

/** Standard Dev Flow — the 8 stages of blueprint §4A.9. */
const STAGES: Stage[] = [
  { stageCode: 'INTAKE', displayName: 'Intake', sequence: 1, ownerRole: 'SUPPORT', icon: 'inbox', stageSlaHrs: 2, isOptional: false, canReturnTo: [] },
  { stageCode: 'TRIAGE', displayName: 'Triage', sequence: 2, ownerRole: 'PM', icon: 'list-filter', stageSlaHrs: 4, isOptional: false, canReturnTo: ['INTAKE'] },
  { stageCode: 'DEVELOPMENT', displayName: 'Development', sequence: 3, ownerRole: 'DEVELOPER', icon: 'code', stageSlaHrs: 24, isOptional: false, canReturnTo: ['TRIAGE'] },
  { stageCode: 'QA', displayName: 'QA', sequence: 4, ownerRole: 'QA', icon: 'check-check', stageSlaHrs: 8, isOptional: false, canReturnTo: ['DEVELOPMENT'] },
  { stageCode: 'DEPLOYMENT', displayName: 'Deployment', sequence: 5, ownerRole: 'DEPLOYMENT', icon: 'rocket', stageSlaHrs: 4, isOptional: false, canReturnTo: ['DEVELOPMENT', 'QA'] },
  { stageCode: 'VERIFICATION', displayName: 'Verification', sequence: 6, ownerRole: 'DEVELOPER', icon: 'shield-check', stageSlaHrs: 4, isOptional: false, canReturnTo: ['DEPLOYMENT'] },
  { stageCode: 'SIGNOFF', displayName: 'Sign-off', sequence: 7, ownerRole: 'PM', icon: 'stamp', stageSlaHrs: 8, isOptional: false, canReturnTo: ['DEVELOPMENT', 'VERIFICATION'] },
  { stageCode: 'CLOSED', displayName: 'Closed', sequence: 8, ownerRole: 'PM', icon: 'archive', stageSlaHrs: null, isOptional: false, canReturnTo: [] },
];

/**
 * B-040 · the three templates and their stages, matching
 * `V20260807_1700__seed_workflow_templates_stages.sql` row for row.
 *
 * **`DEV` on Standard Dev Flow and `DEV` on Support Fast-Track are two rows, not
 * one.** That is the whole shape of tab 2 — `workflow_stages.template_id` is
 * `NOT NULL` — and a mock that shared them would let a screen pass that had
 * quietly assumed a global stage catalogue.
 *
 * `QA` carries usage counts above zero because it is the only way to reach the
 * frozen-code path from the screen. Every other stage is editable, which is the
 * ordinary case and the one the create flow lands in.
 */
const WORKFLOW_TEMPLATES: WorkflowTemplateRow[] = [
  { id: 1, name: 'Standard Dev Flow', isDefault: true, isActive: true, ticketCount: 47,
    description: 'All 8 stages. Production Bug, Change Request, Future Release. Blueprint §4A.9.' },
  { id: 2, name: 'Support Fast-Track', isDefault: false, isActive: true, ticketCount: 0,
    description: 'Intake -> Triage -> Development -> Sign-off -> Closed. Blueprint §4A.9.' },
  { id: 3, name: 'Infra Flow', isDefault: false, isActive: true, ticketCount: 0,
    description: 'Intake -> Triage -> Deployment -> Verification -> Closed. Blueprint §4A.9.' },
]

const TEMPLATE_STAGES: TemplateStage[] = [
  // Standard Dev Flow — §4A.1 verbatim.
  { id: 1, templateId: 1, seq: 10, stageCode: 'INTAKE', displayName: 'Intake', ownerRole: 'SUPPORT', slaHours: 2, isOptional: false, canReturnTo: [], icon: 'inbox', transitionCount: 0, openTicketCount: 0, isDeprecated: false, deprecatedAt: null },
  { id: 2, templateId: 1, seq: 20, stageCode: 'TRIAGE', displayName: 'Triage / Planning', ownerRole: 'PM', slaHours: 4, isOptional: false, canReturnTo: [], icon: 'list-checks', transitionCount: 0, openTicketCount: 0, isDeprecated: false, deprecatedAt: null },
  { id: 3, templateId: 1, seq: 30, stageCode: 'DEV', displayName: 'Development', ownerRole: 'DEVELOPER', slaHours: null, isOptional: false, canReturnTo: ['TRIAGE'], icon: 'code-2', transitionCount: 0, openTicketCount: 0, isDeprecated: false, deprecatedAt: null },
  { id: 4, templateId: 1, seq: 40, stageCode: 'QA', displayName: 'QA / Testing', ownerRole: 'QA', slaHours: 8, isOptional: false, canReturnTo: ['DEV'], icon: 'flask-conical', transitionCount: 41, openTicketCount: 3, isDeprecated: false, deprecatedAt: null },
  { id: 5, templateId: 1, seq: 50, stageCode: 'DEPLOY', displayName: 'Deployment', ownerRole: 'DEPLOYMENT', slaHours: 4, isOptional: false, canReturnTo: ['DEV'], icon: 'rocket', transitionCount: 0, openTicketCount: 0, isDeprecated: false, deprecatedAt: null },
  { id: 6, templateId: 1, seq: 60, stageCode: 'VERIFY', displayName: 'Verification', ownerRole: 'DEVELOPER', slaHours: 4, isOptional: false, canReturnTo: ['DEV'], icon: 'check-check', transitionCount: 0, openTicketCount: 0, isDeprecated: false, deprecatedAt: null },
  { id: 7, templateId: 1, seq: 70, stageCode: 'SIGNOFF', displayName: 'Sign-off', ownerRole: 'PM', slaHours: 8, isOptional: false, canReturnTo: ['DEV'], icon: 'clipboard-check', transitionCount: 0, openTicketCount: 0, isDeprecated: false, deprecatedAt: null },
  { id: 8, templateId: 1, seq: 80, stageCode: 'CLOSED', displayName: 'Closed', ownerRole: 'PM', slaHours: null, isOptional: false, canReturnTo: [], icon: 'circle-check-big', transitionCount: 0, openTicketCount: 0, isDeprecated: false, deprecatedAt: null },
  // Support Fast-Track.
  { id: 9, templateId: 2, seq: 10, stageCode: 'INTAKE', displayName: 'Intake', ownerRole: 'SUPPORT', slaHours: 2, isOptional: false, canReturnTo: [], icon: 'inbox', transitionCount: 0, openTicketCount: 0, isDeprecated: false, deprecatedAt: null },
  { id: 10, templateId: 2, seq: 20, stageCode: 'TRIAGE', displayName: 'Triage / Planning', ownerRole: 'PM', slaHours: 4, isOptional: false, canReturnTo: [], icon: 'list-checks', transitionCount: 0, openTicketCount: 0, isDeprecated: false, deprecatedAt: null },
  { id: 11, templateId: 2, seq: 30, stageCode: 'DEV', displayName: 'Development', ownerRole: 'DEVELOPER', slaHours: null, isOptional: false, canReturnTo: ['TRIAGE'], icon: 'code-2', transitionCount: 0, openTicketCount: 0, isDeprecated: false, deprecatedAt: null },
  { id: 12, templateId: 2, seq: 40, stageCode: 'SIGNOFF', displayName: 'Sign-off', ownerRole: 'SUPPORT', slaHours: 8, isOptional: false, canReturnTo: ['DEV'], icon: 'clipboard-check', transitionCount: 0, openTicketCount: 0, isDeprecated: false, deprecatedAt: null },
  { id: 13, templateId: 2, seq: 50, stageCode: 'CLOSED', displayName: 'Closed', ownerRole: 'SUPPORT', slaHours: null, isOptional: false, canReturnTo: [], icon: 'circle-check-big', transitionCount: 0, openTicketCount: 0, isDeprecated: false, deprecatedAt: null },
  // Infra Flow.
  { id: 14, templateId: 3, seq: 10, stageCode: 'INTAKE', displayName: 'Intake', ownerRole: 'SUPPORT', slaHours: 2, isOptional: false, canReturnTo: [], icon: 'inbox', transitionCount: 0, openTicketCount: 0, isDeprecated: false, deprecatedAt: null },
  { id: 15, templateId: 3, seq: 20, stageCode: 'TRIAGE', displayName: 'Triage / Planning', ownerRole: 'PM', slaHours: 4, isOptional: false, canReturnTo: [], icon: 'list-checks', transitionCount: 0, openTicketCount: 0, isDeprecated: false, deprecatedAt: null },
  { id: 16, templateId: 3, seq: 30, stageCode: 'DEPLOY', displayName: 'Deployment', ownerRole: 'DEPLOYMENT', slaHours: 4, isOptional: false, canReturnTo: ['TRIAGE'], icon: 'rocket', transitionCount: 0, openTicketCount: 0, isDeprecated: false, deprecatedAt: null },
  { id: 17, templateId: 3, seq: 40, stageCode: 'VERIFY', displayName: 'Verification', ownerRole: 'DEVELOPER', slaHours: 4, isOptional: false, canReturnTo: ['DEPLOY'], icon: 'check-check', transitionCount: 0, openTicketCount: 0, isDeprecated: false, deprecatedAt: null },
  { id: 18, templateId: 3, seq: 50, stageCode: 'CLOSED', displayName: 'Closed', ownerRole: 'PM', slaHours: null, isOptional: false, canReturnTo: [], icon: 'circle-check-big', transitionCount: 0, openTicketCount: 0, isDeprecated: false, deprecatedAt: null },
]

/**
 * B-041 - 4A.9's own seven pairs, matching
 * `V20260821_1015__workflow_template_mappings.sql` row for row.
 *
 * **All seven are (null, taskType), and that is the blueprint rather than a
 * simplification.** 4A.9 names the task types each template covers in
 * parentheses and says nothing about projects, so a project-scoped seed row
 * would invent a policy for a project the fixture knows nothing about.
 *
 * It also leaves the ladder genuinely exercised on first open: a pair with an
 * exact rule, a pair matching only on task type, and a pair matching nothing and
 * falling through to Standard Dev Flow's `isDefault`. A fixture that mapped
 * every pair explicitly would make `rung` constant, and a screen that rendered
 * `DEFAULT` wrongly would pass every test.
 *
 * Task type ids are B-002's: 1 CHANGE_REQUEST, 2 PRODUCTION_BUG,
 * 3 CLIENT_REQUEST, 4 FUTURE_RELEASE, 7 SERVER_ISSUE, 8 NETWORK_ISSUE,
 * 9 BROWSER_ISSUE.
 */
const TEMPLATE_MAPPINGS: TemplateMappingRow[] = [
  { id: 1, templateId: 1, projectId: null, taskTypeId: 2 },
  { id: 2, templateId: 1, projectId: null, taskTypeId: 1 },
  { id: 3, templateId: 1, projectId: null, taskTypeId: 4 },
  { id: 4, templateId: 2, projectId: null, taskTypeId: 3 },
  { id: 5, templateId: 2, projectId: null, taskTypeId: 9 },
  { id: 6, templateId: 3, projectId: null, taskTypeId: 7 },
  { id: 7, templateId: 3, projectId: null, taskTypeId: 8 },
]

// ── A-118 · onboarding fixtures ─────────────────────────────────────────────
/**
 * Two products, because one product cannot show what the module is for: a
 * client buys several and gets **one journey per product** (plan §1.1), and a
 * single-product fixture would let the accordion look finished while never
 * rendering a second strip.
 *
 * `BIOMETRIC` has no active template, which is the state the OB-04 picker has
 * to refuse — a purchase with nothing to instantiate would board a client into
 * nothing. A fixture where every product is bookable never exercises that.
 */
const OB_PRODUCTS: ObProduct[] = [
  { id: 1, code: 'ERP', name: 'ERP Suite', isActive: true, hasActiveTemplate: true, totalTatDays: 24 },
  { id: 2, code: 'BIOMETRIC', name: 'Biometric Attendance', isActive: true, hasActiveTemplate: false, totalTatDays: null },
  { id: 3, code: 'LMS', name: 'Learning Management', isActive: false, hasActiveTemplate: true, totalTatDays: 12 },
];

/**
 * Three clients, chosen so the three states OB-03 has to render are all
 * present on first open rather than reachable only by editing the fixture:
 *
 * - **Northwind** is past the gate and running — `rag` is a real colour, one
 *   service breached and one waiting on the client, so the RED roll-up and the
 *   paused clock are both visible.
 * - **Acme** is still `LOCKED`. Its `rag` is **null**, not GREEN: nothing is
 *   running to colour, and OB-03 renders that as "Prerequisites pending". A
 *   fixture that coloured it green would make the null case unreachable and
 *   the empty state untested.
 * - **Contoso** is `LIVE`, so the go-live flip has something to have produced.
 *
 * Northwind buys two products and holds the second behind the first, which is
 * the service-level dependency of plan §5.5 — `heldByJourneyId` set while
 * `gateStatus` is already OPEN, the combination most likely to be modelled
 * wrongly as one field.
 */
const OB_CLIENTS: ObClient[] = [
  {
    id: 1, name: 'Northwind Technologies Pvt Ltd', description: 'Mid-market ERP rollout across three campuses.',
    onboardingDate: '2026-07-14', pan: 'AABCN1234M', address: 'Baner, Pune 411045',
    licenseType: 'Subscription', salesPersonId: 5, status: 'ONBOARDING', liveAt: null,
    hasPortalLogin: true,
    contacts: [
      { id: 1, name: 'Meena Raghavan', designation: 'IT Head', email: 'meena@northwind.example', phone: '+91 98200 11223', whatsappOptIn: true, isPrimary: true },
      { id: 2, name: 'Sanjay Bose', designation: 'Finance Lead', email: 'sanjay@northwind.example', phone: null, whatsappOptIn: false, isPrimary: false },
    ],
    applications: [
      { id: 1, productId: 1, licenseType: 'Subscription', units: 250, licenseStart: '2026-08-01', licenseEnd: '2027-07-31' },
      { id: 2, productId: 2, licenseType: 'Perpetual', units: 8, licenseStart: '2026-08-01', licenseEnd: null },
    ],
    requirements: ['Single sign-on against their Azure AD', 'Data migration from Tally for FY25-26'],
    journeys: [
      {
        id: 1, productId: 1, gateStatus: 'OPEN', heldByJourneyId: null,
        steps: [
          { id: 1, sequence: 1, name: 'Kickoff & Requirement Sign-off', status: 'DONE', tatDays: 3, usedHours: 19, dependsOnStepId: null },
          { id: 2, sequence: 2, name: 'Environment Provisioning', status: 'DONE', tatDays: 4, usedHours: 26.5, dependsOnStepId: 1 },
          { id: 3, sequence: 3, name: 'Data Migration', status: 'BLOCKED', tatDays: 8, usedHours: 71, dependsOnStepId: 2 },
          { id: 4, sequence: 4, name: 'User Training', status: 'WAITING_ON_CLIENT', tatDays: 5, usedHours: 12, dependsOnStepId: null },
          { id: 5, sequence: 5, name: 'Go-live Readiness', status: 'PENDING', tatDays: 4, usedHours: 0, dependsOnStepId: 3 },
        ],
      },
      {
        // Held behind the ERP journey — bought, instantiated, past the gate,
        // and still not started. Plan §5.5.
        id: 2, productId: 2, gateStatus: 'OPEN', heldByJourneyId: 1,
        steps: [
          { id: 6, sequence: 1, name: 'Device Rollout', status: 'PENDING', tatDays: 6, usedHours: 0, dependsOnStepId: null },
          { id: 7, sequence: 2, name: 'Attendance Policy Mapping', status: 'PENDING', tatDays: 3, usedHours: 0, dependsOnStepId: 6 },
        ],
      },
    ],
    createdById: 5, createdAt: iso('2026-07-14T09:20:00'),
  },
  {
    id: 2, name: 'Acme Private Limited', description: null,
    onboardingDate: '2026-08-28', pan: 'AAACA9876Q', address: 'Andheri East, Mumbai 400069',
    licenseType: 'Subscription', salesPersonId: 5, status: 'ONBOARDING', liveAt: null,
    hasPortalLogin: false,
    contacts: [
      { id: 3, name: 'Priya Nair', designation: 'Operations Manager', email: 'priya@acme.example', phone: '+91 99300 44556', whatsappOptIn: true, isPrimary: true },
    ],
    applications: [
      { id: 3, productId: 1, licenseType: 'Subscription', units: 40, licenseStart: '2026-09-01', licenseEnd: '2027-08-31' },
    ],
    requirements: [],
    journeys: [
      {
        id: 3, productId: 1, gateStatus: 'LOCKED', heldByJourneyId: null,
        steps: [
          { id: 8, sequence: 1, name: 'Kickoff & Requirement Sign-off', status: 'PENDING', tatDays: 3, usedHours: 0, dependsOnStepId: null },
          { id: 9, sequence: 2, name: 'Environment Provisioning', status: 'PENDING', tatDays: 4, usedHours: 0, dependsOnStepId: 8 },
          { id: 10, sequence: 3, name: 'Data Migration', status: 'PENDING', tatDays: 8, usedHours: 0, dependsOnStepId: 9 },
          { id: 11, sequence: 4, name: 'User Training', status: 'PENDING', tatDays: 5, usedHours: 0, dependsOnStepId: null },
          { id: 12, sequence: 5, name: 'Go-live Readiness', status: 'PENDING', tatDays: 4, usedHours: 0, dependsOnStepId: 10 },
        ],
      },
    ],
    createdById: 5, createdAt: iso('2026-08-28T14:05:00'),
  },
  {
    id: 3, name: 'Contoso Education Trust', description: 'Completed rollout, retained for renewals.',
    onboardingDate: '2026-04-02', pan: 'AAECC4567P', address: 'Salt Lake, Kolkata 700091',
    licenseType: 'Perpetual', salesPersonId: 5, status: 'LIVE', liveAt: iso('2026-06-19T11:40:00'),
    hasPortalLogin: true,
    contacts: [
      { id: 4, name: 'Arjun Sen', designation: 'Registrar', email: 'arjun@contoso.example', phone: '+91 98310 77889', whatsappOptIn: false, isPrimary: true },
    ],
    applications: [
      { id: 4, productId: 1, licenseType: 'Perpetual', units: 120, licenseStart: '2026-04-15', licenseEnd: null },
    ],
    requirements: ['Bulk student import from their existing MIS'],
    journeys: [
      {
        id: 4, productId: 1, gateStatus: 'OPEN', heldByJourneyId: null,
        steps: [
          { id: 13, sequence: 1, name: 'Kickoff & Requirement Sign-off', status: 'DONE', tatDays: 3, usedHours: 17, dependsOnStepId: null },
          { id: 14, sequence: 2, name: 'Environment Provisioning', status: 'DONE', tatDays: 4, usedHours: 22, dependsOnStepId: 13 },
          { id: 15, sequence: 3, name: 'Data Migration', status: 'DONE', tatDays: 8, usedHours: 54, dependsOnStepId: 14 },
          { id: 16, sequence: 4, name: 'User Training', status: 'DONE', tatDays: 5, usedHours: 31, dependsOnStepId: null },
          { id: 17, sequence: 5, name: 'Go-live Readiness', status: 'DONE', tatDays: 4, usedHours: 25, dependsOnStepId: 15 },
        ],
      },
    ],
    createdById: 5, createdAt: iso('2026-04-02T10:00:00'),
  },
];

export function createDb(): Db {
  // Before anything reads `pick` or `int` — see `rewindFixtureRandom`. Without
  // this, two calls to this function produce different fixtures.
  rewindFixtureRandom();
  const db: Db = {
    users: structuredClone(USERS),
    projects: structuredClone(PROJECTS),
    clients: structuredClone(CLIENTS),
    contacts: structuredClone(CONTACTS),
    clientProjects: structuredClone(CLIENT_PROJECTS),
    taskTypes: structuredClone(TASK_TYPES),
    modules: structuredClone(MODULES),
    priorities: structuredClone(PRIORITIES),
    statuses: structuredClone(STATUSES),
    workflowTransitions: structuredClone(WORKFLOW_TRANSITIONS),
    workflowTemplates: structuredClone(WORKFLOW_TEMPLATES),
    templateStages: structuredClone(TEMPLATE_STAGES),
    templateMappings: structuredClone(TEMPLATE_MAPPINGS),
    permissions: structuredClone(PERMISSIONS),
    roles: structuredClone(ROLES),
    notificationTemplates: structuredClone(NOTIFICATION_TEMPLATES),
    roleGrants: Object.fromEntries(
      ROLES.map((role) => [role.id, [...(ROLE_GRANTS[role.code] ?? [])]]),
    ),
    stages: structuredClone(STAGES),
    slaPolicies: structuredClone(SLA_POLICIES),
    projectTaskTypes: structuredClone(PROJECT_TASK_TYPES),
    pushSubscriptions: [],
    tickets: [], cycles: [], transitions: [], effortLogs: [], history: [],
    comments: [], attachments: [], ticketLinks: [], notifications: [], notificationPreferences: [], emailLog: [],
    chatThreads: [], chatMessages: [], chatAttachments: [], statusRequests: [],
    timesheetApprovals: [],
    currentUserId: 3, // Ravi — a Developer, so scoping is visible by default
    seq: {},
    twoFactor: {}, // opt-in, so nobody starts enrolled
    // C-027 · §4B.4's published defaults, which is also what the migration
    // seeds — 10 MB per file, 50 MB and 20 files per ticket.
    attachmentLimits: {
      maxFileBytes: 10 * 1024 * 1024,
      maxTicketBytes: 50 * 1024 * 1024,
      maxFiles: 20,
      ceilingBytes: 10 * 1024 * 1024,
    },
    // B-033 · nobody has saved a mapping yet, which is the honest starting state.
    mappingPresets: {},
    // B-035 · and nobody has committed one either.
    importBatches: {},
    // A-065 · nor has anybody scheduled a report. The empty state is the one
    // most users meet first, so it is what the mock starts in.
    reportSchedules: [],
    // A-118 · the onboarding module. See OB_PRODUCTS / OB_CLIENTS.
    obProducts: structuredClone(OB_PRODUCTS),
    obClients: structuredClone(OB_CLIENTS),
    calendar: {
      week: {
        weeklyOff: [6, 7],
        workDayStart: '09:30:00',
        workDayEnd: '18:30:00',
        timezone: 'Asia/Kolkata',
      },
      holidays: [
        { id: 1, date: '2026-08-15', name: 'Independence Day', projectId: null, isRecurring: true, isActive: true },
        { id: 2, date: '2026-10-02', name: 'Gandhi Jayanti', projectId: null, isRecurring: true, isActive: true },
        { id: 3, date: '2026-11-08', name: 'Diwali', projectId: null, isRecurring: false, isActive: true },
      ],
      leaves: [
        { id: 1, userId: 4, startDate: '2026-08-24', endDate: '2026-08-28', leaveType: 'PLANNED', isHalfDay: false, status: 'APPROVED', reason: null },
        { id: 2, userId: 5, startDate: '2026-09-03', endDate: '2026-09-03', leaveType: 'SICK', isHalfDay: true, status: 'APPROVED', reason: null },
      ],
    },
  };
  seedWalkthrough(db);
  seedFiller(db);
  seedImportBatches(db);
  return db;
}

/**
 * B-037 · two finished imports, so the history panel has something to be.
 *
 * The ids are taken from the same sequence every other batch uses, so a run
 * committed during a session lands *after* these rather than colliding with
 * them — the panel is newest-first, and a seeded row appearing above a run the
 * user just started would be the one thing this fixture must not do.
 *
 * The two are deliberately different runs:
 *
 *   - **#1 has been reversed already.** It is what the disabled Reverse button
 *     and the "Reversed — 40 deleted, 2 kept" line render from, and it carries a
 *     non-zero `retainedRows`, because a partial reversal is the outcome most
 *     likely to be got wrong and least likely to be seen by hand.
 *   - **#2 completed with rejections and is reversible.** It is the one a
 *     reversal is exercised against, and it has an error report, so the panel's
 *     rejected-count download is reachable without committing anything.
 *
 * Neither is QUEUED or RUNNING: those belong to a batch the poll is advancing,
 * and one sitting here permanently would be a progress bar that never moves.
 *
 * **B-038 adds a third, under `RESOURCE`.** The panel filters on `entity`, and a
 * fixture with only CLIENT runs cannot tell a working filter from a broken one:
 * both render the same empty resource history and the same two client rows. It
 * is reversible and has no error report, so the resource wizard opens on a run
 * somebody can actually press Reverse on.
 */
function seedImportBatches(db: Db) {
  const rows: ImportBatchRow[] = [
    {
      batchId: nextId(db, 'importBatch'),
      entity: 'CLIENT',
      fileName: 'clients-q1-onboarding.xlsx',
      status: 'COMPLETED',
      processed: 44,
      total: 44,
      created: 42,
      updated: 2,
      rejected: 0,
      errorReportUrl: null,
      startedAt: '2026-08-11T06:14:00.000Z',
      importedBy: 1,
      importedByName: 'Anita Desai',
      reversedAt: '2026-08-11T07:02:00.000Z',
      reversedRows: 40,
      // Two of the forty-two had tickets raised against them in the 48 minutes
      // between the import and the reversal, so they were kept.
      retainedRows: 2,
      reversible: false,
    },
    {
      batchId: nextId(db, 'importBatch'),
      entity: 'CLIENT',
      fileName: 'clients-august.xlsx',
      status: 'COMPLETED',
      processed: 31,
      total: 31,
      created: 24,
      updated: 4,
      rejected: 3,
      errorReportUrl: '/import-batches/2/error-report',
      startedAt: '2026-08-17T11:48:00.000Z',
      importedBy: 1,
      importedByName: 'Anita Desai',
      reversedAt: null,
      reversedRows: 0,
      retainedRows: 0,
      reversible: true,
    },
    {
      batchId: nextId(db, 'importBatch'),
      entity: 'RESOURCE',
      fileName: 'joiners-august.xlsx',
      status: 'COMPLETED',
      processed: 18,
      total: 18,
      created: 16,
      updated: 2,
      rejected: 0,
      errorReportUrl: null,
      startedAt: '2026-08-18T05:30:00.000Z',
      importedBy: 1,
      importedByName: 'Anita Desai',
      reversedAt: null,
      reversedRows: 0,
      retainedRows: 0,
      reversible: true,
    },
  ];
  for (const row of rows) {
    db.importBatches[row.batchId] = row;
  }
}

/**
 * Blueprint §14 walkthrough A, to the hour.
 *
 * This is Stream C's exit criterion — the Journey tab for this ticket must
 * reconcile to **38.0 h across 5 resources and 3 iterations**, cycle 1 sealing
 * at 24.5 h over 2 iterations. Getting the fixture right now means the ribbon
 * and the roll-up are built against the number they will be judged on, rather
 * than against something plausible.
 */
function seedWalkthrough(db: Db) {
  const T = 'CRM-26-00347';
  const WALKTHROUGH_TITLE = 'Checkout fails with 500 on saved-card payment';
  const [ANITA, MEERA, RAVI, ANIL, KARAN, PRIYA] = [1, 2, 3, 4, 5, 6];
  void ANITA;

  db.tickets.push({
    ticketId: T,
    title: WALKTHROUGH_TITLE,
    description:
      'Acme report that returning customers paying with a saved card get a 500 at the ' +
      'final step. Reproduced on production with two accounts. Guest checkout is fine.',
    projectId: 1, clientId: 1, clientContactId: 1, isClientRaised: true,
    taskTypeId: 2,
    moduleId: 3, // Fees
    screenName: 'Checkout — payment',
    feature: 'Saved-card payment',
    stepsToGenerate:
      '<ol><li>Sign in as a returning customer with a saved card.</li>' +
      '<li>Add any item to the basket and go to checkout.</li>' +
      '<li>Choose the saved card and press <strong>Pay</strong>.</li></ol>' +
      '<p>Expected: order confirmation. Actual: HTTP 500.</p>',
    level: 'CRITICAL', originalLevel: 'HIGH', status: 'CLOSED',
    currentStageCode: 'CLOSED', assigneeId: MEERA, reportedById: PRIYA,
    cycleNo: 2, iterationNo: 1, reopenCount: 1,
    plannedCloseDate: iso('2026-08-13T12:00:00'), actualCloseDate: iso('2026-08-14T16:30:00'),
    isDelayed: true, delayedSince: iso('2026-08-13T00:15:00'),
    estimatedHrs: 16, pctComplete: 100,
    // Meera and Anil, per the S-20 wireframe's "Watchers  Meera, Anil".
    watcherIds: [MEERA, ANIL],
    createdAt: iso('2026-08-03T09:12:00'), updatedAt: iso('2026-08-14T16:30:00'), version: 14,
  });

  db.cycles.push(
    { ticketId: T, cycleNo: 1, isSealed: true, startedAt: iso('2026-08-03T09:12:00'), closedAt: iso('2026-08-07T17:40:00'), reason: null },
    { ticketId: T, cycleNo: 2, isSealed: true, startedAt: iso('2026-08-08T10:05:00'), closedAt: iso('2026-08-14T16:30:00'), reason: 'Client reports recurrence on two further accounts' },
  );

  // stage, owner, iteration, entered, exited, action, effort
  type Hop = [string, number, number, string, string | null, string, number];
  const cycle1: Hop[] = [
    ['INTAKE',       PRIYA, 1, '2026-08-03T09:12:00', '2026-08-03T10:30:00', 'FORWARD', 0.5],
    ['TRIAGE',       MEERA, 1, '2026-08-03T10:30:00', '2026-08-03T12:00:00', 'FORWARD', 1.0],
    ['DEVELOPMENT',  RAVI,  1, '2026-08-03T12:00:00', '2026-08-05T11:00:00', 'FORWARD', 9.0],
    ['QA',           ANIL,  1, '2026-08-05T11:00:00', '2026-08-05T16:20:00', 'VERIFY_FAILED', 3.5],
    ['DEVELOPMENT',  RAVI,  2, '2026-08-05T16:20:00', '2026-08-06T14:00:00', 'FORWARD', 5.5],
    ['QA',           ANIL,  2, '2026-08-06T14:00:00', '2026-08-06T16:30:00', 'FORWARD', 2.0],
    ['DEPLOYMENT',   KARAN, 2, '2026-08-06T16:30:00', '2026-08-07T11:00:00', 'FORWARD', 1.5],
    ['VERIFICATION', RAVI,  2, '2026-08-07T11:00:00', '2026-08-07T15:00:00', 'FORWARD', 1.0],
    ['SIGNOFF',      MEERA, 2, '2026-08-07T15:00:00', '2026-08-07T17:40:00', 'FORWARD', 0.5],
  ];
  const cycle2: Hop[] = [
    ['TRIAGE',       MEERA, 1, '2026-08-08T10:05:00', '2026-08-08T11:00:00', 'FORWARD', 0.5],
    ['DEVELOPMENT',  RAVI,  1, '2026-08-08T11:00:00', '2026-08-12T16:00:00', 'FORWARD', 7.0],
    ['QA',           ANIL,  1, '2026-08-12T16:00:00', '2026-08-13T14:00:00', 'FORWARD', 3.0],
    ['DEPLOYMENT',   KARAN, 1, '2026-08-13T14:00:00', '2026-08-14T10:00:00', 'FORWARD', 1.5],
    ['VERIFICATION', RAVI,  1, '2026-08-14T10:00:00', '2026-08-14T14:00:00', 'FORWARD', 1.0],
    ['SIGNOFF',      MEERA, 1, '2026-08-14T14:00:00', '2026-08-14T16:30:00', 'FORWARD', 0.5],
  ];

  const addHops = (hops: Hop[], cycleNo: number) => {
    for (const [stageCode, ownerId, iterationNo, entered, exited, action, hours] of hops) {
      const stage = db.stages.find((s) => s.stageCode === stageCode)!;
      const enteredAt = iso(entered);
      const exitedAt = exited ? iso(exited) : null;
      db.transitions.push({
        id: nextId(db, 'transition'), ticketId: T, cycleNo, iterationNo,
        stageCode, ownerId, ownerRole: stage.ownerRole,
        enteredAt, exitedAt,
        durationMins: exitedAt
          ? Math.round((Date.parse(exitedAt) - Date.parse(enteredAt)) / 60000)
          : null,
        action,
        note: action === 'VERIFY_FAILED'
          ? 'Three defects: saved-card token not refreshed, no retry on gateway timeout, error swallowed in the controller'
          : null,
        skipReason: null,
      });
      if (hours > 0) {
        db.effortLogs.push({
          id: nextId(db, 'effort'), ticketId: T, userId: ownerId, hours,
          workDate: entered.slice(0, 10), note: null,
          stageCode, cycleNo, iterationNo,
          isCorrection: false, correctsEntryId: null, createdAt: exitedAt ?? enteredAt,
        });
      }
      db.history.push({
        id: nextId(db, 'history'), ticketId: T,
        action: action === 'VERIFY_FAILED' ? 'REWORK' : 'STAGE_ADVANCED',
        actorId: ownerId, actorType: 'USER',
        fieldName: 'currentStageCode', oldValue: stageCode, newValue: null,
        note: null, stageCode, cycleNo, iterationNo,
        isCorrection: false, correctsEntryId: null,
        entryHash: `sha256:${(nextId(db, 'hash') * 2654435761).toString(16).padStart(16, '0')}`,
        createdAt: exitedAt ?? enteredAt,
      });
    }
  };
  addHops(cycle1, 1);
  addHops(cycle2, 2);

  // The auto-escalation at step 11 — actorType SYSTEM, and originalLevel intact.
  db.history.push({
    id: nextId(db, 'history'), ticketId: T, action: 'LEVEL_CHANGED',
    actorId: null, actorType: 'SYSTEM',
    fieldName: 'level', oldValue: 'HIGH', newValue: 'CRITICAL',
    note: 'SLA scanner: planned close date passed', stageCode: 'DEVELOPMENT',
    cycleNo: 2, iterationNo: 1, isCorrection: false, correctsEntryId: null,
    entryHash: 'sha256:00000000000000ff', createdAt: iso('2026-08-13T00:15:00'),
  });

  db.comments.push(
    {
      id: nextId(db, 'comment'), ticketId: T,
      body: 'Reproduced on prod with two Acme accounts. Attaching the gateway response.',
      originalBody: null, authorId: PRIYA, isClientVisible: false, isEdited: false,
      isDeleted: false, stageCode: 'INTAKE', cycleNo: 1, iterationNo: 1,
      mentionIds: [], createdAt: iso('2026-08-03T09:40:00'),
    },
    {
      id: nextId(db, 'comment'), ticketId: T,
      body: 'Token refresh was missing on the saved-card path. @Anil Shah please retest both flows.',
      originalBody: null, authorId: RAVI, isClientVisible: false, isEdited: false,
      isDeleted: false, stageCode: 'DEVELOPMENT', cycleNo: 1, iterationNo: 2,
      mentionIds: [ANIL], createdAt: iso('2026-08-06T13:50:00'),
    },
    {
      id: nextId(db, 'comment'), ticketId: T,
      body: 'Fix is live. Apologies for the disruption — we have added monitoring on this path.',
      originalBody: null, authorId: MEERA, isClientVisible: true, isEdited: false,
      isDeleted: false, stageCode: 'SIGNOFF', cycleNo: 2, iterationNo: 1,
      mentionIds: [], createdAt: iso('2026-08-14T16:25:00'),
    },
  );

  db.attachments.push({
    id: nextId(db, 'attachment'), ticketId: T, fileName: 'gateway-500.png',
    contentType: 'image/png', sizeBytes: 184_320, scanStatus: 'CLEAN',
    isClientVisible: false, isDeleted: false, uploadedById: PRIYA,
    stageCode: 'INTAKE', cycleNo: 1, createdAt: iso('2026-08-03T09:41:00'),
  });

  db.emailLog.push(
    { id: nextId(db, 'email'), ticketId: T, toAddress: 'ravi@edunext.example', subject: `[${T}] Handed to you at Development by Meera Iyer`, eventKey: 'TICKET_HANDED_OFF', status: 'SENT', retryCount: 0, nextAttemptAt: null, providerMessageId: 'msg-8841', sentAt: iso('2026-08-03T12:00:30') },
    { id: nextId(db, 'email'), ticketId: T, toAddress: 'sara@acme.example', subject: `[${T}] Update on your reported issue`, eventKey: 'CLIENT_UPDATE', status: 'BOUNCED', retryCount: 3, nextAttemptAt: null, providerMessageId: null, sentAt: null },
    { id: nextId(db, 'email'), ticketId: T, toAddress: 'meera@edunext.example', subject: `[${T}] SLA breached — escalated to Critical`, eventKey: 'SLA_BREACH', status: 'SENT', retryCount: 0, nextAttemptAt: null, providerMessageId: 'msg-9102', sentAt: iso('2026-08-13T00:15:20') },
  );

  const thread: ChatThread = {
    id: nextId(db, 'thread'), kind: 'TICKET', title: T, ticketId: T,
    participantIds: [MEERA, RAVI, ANIL], lastMessageAt: iso('2026-08-13T09:35:00'),
  };
  db.chatThreads.push(thread);
  const askedAndAnswered = nextId(db, 'message');
  const theAnswer = nextId(db, 'message');
  const stillWaiting = nextId(db, 'message');
  db.chatMessages.push(
    { id: askedAndAnswered, threadId: thread.id, body: 'Please share the current status and expected closure.', authorId: MEERA, kind: 'STATUS_REQUEST', isEdited: false, isDeleted: false, readBy: [RAVI], createdAt: iso('2026-08-13T09:20:00') },
    { id: theAnswer, threadId: thread.id, body: 'Root cause found — token refresh again, different code path. Fix by EOD.', authorId: RAVI, kind: 'TEXT', isEdited: false, isDeleted: false, readBy: [MEERA], createdAt: iso('2026-08-13T09:35:00') },
    { id: stillWaiting, threadId: thread.id, body: 'Any movement on the saved-card path? The client is asking.', authorId: MEERA, kind: 'STATUS_REQUEST', isEdited: false, isDeleted: false, readBy: [], createdAt: iso('2026-08-07T17:40:00') },
  );

  // D-055 / D-056. One answered and one still open, so a client can render both
  // the badge and a filled-in response time without inventing either.
  db.statusRequests.push(
    {
      id: nextId(db, 'statusRequest'), ticketId: T, ticketTitle: WALKTHROUGH_TITLE,
      threadId: thread.id, requestMessageId: askedAndAnswered,
      requestedById: MEERA, askedOfId: RAVI, requestedAt: iso('2026-08-13T09:20:00'),
      answerMessageId: theAnswer, answeredAt: iso('2026-08-13T09:35:00'),
      responseWorkingMinutes: 15,
    },
    {
      // Asked at 17:40 on Friday 7 Aug and never answered. Deliberately spans a
      // weekend: a fixture whose every window sits inside working hours is one
      // nobody can tell apart from wall clock, and the whole point of the
      // metric is that the two differ.
      id: nextId(db, 'statusRequest'), ticketId: T, ticketTitle: WALKTHROUGH_TITLE,
      threadId: thread.id, requestMessageId: stillWaiting,
      requestedById: MEERA, askedOfId: RAVI, requestedAt: iso('2026-08-07T17:40:00'),
      answerMessageId: null, answeredAt: null, responseWorkingMinutes: null,
    },
  );
}

/** Enough surrounding tickets that a list, a queue and a dashboard look real. */
function seedFiller(db: Db) {
  const TITLES = [
    'Saved filters lost after logout', 'Invoice PDF renders blank on Safari',
    'Add bulk export to the resource list', 'Webhook retries stop after one failure',
    'Search misses tickets with a hyphen in the title', 'Session expires while typing a long comment',
    'Deployment pipeline times out on the migration step', 'Client portal shows stale SLA badge',
    'Attachment thumbnails not generated for HEIC', 'Dashboard totals disagree with the ticket list',
    'Password reset link expires too quickly', 'Timezone wrong on the calendar heatmap',
    'Reopen does not clear the actual close date', 'Notification bell count drifts',
    'CSV export drops the client column', 'Stage queue not sorted oldest-first',
    'Duplicate emails on rapid handoff', 'Mobile layout breaks at 8 ribbon stages',
    'Effort log accepts 25 hours in a day', 'Cannot paste screenshots into a comment',
    'Round-robin assignment skips inactive users', 'Holiday calendar ignores regional holidays',
    'Ticket ID sequence reset after year change',
  ];
  const SCREENS = [
    'Student Profile', 'Admission Form', 'Fee Receipt Print', 'Marks Entry',
    'Daily Register', 'Issue & Return', 'Stock Adjustment', 'Parent Timeline',
  ];
  const FEATURES = [
    'Bulk edit', 'Duplicate check', 'Reprint with watermark', 'Grade calculation',
    'Backdated entry', 'Overdue reminder', 'Low-stock alert', 'Push notification',
  ];
  const LEVELS: Level[] = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
  const ACTIVE: StatusCode[] = ['NEW', 'IN_PROGRESS', 'ON_HOLD', 'AWAITING_INFO', 'REWORK'];
  const OPEN_STAGES = ['TRIAGE', 'DEVELOPMENT', 'QA', 'DEPLOYMENT', 'VERIFICATION', 'SIGNOFF'];
  const now = Date.parse('2026-08-06T09:00:00Z');
  /** C-062 · counts only the tickets that land in a queue-driven stage. */
  let queuePosition = 0;

  TITLES.forEach((title, i) => {
    const project = db.projects[i % 3];
    const seqNo = 300 + i;
    const ticketId = `${project.projectCode}-26-${String(seqNo).padStart(5, '0')}`;
    const closed = i % 5 === 0;
    const stage = closed ? 'CLOSED' : pick(OPEN_STAGES);
    const stageDef = db.stages.find((s) => s.stageCode === stage)!;
    const assignee = db.users.find((u) => u.role === stageDef.ownerRole) ?? db.users[2];
    /*
     * C-062 · **half the tickets waiting in a queue-driven stage have nobody
     * holding them**, and that is the state S-31 exists to make visible.
     *
     * Until now every filler ticket was assigned to the first user whose role
     * owns its stage — so QA's queue was three tickets, all Anil's, and the
     * screen was indistinguishable from My Tasks with a different title. That
     * fixture cannot tell the bug from the fix: with row scope applied it looked
     * identical, which is precisely how the defect would have shipped.
     *
     * It is also the honest shape. §17 item 12 describes the failure this screen
     * prevents as *"tickets stall between the handoff and someone noticing"* —
     * a stalled ticket is one nobody has picked up, so a queue in which
     * everything is already assigned is a queue with the interesting case
     * removed. `unassignedOnly` is on this endpoint and on `GET /tickets`, and
     * before this it could not return a single row from either.
     *
     * Narrowed to QA and Deployment on purpose. Leaving Development or Triage
     * tickets unassigned would move numbers on My Tasks, the dashboards and the
     * resource reports for a reason that has nothing to do with them.
     */
    const queueDriven = !closed && (stageDef.ownerRole === 'QA' || stageDef.ownerRole === 'DEPLOYMENT');
    // Alternated over the queue itself rather than over `i`. Only a handful of
    // the generated tickets land in QA or Deployment at all, and `i % 2` over
    // the whole run left the QA queue with three tickets and none of them
    // unassigned — a fixture that says the right thing and demonstrates nothing.
    const assigneeId = queueDriven && queuePosition++ % 2 === 0 ? null : assignee.id;
    const createdAt = new Date(now - int(1, 21) * 86_400_000).toISOString();
    const pcd = new Date(now + int(-4, 12) * 86_400_000).toISOString();
    const delayed = !closed && Date.parse(pcd) < now;

    // §7.5's fields, spread deliberately rather than uniformly:
    //   · every 11th ticket has no module at all — the state of every ticket
    //     raised before the fields existed, and what the grid must render
    //   · one references the retired module, which only resolves because the
    //     endpoint returns inactive rows
    //   · steps are present on roughly a third, because they are optional and a
    //     detail page that has only ever seen them populated hides the empty case
    const moduleId = i === 7 ? 9 : i % 11 === 0 ? null : (i % 8) + 1;

    db.tickets.push({
      ticketId, title,
      description: `${title}. Reported by the desk; reproduced on the current release.`,
      projectId: project.id,
      clientId: i % 3 === 0 ? null : ((i % 3) as number),
      clientContactId: null,
      isClientRaised: i % 3 !== 0,
      taskTypeId: int(1, 11),
      moduleId,
      screenName: moduleId == null ? null : SCREENS[i % SCREENS.length],
      feature: moduleId == null ? null : FEATURES[i % FEATURES.length],
      stepsToGenerate:
        moduleId != null && i % 3 === 0
          ? `<ol><li>Open ${SCREENS[i % SCREENS.length]}.</li>` +
            `<li>Use ${FEATURES[i % FEATURES.length]} with a typical record.</li>` +
            `<li>Observe: ${title.toLowerCase()}.</li></ol>`
          : null,
      level: delayed ? 'CRITICAL' : pick(LEVELS),
      originalLevel: pick(LEVELS),
      status: closed ? 'CLOSED' : pick(ACTIVE),
      currentStageCode: stage,
      assigneeId,
      reportedById: 6,
      cycleNo: i % 7 === 0 ? 2 : 1,
      iterationNo: i % 4 === 0 ? 2 : 1,
      reopenCount: i % 7 === 0 ? 1 : 0,
      plannedCloseDate: pcd,
      actualCloseDate: closed ? new Date(now - int(1, 5) * 86_400_000).toISOString() : null,
      isDelayed: delayed,
      delayedSince: delayed ? pcd : null,
      estimatedHrs: int(2, 24),
      pctComplete: closed ? 100 : int(0, 90),
      watcherIds: i % 4 === 0 ? [2] : [],
      createdAt, updatedAt: createdAt, version: 1,
    });
    db.cycles.push({ ticketId, cycleNo: 1, isSealed: closed, startedAt: createdAt, closedAt: null, reason: null });

    // One open transition so the stage queue has a time-in-stage to sort on.
    if (!closed) {
      const entered = new Date(now - int(1, 96) * 3_600_000).toISOString();
      db.transitions.push({
        id: nextId(db, 'transition'), ticketId, cycleNo: 1, iterationNo: 1,
        stageCode: stage, ownerId: assignee.id, ownerRole: stageDef.ownerRole,
        enteredAt: entered, exitedAt: null, durationMins: null,
        action: 'FORWARD', note: null, skipReason: null,
      });
    }
    for (let e = 0; e < int(0, 3); e++) {
      db.effortLogs.push({
        id: nextId(db, 'effort'), ticketId, userId: assignee.id,
        hours: [0.5, 1, 1.5, 2, 3, 4][int(0, 5)],
        workDate: createdAt.slice(0, 10), note: null,
        stageCode: stage, cycleNo: 1, iterationNo: 1,
        isCorrection: false, correctsEntryId: null, createdAt,
      });
    }
  });

  /**
   * B-029 · tickets belonging to the **deactivated** client.
   *
   * Until now no ticket in the fixture did. The generator above assigns
   * `clientId: i % 3 === 0 ? null : i % 3`, which only ever produces 1 and 2 —
   * both ACTIVE — so Oldco (id 4, INACTIVE) had none, and neither half of
   * blueprint line 523 could be exercised on any screen. A screen that hides a
   * deactivated client's history and one that never hides anything behaved
   * identically against this fixture, which is how the S-15 client filter went
   * on sending `?isActive=true` for three tasks without anybody noticing.
   *
   * Explicit rows rather than widening the generator's modulo: the ticket
   * distribution across three projects is depended on by the list, the queue and
   * the dashboard, and a fixture change that moves those numbers to make a point
   * about clients is a change to every one of their assertions.
   *
   * **Two open and one closed**, on project 4 — the mapping `CLIENT_PROJECTS`
   * already declares. The open pair is what `openTicketCount` reports as 2, so
   * S-32's warning has a number in it; the closed one is what stops a screen
   * that happens to filter history by "open" from passing by accident.
   *
   * **Assigned to Ravi (id 3), which is load-bearing rather than arbitrary.**
   * He is `currentUserId` and a Developer, so `scopedTickets` narrows to
   * `assigneeId === me.id` — put these on anybody else and they are invisible
   * to every default-user test, and the S-15 filter assertion would be checking
   * that a dropdown offers a client whose tickets can never be reached.
   */
  const OLDCO_TICKETS: [string, StatusCode, string, boolean][] = [
    ['ARCH-25-00061', 'IN_PROGRESS', 'Legacy import drops the fee-head column', false],
    ['ARCH-25-00062', 'ON_HOLD', 'Archived pilot still emails the old support address', false],
    ['ARCH-25-00063', 'CLOSED', 'Final handover pack not generated', true],
  ];
  OLDCO_TICKETS.forEach(([ticketId, status, title, closed], i) => {
    const createdAt = new Date(now - (120 + i) * 86_400_000).toISOString();
    db.tickets.push({
      ticketId, title,
      description: `${title}. Raised before the account was closed; still worked on.`,
      projectId: 4,
      clientId: 4,
      clientContactId: null,
      isClientRaised: true,
      taskTypeId: 1,
      moduleId: null,
      screenName: null,
      feature: null,
      stepsToGenerate: null,
      level: 'MEDIUM',
      originalLevel: 'MEDIUM',
      status,
      currentStageCode: closed ? 'CLOSED' : 'DEVELOPMENT',
      assigneeId: 3,
      reportedById: 6,
      cycleNo: 1,
      iterationNo: 1,
      reopenCount: 0,
      plannedCloseDate: new Date(now - 90 * 86_400_000).toISOString(),
      actualCloseDate: closed ? new Date(now - 88 * 86_400_000).toISOString() : null,
      isDelayed: !closed,
      delayedSince: closed ? null : new Date(now - 90 * 86_400_000).toISOString(),
      estimatedHrs: 8,
      pctComplete: closed ? 100 : 40,
      watcherIds: [],
      createdAt, updatedAt: createdAt, version: 1,
    });
    db.cycles.push({
      ticketId, cycleNo: 1, isSealed: closed,
      startedAt: createdAt, closedAt: null, reason: null,
    });
  });

  /*
   * C-026 · attachments, on tickets somebody can actually open.
   *
   * The table was `[]` from D-004 until now, which was fine while nothing
   * rendered a file and stopped being fine the moment S-20 grew a gallery: every
   * ticket showed an empty strip, so the feature was invisible unless a developer
   * happened to attach something by hand. A fixture that requires a manual step
   * before it shows anything is not a fixture.
   *
   * Seeded onto the walkthrough ticket **and** onto the first ticket the default
   * signed-in user owns. `CRM-26-00347` is assigned to Meera and the default user
   * is Ravi, a Developer scoped to `assigned_to = me` — so seeding only the
   * walkthrough ticket would have left the strip empty under `npm run dev`
   * exactly as before, for the reason the C-019 note already records.
   *
   * The mix is deliberate and each row is a distinct rendering path:
   * two CLEAN images (so the lightbox has something to page through), a WebP
   * (CLEAN, but **no thumbnail** — the server cannot decode one, so this is the
   * client's fall-back-to-the-full-image path), a document, and one PENDING row
   * that must render as "Scanning" with no image at all.
   */
  const attachmentsFor = (ticket: Ticket, uploadedById: number) => [
    { fileName: 'fees-screen-error.png', contentType: 'image/png', sizeBytes: 184_320, scanStatus: 'CLEAN' as const },
    { fileName: 'stack-trace.png', contentType: 'image/png', sizeBytes: 96_180, scanStatus: 'CLEAN' as const },
    { fileName: 'receipt-preview.webp', contentType: 'image/webp', sizeBytes: 42_110, scanStatus: 'CLEAN' as const },
    { fileName: 'error-log.txt', contentType: 'text/plain', sizeBytes: 12_884, scanStatus: 'CLEAN' as const },
    { fileName: 'awaiting-scan.png', contentType: 'image/png', sizeBytes: 220_400, scanStatus: 'PENDING' as const },
  ].map((file, n) => ({
    id: nextId(db, 'attachment'),
    ticketId: ticket.ticketId,
    ...file,
    isClientVisible: n === 3,
    isDeleted: false,
    uploadedById,
    stageCode: ticket.currentStageCode,
    // **The ticket's own cycle, never a hardcoded 1.** `GET /tickets/{id}/full`
    // filters attachments by the cycle it is rendering, and the generator puts
    // every seventh ticket on cycle 2 — so a fixed `cycleNo: 1` produces rows
    // that exist in the store, pass every unit test, and are filtered out of the
    // only payload the detail page reads. Which is exactly how this was found.
    cycleNo: ticket.cycleNo,
    createdAt: iso('2026-08-08T10:0' + n + ':00'),
  }));

  /*
   * One populated ticket per signed-in user, not just the walkthrough.
   *
   * Row scoping means a Developer sees only `assigned_to = me`, so seeding the
   * walkthrough alone leaves the strip empty for five of the six seeded logins —
   * which is the state this fixture was already in, and the reason the gallery
   * looked broken when it was not.
   */
  db.users
    .filter((u) => u.isActive)
    .forEach((user) => {
      const own = db.tickets.find((t) => t.assigneeId === user.id && t.status !== 'CLOSED');
      if (own && !db.attachments.some((a) => a.ticketId === own.ticketId)) {
        db.attachments.push(...attachmentsFor(own, user.id));
      }
    });

  const walkthrough = db.tickets.find((t) => t.ticketId === 'CRM-26-00347');
  if (walkthrough && !db.attachments.some((a) => a.ticketId === walkthrough.ticketId && a.cycleNo === walkthrough.cycleNo)) {
    db.attachments.push(...attachmentsFor(walkthrough, 2));
  }

  db.notifications.push(
    { id: nextId(db, 'notification'), userId: 3, eventKey: 'TICKET_HANDED_OFF', title: 'CRM-26-00347 handed to you at Development', body: 'Meera Iyer handed this to you.', ticketId: 'CRM-26-00347', isRead: false, deepLink: '/tickets/CRM-26-00347', createdAt: iso('2026-08-08T11:00:00') },
    { id: nextId(db, 'notification'), userId: 3, eventKey: 'MENTIONED', title: 'Anil Shah mentioned you', body: 'Retest both flows please.', ticketId: 'CRM-26-00347', isRead: false, deepLink: '/tickets/CRM-26-00347', createdAt: iso('2026-08-06T13:55:00') },
    { id: nextId(db, 'notification'), userId: 3, eventKey: 'SLA_BREACH', title: 'CRM-26-00347 breached its SLA', body: 'Escalated High → Critical.', ticketId: 'CRM-26-00347', isRead: true, deepLink: '/tickets/CRM-26-00347', createdAt: iso('2026-08-13T00:15:00') },
  );
}

let db: Db = createDb();

export const getDb = (): Db => db;

/** Reset between tests, so one test's handoff cannot leak into the next. */
export const resetDb = (): Db => (db = createDb());
