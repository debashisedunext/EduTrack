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
const rnd = mulberry32(20260803);
const pick = <T,>(xs: readonly T[]): T => xs[Math.floor(rnd() * xs.length)];
const int = (lo: number, hi: number) => lo + Math.floor(rnd() * (hi - lo + 1));

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
export interface Client {
  id: number; clientCode: string; name: string; domain: string;
  accountManagerId: number; supportPlan: string; timezone: string; isActive: boolean;
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
export interface Contact {
  id: number; clientId: number; name: string; email: string; phone: string;
  isPrimary: boolean; notificationOptIn: boolean; portalAccess: boolean;
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

// ── the store ───────────────────────────────────────────────────────────────
export interface Db {
  users: User[]; projects: Project[]; clients: Client[]; contacts: Contact[];
  clientProjects: ClientProject[];
  taskTypes: TaskType[]; modules: Module[]; priorities: Priority[]; stages: Stage[];
  /** B-015 · S-09. `roleGrants` is keyed by role id — the matrix, one row per role. */
  permissions: Permission[]; roles: Role[]; roleGrants: Record<number, string[]>;
  /** B-022 · S-15. One row per (event, channel) — the wording of everything sent. */
  notificationTemplates: NotificationTemplateRow[];
  slaPolicies: SlaPolicy[];
  projectTaskTypes: ProjectTaskType[];
  pushSubscriptions: PushSubscription[];
  tickets: Ticket[]; cycles: Cycle[]; transitions: Transition[];
  effortLogs: EffortLog[]; history: HistoryEntry[]; comments: Comment[];
  attachments: Attachment[]; notifications: Notification[];
  notificationPreferences: NotificationPreference[];
  emailLog: EmailLogEntry[]; chatThreads: ChatThread[]; chatMessages: ChatMessage[];
  statusRequests: StatusRequest[];
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
}

export interface Holiday {
  id: number; date: string; name: string;
  projectId: number | null; isRecurring: boolean; isActive: boolean;
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

const CLIENTS: Client[] = [
  { id: 1, clientCode: 'ACME', name: 'Acme Retail Ltd', domain: 'acme.example', accountManagerId: 2, supportPlan: 'Premium', timezone: 'Asia/Kolkata', isActive: true },
  { id: 2, clientCode: 'NORTH', name: 'Northwind Logistics', domain: 'northwind.example', accountManagerId: 2, supportPlan: 'Standard', timezone: 'Europe/London', isActive: true },
  { id: 3, clientCode: 'BLUE', name: 'Bluewave Media', domain: 'bluewave.example', accountManagerId: 1, supportPlan: 'Standard', timezone: 'America/New_York', isActive: true },
  { id: 4, clientCode: 'OLDCO', name: 'Oldco Industries', domain: 'oldco.example', accountManagerId: 1, supportPlan: 'Basic', timezone: 'Asia/Kolkata', isActive: false },
];

const CONTACTS: Contact[] = [
  { id: 1, clientId: 1, name: 'Sara Kapoor', email: 'sara@acme.example', phone: '+91 98200 11111', isPrimary: true, notificationOptIn: true, portalAccess: true },
  { id: 2, clientId: 1, name: 'Dev Patel', email: 'dev@acme.example', phone: '+91 98200 22222', isPrimary: false, notificationOptIn: true, portalAccess: false },
  { id: 3, clientId: 2, name: 'Tom Fletcher', email: 'tom@northwind.example', phone: '+44 20 7946 0000', isPrimary: true, notificationOptIn: true, portalAccess: false },
  { id: 4, clientId: 3, name: 'Erin Walsh', email: 'erin@bluewave.example', phone: '+1 212 555 0100', isPrimary: true, notificationOptIn: false, portalAccess: false },
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
 */
const CLIENT_PROJECTS: ClientProject[] = [
  { clientId: 1, projectId: 1, isDefault: true },
  { clientId: 1, projectId: 3, isDefault: false },
  { clientId: 2, projectId: 1, isDefault: false },
  { clientId: 2, projectId: 2, isDefault: true },
  { clientId: 4, projectId: 4, isDefault: true },
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

export function createDb(): Db {
  const db: Db = {
    users: structuredClone(USERS),
    projects: structuredClone(PROJECTS),
    clients: structuredClone(CLIENTS),
    contacts: structuredClone(CONTACTS),
    clientProjects: structuredClone(CLIENT_PROJECTS),
    taskTypes: structuredClone(TASK_TYPES),
    modules: structuredClone(MODULES),
    priorities: structuredClone(PRIORITIES),
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
    comments: [], attachments: [], notifications: [], notificationPreferences: [], emailLog: [],
    chatThreads: [], chatMessages: [], statusRequests: [],
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
  return db;
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

  TITLES.forEach((title, i) => {
    const project = db.projects[i % 3];
    const seqNo = 300 + i;
    const ticketId = `${project.projectCode}-26-${String(seqNo).padStart(5, '0')}`;
    const closed = i % 5 === 0;
    const stage = closed ? 'CLOSED' : pick(OPEN_STAGES);
    const stageDef = db.stages.find((s) => s.stageCode === stage)!;
    const assignee = db.users.find((u) => u.role === stageDef.ownerRole) ?? db.users[2];
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
      assigneeId: assignee.id,
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
