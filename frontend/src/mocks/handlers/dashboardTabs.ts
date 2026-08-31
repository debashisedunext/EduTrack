import { http } from 'msw';
import type { Db, RoleCode, Ticket } from '../db';
import { getDb } from '../db';
import { currentUser, ok, scopedTickets, url, validationFailed } from './util';

/**
 * S-05 rework · mocks for the three tab endpoints.
 *
 * **Stream A adding handlers in Stream D's directory (D-004)** — the same
 * situation `/dashboard/widgets` and `/reports` document in `rest.ts`, and
 * flagged here the same way rather than done quietly. `coverage.test.ts`
 * refuses a contract operation with no handler, so the alternative to this file
 * is a red `develop` the moment the contract lands.
 *
 * **These compute from the seeded tickets rather than returning fixtures.**
 * The whole point of the split in `docs/Dashboard-Rework-Split.md` is that both
 * developers build entire tabs against MSW weeks before the endpoints exist, so
 * a mock returning three hardcoded cards would let a tab look finished while
 * being wrong about every relationship the real screen has to get right —
 * sub-figures summing to their card, disjoint segments, a drill-down returning
 * what its figure counted. Deriving from `db.tickets` means the numbers move
 * when the seed does, and a card that disagrees with its own drill-down is
 * visible here rather than at H2.
 *
 * Scope is `scopedTickets`, so a Developer login sees their own rows and the
 * own-work variant, exactly as `DashboardScope` will narrow them server-side.
 *
 * Two places this is deliberately thinner than the real thing, both noted where
 * they occur: the review-stage half of Pending Review, and holidays in the
 * near-delay calendar.
 */

const DAY = 86_400_000;

/** The UTC civil day, which is what the summary tables are keyed by. */
const todayUtc = (): string => new Date().toISOString().slice(0, 10);

const dayOf = (iso: string | null): string | null => (iso ? iso.slice(0, 10) : null);

function categoryOf(db: Db, status: string): 'TODO' | 'IN_PROGRESS' | 'DONE' {
  return db.statuses.find((s) => s.code === status)?.category ?? 'TODO';
}

/**
 * The next working day, weekends only.
 *
 * The real `WorkingHoursService.nextWorkingDay` also walks org holidays and
 * resource leave; the mock db seeds no holiday calendar, so this stops at
 * weekends. That difference is why the near-delay figure is one of the things
 * the plan says to re-check against the real backend at H2 rather than trusting
 * the mock for.
 */
function nextWorkingDay(from: Date): string {
  const d = new Date(from.getTime() + DAY);
  while (d.getUTCDay() === 0 || d.getUTCDay() === 6) d.setUTCDate(d.getUTCDate() + 1);
  return d.toISOString().slice(0, 10);
}

const notStarted = (db: Db, t: Ticket) => categoryOf(db, t.status) === 'TODO';
const wip = (db: Db, t: Ticket) => categoryOf(db, t.status) === 'IN_PROGRESS';
const overdue = (t: Ticket, today: string) =>
  t.plannedCloseDate != null && t.plannedCloseDate < today && t.status !== 'CLOSED';

/** A figure and the query returning exactly the rows it counted. */
const fig = (value: number, drillDown: string | null = null) => ({ value, drillDown });

const q = (params: Record<string, string | number | boolean | undefined>) => {
  const search = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v !== undefined) search.set(k, String(v));
  return `/tickets?${search.toString()}`;
};

// ── tab 1 · Today's Progress ────────────────────────────────────────────────

const ROLE_CHIPS: { role: RoleCode | 'UNASSIGNED'; label: string }[] = [
  { role: 'DEVELOPER', label: 'Developer' },
  { role: 'QA', label: 'QA' },
  { role: 'PM', label: 'Project Manager' },
  { role: 'SUPPORT', label: 'Support' },
  { role: 'DEPLOYMENT', label: 'Deployment' },
  { role: 'UNASSIGNED', label: 'Unassigned' },
];

