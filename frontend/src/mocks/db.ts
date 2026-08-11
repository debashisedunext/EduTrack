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
}
export interface Project {
  id: number; projectCode: string; name: string; projectManagerId: number;
  colourTag: string; isActive: boolean; ticketSeq: number;
}
export interface Client {
  id: number; clientCode: string; name: string; domain: string;
  accountManagerId: number; supportPlan: string; timezone: string; isActive: boolean;
}
export interface Contact {
  id: number; clientId: number; name: string; email: string; phone: string;
  isPrimary: boolean; notificationOptIn: boolean; portalAccess: boolean;
}
export interface TaskType {
  id: number; name: string; icon: string; colour: string;
  defaultLevel: Level; defaultSlaHrs: number; isActive: boolean;
}
/** §7.5 — the product area a concern was raised against. */
export interface Module {
  id: number; code: string; name: string; seq: number; isActive: boolean;
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

// ── the store ───────────────────────────────────────────────────────────────
export interface Db {
  users: User[]; projects: Project[]; clients: Client[]; contacts: Contact[];
  taskTypes: TaskType[]; modules: Module[]; stages: Stage[];
  tickets: Ticket[]; cycles: Cycle[]; transitions: Transition[];
  effortLogs: EffortLog[]; history: HistoryEntry[]; comments: Comment[];
  attachments: Attachment[]; notifications: Notification[];
  notificationPreferences: NotificationPreference[];
  emailLog: EmailLogEntry[]; chatThreads: ChatThread[]; chatMessages: ChatMessage[];
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
}

export interface Holiday {
  id: number; date: string; name: string;
  projectId: number | null; isRecurring: boolean; isActive: boolean;
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
}));

const PROJECTS: Project[] = [
  { id: 1, projectCode: 'CRM', name: 'Client CRM Platform', projectManagerId: 2, colourTag: '#4F46E5', isActive: true, ticketSeq: 347 },
  { id: 2, projectCode: 'PAY', name: 'Payments Gateway', projectManagerId: 2, colourTag: '#F59E0B', isActive: true, ticketSeq: 128 },
  { id: 3, projectCode: 'WEB', name: 'Marketing Website', projectManagerId: 2, colourTag: '#06B6D4', isActive: true, ticketSeq: 64 },
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

const TASK_TYPES: TaskType[] = [
  ['Change Request', 'git-pull-request', '#4F46E5', 'MEDIUM', 72],
  ['Production Bug', 'flame', '#BE185D', 'HIGH', 8],
  ['Client Request', 'message-square', '#06B6D4', 'MEDIUM', 48],
  ['Future Release', 'calendar', '#84CC16', 'LOW', 240],
  ['Internal Bug', 'bug', '#9A3412', 'MEDIUM', 24],
  ['Client Bug', 'bug', '#BE185D', 'HIGH', 16],
  ['Server Issue', 'server', '#9333EA', 'CRITICAL', 4],
  ['Network Issue', 'wifi', '#0891B2', 'HIGH', 4],
  ['Browser Issue', 'globe', '#F59E0B', 'LOW', 72],
  ['Performance Issue', 'gauge', '#9A3412', 'HIGH', 24],
  ['Other', 'circle-help', '#7C859C', 'LOW', 120],
].map(([name, icon, colour, defaultLevel, defaultSlaHrs], i) => ({
  id: i + 1,
  name: name as string,
  icon: icon as string,
  colour: colour as string,
  defaultLevel: defaultLevel as Level,
  defaultSlaHrs: defaultSlaHrs as number,
  isActive: true,
}));

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
    taskTypes: structuredClone(TASK_TYPES),
    modules: structuredClone(MODULES),
    stages: structuredClone(STAGES),
    tickets: [], cycles: [], transitions: [], effortLogs: [], history: [],
    comments: [], attachments: [], notifications: [], notificationPreferences: [], emailLog: [],
    chatThreads: [], chatMessages: [],
    currentUserId: 3, // Ravi — a Developer, so scoping is visible by default
    seq: {},
    twoFactor: {}, // opt-in, so nobody starts enrolled
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
  const [ANITA, MEERA, RAVI, ANIL, KARAN, PRIYA] = [1, 2, 3, 4, 5, 6];
  void ANITA;

  db.tickets.push({
    ticketId: T,
    title: 'Checkout fails with 500 on saved-card payment',
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
  db.chatMessages.push(
    { id: nextId(db, 'message'), threadId: thread.id, body: 'Status update requested on this ticket.', authorId: MEERA, kind: 'STATUS_REQUEST', isEdited: false, isDeleted: false, readBy: [RAVI], createdAt: iso('2026-08-13T09:20:00') },
    { id: nextId(db, 'message'), threadId: thread.id, body: 'Root cause found — token refresh again, different code path. Fix by EOD.', authorId: RAVI, kind: 'TEXT', isEdited: false, isDeleted: false, readBy: [MEERA], createdAt: iso('2026-08-13T09:35:00') },
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