function todayPayload(db: Db) {
  const today = todayUtc();
  const nearBy = nextWorkingDay(new Date(`${today}T00:00:00Z`));
  const me = currentUser(db);
  const ownWork = me.role === 'DEVELOPER' || me.role === 'QA' || me.role === 'DEPLOYMENT';

  const rows = scopedTickets(db).filter((t) => t.status !== 'CLOSED');
  const ns = rows.filter((t) => notStarted(db, t));
  const ip = rows.filter((t) => wip(db, t));
  const late = rows.filter((t) => overdue(t, today));

  const nsOverdue = ns.filter((t) => overdue(t, today));
  const nsDueToday = ns.filter((t) => t.plannedCloseDate === today);
  const wipUpdatedToday = ip.filter((t) => dayOf(t.updatedAt) === today);
  const wipDelayed = ip.filter((t) => t.isDelayed);
  const wipNearDelay = ip.filter(
    (t) => !t.isDelayed && t.plannedCloseDate != null && t.plannedCloseDate <= nearBy,
  );
  const wipOnTime = ip.filter((t) => !t.isDelayed && !wipNearDelay.includes(t));
  const onHold = rows.filter((t) => t.status === 'ON_HOLD');
  const awaiting = rows.filter((t) => t.status === 'AWAITING_INFO');

  /*
    Pending Review is RESOLVED-not-CLOSED **plus** tickets in a stage the stage
    master marks as review, de-duplicated. The mock db seeds no review flag on
    its stages, so only the first half is computed here — the count is therefore
    a floor, and the real endpoint will return the same or more. Stated rather
    than silently approximated, because a mock that quietly under-counts is how
    a card ships looking right.
  */
  const pendingReview = rows.filter((t) => t.status === 'RESOLVED');

  const openRows = scopedTickets(db).filter((t) => t.status !== 'CLOSED');

  const cards = [
    {
      key: 'todays-work',
      label: "Today's Work",
      total: fig(rows.length, q({ excludeClosed: true })),
      figures: [
        { key: 'not-started', label: 'Not started', value: ns.length, drillDown: q({ statusCategory: 'TODO', excludeClosed: true }) },
        { key: 'on-time', label: 'On time', value: wipOnTime.length, drillDown: q({ statusCategory: 'IN_PROGRESS', isDelayed: false }) },
        { key: 'wip', label: 'WIP', value: ip.length, drillDown: q({ statusCategory: 'IN_PROGRESS' }) },
        { key: 'overdue', label: 'Overdue', value: late.length, drillDown: q({ dueTo: today, excludeClosed: true }) },
      ],
    },
    {
      key: 'overdue',
      label: 'Overdue',
      total: fig(late.length, q({ dueTo: today, excludeClosed: true })),
      figures: [
        { key: 'not-started', label: 'Not started', value: late.filter((t) => notStarted(db, t)).length, drillDown: q({ statusCategory: 'TODO', dueTo: today, excludeClosed: true }) },
        { key: 'wip', label: 'WIP', value: late.filter((t) => wip(db, t)).length, drillDown: q({ statusCategory: 'IN_PROGRESS', dueTo: today }) },
      ],
    },
    {
      key: 'not-started',
      label: 'Not Started',
      total: fig(ns.length, q({ statusCategory: 'TODO', excludeClosed: true })),
      figures: [
        { key: 'overdue-start', label: 'Overdue start', value: nsOverdue.length, drillDown: q({ statusCategory: 'TODO', dueTo: today, excludeClosed: true }) },
        { key: 'due-today', label: 'Due today', value: nsDueToday.length, drillDown: q({ statusCategory: 'TODO', dueFrom: today, dueTo: today }) },
        { key: 'total', label: 'Total', value: ns.length, drillDown: q({ statusCategory: 'TODO', excludeClosed: true }) },
      ],
    },
    {
      key: 'wip',
      label: 'WIP',
      total: fig(ip.length, q({ statusCategory: 'IN_PROGRESS' })),
      figures: [
        { key: 'total', label: 'Total', value: ip.length, drillDown: q({ statusCategory: 'IN_PROGRESS' }) },
        { key: 'updated-today', label: 'Updated today', value: wipUpdatedToday.length, drillDown: q({ statusCategory: 'IN_PROGRESS', updatedFrom: today, updatedTo: today }) },
        { key: 'not-updated', label: 'Not updated', value: ip.length - wipUpdatedToday.length, drillDown: q({ statusCategory: 'IN_PROGRESS', updatedTo: today }) },
      ],
    },
    {
      key: 'wip-breakdown',
      label: 'WIP Breakdown',
      total: fig(ip.length, q({ statusCategory: 'IN_PROGRESS' })),
      figures: [
        { key: 'near-delay', label: 'Near delay', value: wipNearDelay.length, drillDown: q({ statusCategory: 'IN_PROGRESS', isDelayed: false, dueTo: nearBy }) },
        { key: 'delayed', label: 'Delayed', value: wipDelayed.length, drillDown: q({ statusCategory: 'IN_PROGRESS', isDelayed: true }) },
        { key: 'on-time', label: 'On time', value: wipOnTime.length, drillDown: q({ statusCategory: 'IN_PROGRESS', isDelayed: false }) },
      ],
    },
    {
      key: 'blocked',
      label: 'Blocked',
      total: fig(onHold.length + awaiting.length, q({ statuses: 'ON_HOLD,AWAITING_INFO' })),
      figures: [
        { key: 'on-hold', label: 'On hold', value: onHold.length, drillDown: q({ status: 'ON_HOLD' }) },
        { key: 'awaiting-info', label: 'Awaiting info', value: awaiting.length, drillDown: q({ status: 'AWAITING_INFO' }) },
      ],
    },
    {
      // One combined count, no sub-figures. Splitting it would double-count a
      // ticket that is both RESOLVED and sitting in a review stage.
      key: 'pending-review',
      label: 'Pending Review',
      total: fig(pendingReview.length, q({ pendingReview: true })),
      figures: [],
    },
  ];

  const roleOf = (t: Ticket): RoleCode | 'UNASSIGNED' =>
    (db.users.find((u) => u.id === t.assigneeId)?.role ?? 'UNASSIGNED') as RoleCode | 'UNASSIGNED';

  const openIssues = ownWork
    ? null
    : {
        total: fig(openRows.length, q({ excludeClosed: true })),
        roles: ROLE_CHIPS.map(({ role, label }) => ({
          role,
          label,
          value: openRows.filter((t) => roleOf(t) === role).length,
          drillDown:
            role === 'UNASSIGNED'
              ? q({ unassigned: true, excludeClosed: true })
              : q({ excludeClosed: true }),
        })),
      };

  const resources = ownWork
    ? []
    : db.users
        .filter((u) => openRows.some((t) => t.assigneeId === u.id))
        .map((u) => {
          const mine = openRows.filter((t) => t.assigneeId === u.id);
          const myNs = mine.filter((t) => notStarted(db, t));
          const myWip = mine.filter((t) => wip(db, t));
          const myNear = myWip.filter(
            (t) => !t.isDelayed && t.plannedCloseDate != null && t.plannedCloseDate <= nearBy,
          );
          const finished = scopedTickets(db).filter(
            (t) => t.assigneeId === u.id && dayOf(t.actualCloseDate) === todayUtc(),
          );
          const cell = (value: number, extra: Record<string, string | number | boolean>) =>
            fig(value, q({ assigneeId: u.id, ...extra }));
          return {
            userId: u.id,
            displayName: u.displayName,
            overdueStart: cell(myNs.filter((t) => overdue(t, today)).length, { statusCategory: 'TODO', dueTo: today }),
            dueToday: cell(myNs.filter((t) => t.plannedCloseDate === today).length, { statusCategory: 'TODO', dueFrom: today, dueTo: today }),
            notStarted: cell(myNs.length, { statusCategory: 'TODO', excludeClosed: true }),
            wip: cell(myWip.length, { statusCategory: 'IN_PROGRESS' }),
            updatedToday: cell(myWip.filter((t) => dayOf(t.updatedAt) === today).length, { statusCategory: 'IN_PROGRESS', updatedFrom: today, updatedTo: today }),
            nearDelay: cell(myNear.length, { statusCategory: 'IN_PROGRESS', isDelayed: false, dueTo: nearBy }),
            delayed: cell(myWip.filter((t) => t.isDelayed).length, { statusCategory: 'IN_PROGRESS', isDelayed: true }),
            onTime: cell(myWip.filter((t) => !t.isDelayed && !myNear.includes(t)).length, { statusCategory: 'IN_PROGRESS', isDelayed: false }),
            finishedToday: cell(finished.length, { finishedFrom: today, finishedTo: today }),
            finishedLate: cell(finished.filter((t) => t.isDelayed).length, { finishedFrom: today, finishedTo: today, isDelayed: true }),
          };
        });

  return {
    asOf: new Date().toISOString(),
    variant: ownWork ? 'OWN_WORK' : 'FULL',
    unavailableReason: null,
    cards,
    openIssues,
    resources,
  };
}

// ── tab 2 · Ticket Overview ─────────────────────────────────────────────────

function overviewPayload(db: Db, search: URLSearchParams) {
  const today = todayUtc();
  const from = search.get('from');
  const to = search.get('to');
  const assigneeId = search.get('assigneeId');

  let rows = scopedTickets(db);
  if (from) rows = rows.filter((t) => (t.createdAt.slice(0, 10) ?? '') >= from);
  if (to) rows = rows.filter((t) => (t.createdAt.slice(0, 10) ?? '') <= to);
  if (assigneeId) rows = rows.filter((t) => t.assigneeId === Number(assigneeId));

  const range = { from: from ?? undefined, to: to ?? undefined };
  const byCategory = (c: 'TODO' | 'IN_PROGRESS' | 'DONE') =>
    rows.filter((t) => categoryOf(db, t.status) === c);

  const cards = [
    { key: 'total', label: 'Total', value: rows.length, drillDown: q({ ...range }) },
    { key: 'pending', label: 'Pending', value: byCategory('TODO').length, drillDown: q({ statusCategory: 'TODO', ...range }) },
    { key: 'in-progress', label: 'In Progress', value: byCategory('IN_PROGRESS').length, drillDown: q({ statusCategory: 'IN_PROGRESS', ...range }) },
    { key: 'completed', label: 'Completed', value: byCategory('DONE').length, drillDown: q({ statusCategory: 'DONE', ...range }) },
  ];

  /*
    Open state now, NOT completed-in-range — the one figure on this tab that
    answers "what is on people's plates today" rather than "what happened in the
    window". The three states are disjoint and **overdue takes precedence**, so
    the segments sum to that person's open total and an overdue WIP ticket is
    counted once.
  */
  const open = scopedTickets(db).filter((t) => t.status !== 'CLOSED');
  const assignees = db.users
    .filter((u) => open.some((t) => t.assigneeId === u.id))
    .map((u) => {
      const mine = open.filter((t) => t.assigneeId === u.id);
      const late = mine.filter((t) => overdue(t, today));
      const rest = mine.filter((t) => !late.includes(t));
      return {
        userId: u.id,
        displayName: u.displayName,
        inProgress: fig(rest.filter((t) => wip(db, t)).length, q({ assigneeId: u.id, statusCategory: 'IN_PROGRESS' })),
        overdue: fig(late.length, q({ assigneeId: u.id, dueTo: today, excludeClosed: true })),
        notStarted: fig(rest.filter((t) => notStarted(db, t)).length, q({ assigneeId: u.id, statusCategory: 'TODO', excludeClosed: true })),
      };
    })
    .sort((a, b) => (b.inProgress.value + b.overdue.value + b.notStarted.value)
      - (a.inProgress.value + a.overdue.value + a.notStarted.value))
    .slice(0, 10);

  // `pct` is served rather than left to the client so the legend and the arc
  // cannot round differently.
  const total = rows.length || 1;
  const distribution = (['TODO', 'IN_PROGRESS', 'DONE'] as const).map((category) => {
    const value = byCategory(category).length;
    return {
      category,
      label: category === 'TODO' ? 'Pending' : category === 'IN_PROGRESS' ? 'In Progress' : 'Completed',
      value,
      pct: Math.round((value / total) * 1000) / 10,
      drillDown: q({ statusCategory: category, ...range }),
    };
  });

  return { asOf: new Date().toISOString(), unavailableReason: null, cards, assignees, distribution };
}

// ── tab 3 · Weekly Progress ─────────────────────────────────────────────────

/** The ISO Monday of the week containing `d`, in UTC. */
function isoMonday(d: Date): Date {
  const out = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()));
  const shift = (out.getUTCDay() + 6) % 7;
  out.setUTCDate(out.getUTCDate() - shift);
  return out;
}

function weeklyPayload(db: Db, search: URLSearchParams) {
  const raw = search.get('weekStart');
  const start = raw ? new Date(`${raw}T00:00:00Z`) : isoMonday(new Date());
  const end = new Date(start.getTime() + 6 * DAY);
  const prevStart = new Date(start.getTime() - 7 * DAY);
  const prevEnd = new Date(start.getTime() - DAY);

  const iso = (d: Date) => d.toISOString().slice(0, 10);
  const inWindow = (t: Ticket, a: Date, b: Date) =>
    t.plannedCloseDate != null && t.plannedCloseDate >= iso(a) && t.plannedCloseDate <= iso(b);

  const rows = scopedTickets(db);
  const openRows = rows.filter((t) => t.status !== 'CLOSED');

  const avg = (xs: number[]) => (xs.length ? Math.round((xs.reduce((a, b) => a + b, 0) / xs.length) * 10) / 10 : 0);
  const progressNow = avg(openRows.map((t) => t.pctComplete));
  // Against the tickets that were due in the prior window, not against
  // `progressNow` itself — comparing a figure to itself reports "0% change"
  // for ever, which is exactly the fabricated zero the contract forbids.
  const progressPrev = avg(rows.filter((t) => inWindow(t, prevStart, prevEnd)).map((t) => t.pctComplete));
  const dueThis = rows.filter((t) => inWindow(t, start, end));
  const finishedThis = dueThis.filter((t) => t.actualCloseDate != null);
  const delayedThis = rows.filter((t) => t.isDelayed && inWindow(t, start, end)).length;
  const delayedPrev = rows.filter((t) => t.isDelayed && inWindow(t, prevStart, prevEnd)).length;
  const delayDays = openRows
    .filter((t) => t.delayedSince != null)
    .map((t) => Math.max(0, Math.round((Date.now() - Date.parse(t.delayedSince!)) / DAY)));

  /*
    `deltaPct` is null, never 0, when the prior window holds nothing. A first
    week has nothing to improve on, and 0% would claim it held steady.
  */
  const priorHasData = rows.some((t) => inWindow(t, prevStart, prevEnd));
  const delta = (now: number, before: number) =>
    !priorHasData || before === 0 ? null : Math.round(((now - before) / before) * 1000) / 10;

  const range = { dueFrom: iso(start), dueTo: iso(end) };
  return {
    asOf: new Date().toISOString(),
    unavailableReason: null,
    weekStart: iso(start),
    weekEnd: iso(end),
    cards: [
      {
        key: 'avg-progress', label: 'Average progress', value: progressNow, unit: 'PERCENT',
        secondaryValue: null, secondaryLabel: null,
        deltaPct: delta(progressNow, progressPrev),
        drillDown: q({ excludeClosed: true }),
      },
      {
        key: 'due-this-week', label: 'Due this week', value: dueThis.length, unit: 'COUNT',
        secondaryValue: finishedThis.length, secondaryLabel: 'finished so far',
        deltaPct: delta(dueThis.length, rows.filter((t) => inWindow(t, prevStart, prevEnd)).length),
        drillDown: q(range),
      },
      {
        key: 'delayed-vs-last-week', label: 'Delayed', value: delayedThis, unit: 'COUNT',
        secondaryValue: delayedPrev, secondaryLabel: 'last week',
        deltaPct: delta(delayedThis, delayedPrev),
        drillDown: q({ ...range, isDelayed: true }),
      },
      {
        key: 'avg-delay-days', label: 'Average delay', value: avg(delayDays), unit: 'DAYS',
        secondaryValue: null, secondaryLabel: null,
        deltaPct: null,
        drillDown: q({ isDelayed: true, excludeClosed: true }),
      },
    ],
  };
}

export const dashboardTabHandlers = [
  http.get(url('/dashboard/today'), () => ok(todayPayload(getDb()))),

  http.get(url('/dashboard/overview'), ({ request }) =>
    ok(overviewPayload(getDb(), new URL(request.url).searchParams))),

  http.get(url('/dashboard/weekly'), ({ request }) => {
    const search = new URL(request.url).searchParams;
    const raw = search.get('weekStart');
    // A date that is not a Monday is refused rather than silently shifted: a
    // Wednesday-to-Wednesday window returns figures that look ordinary and
    // compare against the wrong seven days.
    if (raw && new Date(`${raw}T00:00:00Z`).getUTCDay() !== 1) {
      return validationFailed({ weekStart: ['weekStart must be a Monday (ISO week start, UTC).'] });
    }
    return ok(weeklyPayload(getDb(), search));
  }),
];
