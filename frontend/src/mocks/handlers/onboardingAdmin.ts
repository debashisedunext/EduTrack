import { http, HttpResponse } from 'msw';
import type {
  Db, ObClientEscalationRow, ObEscalationRow, ObModuleAccessRow,
  ObNotificationTemplateRow, ObSignoffRow,
} from '../db';
import { getDb, nextId } from '../db';
import { stepRag } from './onboarding';
import {
  currentUser, notFound, ok, paginate, problem, unprocessable, url, userRef,
  validationFailed,
} from './util';

/**
 * A-118 · mocks for the OB-02 dashboard, OB-10 reports, the OB-09 sign-off
 * surface (staff and public), escalations, and the OB-08/11/12/13 admin
 * screens.
 *
 * ⚠️ **Stream A in Stream D's `mocks/` (D-004)**, flagged rather than quiet —
 * see `onboardingPrereqs.ts`, which carries the same note for the same reason.
 *
 * **The public sign-off routes are the ones to read carefully.** They mirror
 * four properties the contract states and a permissive mock would quietly
 * drop, each of which a screen has to be built against from the first day:
 *
 *   1. the token is read from the **body**, never a path or query parameter;
 *   2. failures answer **one generic body** — a mock that distinguished
 *      "wrong code" from "no such link" would let OB-09 render two messages
 *      the server will never send;
 *   3. **nothing about the client is returned before the OTP is verified**;
 *   4. acceptance is recorded even when the completion gate refuses the step
 *      (PHASE-2-BUILD-PLAN §3 #4), which is the case most likely to be
 *      implemented as an error and is not one.
 */

// ── the dashboard (OB-02) ───────────────────────────────────────────────────

const CARD_KEYS = [
  'ongoing-projects', 'this-weeks-deadlines', 'todays-delivery',
  'overdue-clients', 'live', 'at-risk', 'client-escalations',
] as const;
type CardKey = (typeof CARD_KEYS)[number];

const COMPUTED_AT = '2026-09-05T06:00:00.000Z';

/**
 * The card counts.
 *
 * Derived here from the same rows the slide-over lists, which is *not* what
 * the server does — it reads `ob_dashboard_summary`. The mock has no refresh
 * job, so deriving is the only way to keep the two consistent; the difference
 * is noted because a screen must not assume the card and its list can never
 * disagree. Against the real server they can, by up to one refresh interval.
 */
function cardCount(key: CardKey, db: Db): number {
  const journeys = db.obClients.flatMap((c) => c.journeys.map((j) => ({ client: c, journey: j })));
  switch (key) {
    case 'ongoing-projects':
      return journeys.filter(({ journey }) => !journey.completedAt && !journey.archivedAt).length;
    case 'this-weeks-deadlines':
      return dashboardItems('this-weeks-deadlines', db).length;
    case 'todays-delivery':
      return dashboardItems('todays-delivery', db).length;
    case 'overdue-clients':
      // Clients, not items — a client late on four services is one to chase.
      return new Set(dashboardItems('overdue-clients', db).map((i) => i.obClientId)).size;
    case 'live':
      return db.obClients.filter((c) => c.status === 'LIVE').length;
    case 'at-risk':
      return journeys.filter(({ journey }) => journey.steps.some((s) => s.status === 'BLOCKED' || s.status === 'WAITING_ON_CLIENT')).length;
    case 'client-escalations':
      return new Set(db.obClientEscalations.filter((e) => !e.resolvedAt).map((e) => e.obClientId)).size;
    default:
      return 0;
  }
}

interface DashItem {
  itemType: 'SERVICE' | 'PREREQUISITE';
  itemId: number; obClientId: number; obClientName: string;
  journeyId: number | null; product: { id: number; code: string; name: string } | null;
  title: string; owner: ReturnType<typeof userRef>; status: string;
  dueAt: string; isOverdue: boolean;
}

const startOfWeek = () => {
  const d = new Date(COMPUTED_AT);
  d.setUTCDate(d.getUTCDate() - ((d.getUTCDay() + 6) % 7));
  d.setUTCHours(0, 0, 0, 0);
  return d;
};

/**
 * The slide-over rows — **services and prerequisites in one list**.
 *
 * Plan §9 requires the deadline cards to cover "all client tasks", so this
 * mixes two tables. That union is the reason the route exists at all rather
 * than the cards deep-linking into `listObJourneys`, which cannot see a
 * prerequisite.
 */
function dashboardItems(key: CardKey, db: Db): DashItem[] {
  const now = new Date(COMPUTED_AT);
  const weekStart = startOfWeek();
  const weekEnd = new Date(weekStart);
  weekEnd.setUTCDate(weekEnd.getUTCDate() + 7);

  const services: DashItem[] = db.obClients.flatMap((c) =>
    c.journeys.flatMap((j) =>
      j.steps
        .filter((s) => s.dueAt && s.status !== 'DONE' && s.status !== 'SKIPPED')
        .map((s) => {
          const p = db.obProducts.find((x) => x.id === j.productId);
          return {
            itemType: 'SERVICE' as const,
            itemId: s.id, obClientId: c.id, obClientName: c.name,
            journeyId: j.id,
            product: p ? { id: p.id, code: p.code, name: p.name } : null,
            title: s.name,
            owner: userRef(s.ownerUserId ?? null, db),
            status: s.status,
            dueAt: s.dueAt as string,
            isOverdue: Date.parse(s.dueAt as string) < now.getTime(),
          };
        }),
    ),
  );

  const prereqs: DashItem[] = db.obClientPrereqTasks
    .filter((t) => t.status === 'PENDING' || t.status === 'SUBMITTED')
    .map((t) => {
      const c = db.obClients.find((x) => x.id === t.obClientId);
      return {
        itemType: 'PREREQUISITE' as const,
        itemId: t.id, obClientId: t.obClientId, obClientName: c?.name ?? '',
        // Null on a prerequisite: the gate sits in front of every journey
        // rather than inside one, and its counterparty is the client rather
        // than an implementor.
        journeyId: null, product: null,
        title: t.title, owner: null, status: t.status,
        dueAt: t.dueAt,
        isOverdue: Date.parse(t.dueAt) < now.getTime(),
      };
    });

  const all = [...services, ...prereqs].sort((a, b) => a.dueAt.localeCompare(b.dueAt));

  switch (key) {
    case 'todays-delivery':
      return all.filter((i) => i.dueAt.slice(0, 10) === COMPUTED_AT.slice(0, 10));
    case 'this-weeks-deadlines':
      return all.filter((i) => {
        const t = Date.parse(i.dueAt);
        return t >= weekStart.getTime() && t < weekEnd.getTime();
      });
    case 'overdue-clients':
      return all.filter((i) => i.isOverdue);
    case 'at-risk':
      return all.filter((i) => i.status === 'BLOCKED' || i.status === 'WAITING_ON_CLIENT');
    case 'client-escalations':
      return all.filter((i) =>
        db.obClientEscalations.some((e) => !e.resolvedAt && e.stepId === i.itemId && i.itemType === 'SERVICE'));
    default:
      return all;
  }
}

// ── reports (OB-10) ─────────────────────────────────────────────────────────
//
// Twelve descriptors: plan §10's set. Seven `available`, five not — the OB4b
// group PHASE-2-BUILD-PLAN §3 #8 holds pending the §11.6 call. They are listed
// rather than hidden so an undecided report is distinguishable from one that
// does not exist, which is what the decision is about.
const OB_REPORTS = [
  { key: 'journey-funnel', title: 'Journey funnel by product', description: 'Where journeys sit, per product.', category: 'DELIVERY', chart: 'funnel', filters: ['dateRange', 'product'], available: true },
  { key: 'tat-compliance', title: 'TAT compliance by service and owner', description: 'On-time delivery against pinned TATs.', category: 'QUALITY', chart: 'bar', filters: ['dateRange', 'product', 'owner'], available: true },
  { key: 'stuck-and-aging', title: 'Stuck & aging', description: 'Block reasons and client-attributed waits, prerequisites included.', category: 'CLIENT', chart: 'bar', filters: ['dateRange', 'product', 'rag'], available: true },
  { key: 'time-to-live', title: 'Time to live, per product', description: 'How long boarding actually takes.', category: 'DELIVERY', chart: 'line', filters: ['dateRange', 'product'], available: true },
  { key: 'sales-pipeline', title: 'Sales pipeline', description: 'Boarded clients by sales person.', category: 'PIPELINE', chart: 'bar', filters: ['dateRange'], available: true },
  { key: 'signoff-pending', title: 'Sign-offs pending', description: 'Requested and unanswered, oldest first.', category: 'CLIENT', chart: null, filters: ['dateRange', 'client'], available: true },
  { key: 'prereq-aging', title: 'Prerequisite aging', description: 'Client-attributed time before the gate.', category: 'CLIENT', chart: 'bar', filters: ['dateRange', 'client'], available: true },
  { key: 'breach-log', title: 'Breach log', description: 'Every TAT breach, with its ladder.', category: 'QUALITY', chart: null, filters: ['dateRange', 'product', 'owner'], available: false, unavailableReason: 'Held as OB4b pending the reports decision (PHASE-2-BUILD-PLAN §11.6).' },
  { key: 'escalation-log', title: 'Escalation log', description: 'Internal and client escalations side by side.', category: 'QUALITY', chart: null, filters: ['dateRange', 'client'], available: false, unavailableReason: 'Held as OB4b pending the reports decision (PHASE-2-BUILD-PLAN §11.6).' },
  { key: 'owner-workload', title: 'Owner workload', description: 'Open services per implementor over time.', category: 'DELIVERY', chart: 'stacked-bar', filters: ['dateRange', 'owner'], available: false, unavailableReason: 'Held as OB4b pending the reports decision (PHASE-2-BUILD-PLAN §11.6).' },
  { key: 'communication-audit', title: 'Communication audit per client', description: 'Every recorded conversation, chronologically.', category: 'CLIENT', chart: null, filters: ['dateRange', 'client'], available: false, unavailableReason: 'Held as OB4b pending the reports decision (PHASE-2-BUILD-PLAN §11.6).' },
  { key: 'csat-summary', title: 'CSAT summary', description: 'Go-live survey scores by product.', category: 'QUALITY', chart: 'donut', filters: ['dateRange', 'product'], available: false, unavailableReason: 'Held as OB4b pending the reports decision (PHASE-2-BUILD-PLAN §11.6).' },
];

// ── sign-off ────────────────────────────────────────────────────────────────

const contactOf = (db: Db, obClientId: number, contactId: number) =>
  db.obClients.find((c) => c.id === obClientId)?.contacts.find((x) => x.id === contactId) ?? null;

function signoffDto(s: ObSignoffRow, db: Db) {
  return {
    id: s.id, obClientId: s.obClientId, journeyId: s.journeyId, stepId: s.stepId,
    kind: s.kind, status: s.status,
    requestedBy: userRef(s.requestedById, db),
    requestedAt: s.requestedAt,
    sentToContact: contactOf(db, s.obClientId, s.sentToContactId),
    tokenExpiresAt: s.tokenExpiresAt,
    signedAt: s.signedAt, objectedAt: s.objectedAt,
    hasCertificate: s.pdfStorageKey != null,
    // token, otp and their hashes appear on no response, ever.
  };
}

const signoffDetailDto = (s: ObSignoffRow, db: Db) => ({
  ...signoffDto(s, db),
  signedByContact: s.signedByContactId ? contactOf(db, s.obClientId, s.signedByContactId) : null,
  signedIp: s.signedIp, signedUserAgent: s.signedUserAgent,
  objectionNote: s.objectionNote,
});

/**
 * One body for every public failure — see property 2 in the file header.
 *
 * A caller who can tell "wrong code" from "no such link" can enumerate links,
 * so the mock must not be more forthcoming than the server.
 */
const publicDenied = () =>
  problem(401, 'ob-signoff-invalid', 'That link or code is not valid.');

function resolveSession(db: Db, token: unknown) {
  if (typeof token !== 'string') return null;
  const session = db.obSignoffSessions.find((s) => s.token === token && !s.used);
  if (!session || Date.parse(session.expiresAt) < Date.now()) return null;
  const signoff = db.obSignoffs.find((s) => s.id === session.signoffId);
  return signoff ? { session, signoff } : null;
}

// ── escalations ─────────────────────────────────────────────────────────────

const escalationDto = (e: ObEscalationRow, db: Db) => ({
  id: e.id, obClientId: e.obClientId, journeyId: e.journeyId, stepId: e.stepId,
  stepTitle: stepTitle(db, e.obClientId, e.stepId),
  obClientName: db.obClients.find((c) => c.id === e.obClientId)?.name ?? '',
  level: e.level, reason: e.reason,
  escalatedTo: userRef(e.escalatedToId, db), escalatedAt: e.escalatedAt,
  acknowledgedBy: userRef(e.acknowledgedById, db), acknowledgedAt: e.acknowledgedAt,
  resolvedBy: userRef(e.resolvedById, db), resolvedAt: e.resolvedAt,
  resolutionNote: e.resolutionNote,
});

const clientEscalationDto = (e: ObClientEscalationRow, db: Db) => ({
  id: e.id, obClientId: e.obClientId,
  obClientName: db.obClients.find((c) => c.id === e.obClientId)?.name ?? '',
  journeyId: e.journeyId, stepId: e.stepId,
  stepTitle: stepTitle(db, e.obClientId, e.stepId),
  raisedByContact: contactOf(db, e.obClientId, e.raisedByContactId),
  comment: e.comment, raisedAt: e.raisedAt,
  resolvedBy: userRef(e.resolvedById, db), resolvedAt: e.resolvedAt,
  resolutionNote: e.resolutionNote,
});

function stepTitle(db: Db, obClientId: number, stepId: number): string {
  const client = db.obClients.find((c) => c.id === obClientId);
  for (const j of client?.journeys ?? []) {
    const s = j.steps.find((x) => x.id === stepId);
    if (s) return s.name;
  }
  return '';
}

// ── module access, settings, notifications ──────────────────────────────────

const ACCESS_TOKEN_LIFETIME_SECONDS = 900;

const grantDto = (g: ObModuleAccessRow, db: Db) => ({
  id: g.id, user: userRef(g.userId, db), module: g.module, moduleRole: g.moduleRole,
  grantedBy: userRef(g.grantedById, db), grantedAt: g.grantedAt,
  isLive: g.revokedAt == null,
  revokedBy: userRef(g.revokedById, db), revokedAt: g.revokedAt,
  // A revoke is not instant: the entitlement rides in the access token.
  tokenLagSeconds: ACCESS_TOKEN_LIFETIME_SECONDS,
});

const settingsDto = (db: Db) => ({
  ...db.obSettings,
  updatedBy: userRef(db.obSettings.updatedById, db),
  updatedById: undefined,
});

/** Derived from the category, never stored — so a new escalation event is covered on arrival. */
const isMandatory = (t: ObNotificationTemplateRow) =>
  t.channel === 'EMAIL' && (t.category === 'ESCALATION' || t.category === 'SIGNOFF');

/** False for WHATSAPP in phase 2 — no adapter exists (PHASE-2-BUILD-PLAN §6.1). */
const isDeliverable = (t: ObNotificationTemplateRow) => t.channel !== 'WHATSAPP';

const templateDto = (t: ObNotificationTemplateRow) => ({
  id: t.id, eventCode: t.eventCode, category: t.category, channel: t.channel,
  recipients: t.recipients, subjectTemplate: t.subjectTemplate,
  bodyTemplate: t.bodyTemplate, isActive: t.isActive,
  isMandatory: isMandatory(t), isDeliverable: isDeliverable(t),
});

const MERGE_TAGS = [
  '{{client_name}}', '{{task_title}}', '{{step_title}}', '{{product_name}}',
  '{{owner_name}}', '{{due_date}}', '{{reason}}', '{{link}}',
];

export const obAdminHandlers = [
  // ── OB-02 ─────────────────────────────────────────────────────────────────
  http.get(url('/onboarding/dashboard/summary'), () => {
    const db = getDb();
    return ok({
      // All seven, always — an absent card and a card reading nought are
      // different claims, and only one of them is true when nothing is overdue.
      cards: CARD_KEYS.map((key) => ({ key, count: cardCount(key, db), deltaFromYesterday: null })),
      computedAt: COMPUTED_AT,
      appliedScope: 'all clients',
    });
  }),

  http.get(url('/onboarding/dashboard/cards/:cardKey/items'), ({ params, request }) => {
    const key = String(params.cardKey) as CardKey;
    if (!CARD_KEYS.includes(key)) {
      return problem(400, 'unknown-card', `Unknown card key "${key}".`);
    }
    const requestUrl = new URL(request.url);
    const db = getDb();
    let items = dashboardItems(key, db);

    const productId = requestUrl.searchParams.get('productId');
    if (productId) items = items.filter((i) => i.product?.id === Number(productId));
    const ownerUserId = requestUrl.searchParams.get('ownerUserId');
    if (ownerUserId) items = items.filter((i) => i.owner?.id === Number(ownerUserId));

    const { page, meta } = paginate(items, requestUrl);
    return ok(page, { ...meta, computedAt: COMPUTED_AT });
  }),

  http.get(url('/onboarding/dashboard/delayed-projects'), ({ request }) => {
    const db = getDb();
    const requestUrl = new URL(request.url);
    const now = Date.parse(COMPUTED_AT);

    let rows = db.obClients.flatMap((c) =>
      c.journeys
        .filter((j) => !j.completedAt && !j.archivedAt)
        .map((j) => {
          const overdue = j.steps.filter((s) => s.dueAt && Date.parse(s.dueAt) < now && s.status !== 'DONE');
          if (overdue.length === 0) return null;
          const worst = Math.min(...overdue.map((s) => Date.parse(s.dueAt as string)));
          // Working days, through the calendar — a naive subtraction would
          // spike every Monday until readers learned to discount it.
          const calendarDays = Math.floor((now - worst) / 86_400_000);
          const weekends = Math.floor(calendarDays / 7) * 2;
          const current = j.steps.find((s) => s.status === 'IN_PROGRESS') ?? overdue[0];
          const p = db.obProducts.find((x) => x.id === j.productId);
          return {
            journeyId: j.id, obClientId: c.id, obClientName: c.name,
            startedAt: j.startedAt ?? null,
            productsBought: c.applications
              .map((a) => db.obProducts.find((x) => x.id === a.productId))
              .filter((x): x is NonNullable<typeof x> => x != null)
              .map((x) => ({ id: x.id, code: x.code, name: x.name })),
            product: p ? { id: p.id, code: p.code, name: p.name } : { id: j.productId, code: '', name: '' },
            currentStep: current
              ? { id: current.id, sequence: current.sequence, name: current.name, status: current.status, rag: stepRag(current), dependsOnStepId: current.dependsOnStepId }
              : null,
            responsible: userRef(current?.ownerUserId ?? null, db),
            expectedCompletionAt: current?.dueAt ?? null,
            delayedByDays: Math.max(1, calendarDays - weekends),
          };
        })
        .filter((r): r is NonNullable<typeof r> => r != null),
    );

    const minDelay = requestUrl.searchParams.get('minDelayDays');
    if (minDelay) rows = rows.filter((r) => r.delayedByDays >= Number(minDelay));
    const productId = requestUrl.searchParams.get('productId');
    if (productId) rows = rows.filter((r) => r.product.id === Number(productId));
    const ownerUserId = requestUrl.searchParams.get('ownerUserId');
    if (ownerUserId) rows = rows.filter((r) => r.responsible?.id === Number(ownerUserId));

    rows.sort((a, b) => b.delayedByDays - a.delayedByDays);
    const { page, meta } = paginate(rows, requestUrl);
    return ok(page, meta);
  }),

  http.get(url('/onboarding/dashboard/implementor-workload'), ({ request }) => {
    const db = getDb();
    const requestUrl = new URL(request.url);
    const includeInactive = requestUrl.searchParams.get('includeInactive') === 'true';
    const statDate = requestUrl.searchParams.get('statDate') ?? COMPUTED_AT.slice(0, 10);

    // **A row per implementor, including those with zero clients.** The
    // population is everyone holding an OB_STEP_OWNER grant, left-joined to
    // their steps — not a group-by over open steps, which would drop somebody
    // who has just finished everything and show them as absent rather than clear.
    const grants = db.obModuleAccess.filter(
      (g) => g.module === 'ONBOARDING' && g.moduleRole === 'OB_STEP_OWNER' && (includeInactive || g.revokedAt == null),
    );

    const rows = grants.map((g) => {
      const steps = db.obClients.flatMap((c) =>
        c.journeys.flatMap((j) => j.steps.filter((s) => s.ownerUserId === g.userId).map((s) => ({ client: c, step: s }))));
      const openClients = new Set(steps.filter(({ step }) => step.status !== 'DONE').map(({ client }) => client.id));
      const blockedWaiting = new Set(steps.filter(({ step }) => step.status === 'BLOCKED' || step.status === 'WAITING_ON_CLIENT').map(({ client }) => client.id));
      const notStarted = new Set(steps.filter(({ step }) => step.status === 'PENDING').map(({ client }) => client.id));
      const delayed = new Set(steps.filter(({ step }) => step.dueAt && step.status !== 'DONE' && Date.parse(step.dueAt) < Date.parse(COMPUTED_AT)).map(({ client }) => client.id));
      for (const id of blockedWaiting) { notStarted.delete(id); delayed.delete(id); }
      for (const id of notStarted) delayed.delete(id);
      // The six columns partition clientsOpen and must sum to it — an
      // arithmetic contract a screen will assume and nothing checks at runtime.
      const onTrack = openClients.size - blockedWaiting.size - notStarted.size - delayed.size;
      const completedOnTime = steps.filter(({ step }) => step.status === 'DONE').length;
      return {
        user: userRef(g.userId, db),
        isActive: g.revokedAt == null,
        clientsOpen: openClients.size,
        onTrack: Math.max(0, onTrack),
        notStarted: notStarted.size,
        delayed: delayed.size,
        atRisk: 0,
        blockedWaiting: blockedWaiting.size,
        aheadOfSchedule: 0,
        completedOnTime,
        completedEarly: 0,
        completedLate: 0,
        blockedHours: blockedWaiting.size * 6,
        // Derived on read, never stored — and null with no completions,
        // because scoring an empty set produces a number that looks like
        // judgement and is arithmetic.
        performanceScore: completedOnTime === 0 ? null : Math.min(100, 60 + completedOnTime * 8 - delayed.size * 5),
        statDate,
      };
    });

    const { page, meta } = paginate(rows, requestUrl);
    return ok(page, meta);
  }),

  // ── OB-10 ─────────────────────────────────────────────────────────────────
  http.get(url('/onboarding/reports'), () => ok({ reports: OB_REPORTS, scopeNote: null })),

  http.get(url('/onboarding/reports/:reportKey'), ({ params }) => {
    const key = String(params.reportKey);
    const descriptor = OB_REPORTS.find((r) => r.key === key);
    // 404 for an unknown key *and* for one that is not built: by the time a
    // caller is running it there are no rows to describe and no columns to name.
    if (!descriptor || !descriptor.available) return notFound('Report');

    const db = getDb();
    const rows = db.obProducts.map((p) => {
      const journeys = db.obClients.flatMap((c) => c.journeys.filter((j) => j.productId === p.id));
      return {
        product: p.name,
        journeys: journeys.length,
        locked: journeys.filter((j) => j.gateStatus === 'LOCKED').length,
        running: journeys.filter((j) => j.gateStatus === 'OPEN' && !j.completedAt).length,
        completed: journeys.filter((j) => j.completedAt).length,
      };
    });
    return ok({
      reportKey: key,
      columns: [
        { key: 'product', label: 'Product', type: 'string' },
        { key: 'journeys', label: 'Journeys', type: 'number' },
        { key: 'locked', label: 'Prerequisites pending', type: 'number' },
        { key: 'running', label: 'Running', type: 'number' },
        { key: 'completed', label: 'Completed', type: 'number' },
      ],
      rows,
    }, { appliedScope: 'all clients', computedAt: COMPUTED_AT });
  }),

  // ── sign-off, staff side ──────────────────────────────────────────────────
  http.get(url('/onboarding/signoffs'), ({ request }) => {
    const db = getDb();
    const requestUrl = new URL(request.url);
    let rows = [...db.obSignoffs];
    const byId = (p: string) => requestUrl.searchParams.get(p);
    if (byId('obClientId')) rows = rows.filter((s) => s.obClientId === Number(byId('obClientId')));
    if (byId('journeyId')) rows = rows.filter((s) => s.journeyId === Number(byId('journeyId')));
    if (byId('kind')) rows = rows.filter((s) => s.kind === byId('kind'));
    if (byId('status')) rows = rows.filter((s) => s.status === byId('status'));
    rows.sort((a, b) => b.requestedAt.localeCompare(a.requestedAt));
    const { page, meta } = paginate(rows, requestUrl);
    return ok(page.map((s) => signoffDto(s, db)), meta);
  }),

  http.post(url('/onboarding/journeys/:journeyId/signoffs'), async ({ params, request }) => {
    const db = getDb();
    const journeyId = Number(params.journeyId);
    const client = db.obClients.find((c) => c.journeys.some((j) => j.id === journeyId));
    const journey = client?.journeys.find((j) => j.id === journeyId);
    if (!client || !journey) return notFound('Journey');

    const body = (await request.json()) as { kind?: 'STEP' | 'GO_LIVE'; stepId?: number | null; sentToContactId?: number };
    if (!body.kind || !body.sentToContactId) {
      return validationFailed({ kind: ['is required'], sentToContactId: ['is required'] });
    }
    // The database's own ck_ob_signoffs_step_matches_kind, answered earlier.
    if (body.kind === 'STEP' && !body.stepId) return validationFailed({ stepId: ['is required for a STEP sign-off'] });
    if (body.kind === 'GO_LIVE' && body.stepId) return validationFailed({ stepId: ['must be absent for a GO_LIVE sign-off'] });

    if (body.kind === 'GO_LIVE' && journey.steps.some((s) => s.status !== 'DONE' && s.status !== 'SKIPPED')) {
      return problem(422, 'ob-signoff-journey-incomplete',
        'Every service must be finished before a go-live sign-off is requested.');
    }
    const duplicate = db.obSignoffs.some((s) =>
      s.status === 'PENDING' && s.journeyId === journeyId
      && (body.kind === 'GO_LIVE' ? s.kind === 'GO_LIVE' : s.stepId === body.stepId));
    if (duplicate) {
      return problem(409, 'ob-signoff-already-pending',
        'A sign-off is already outstanding for this. Resend it rather than creating a second live link.');
    }

    const row: ObSignoffRow = {
      id: nextId(db, 'obSignoff') + 10,
      obClientId: client.id, journeyId, stepId: body.stepId ?? null,
      kind: body.kind, status: 'PENDING',
      token: `ob-signoff-${nextId(db, 'obSignoffToken')}-${Date.now()}`,
      tokenExpiresAt: new Date(Date.now() + 14 * 86_400_000).toISOString(),
      otp: null, otpAttempts: 0,
      requestedById: currentUser(db).id, requestedAt: new Date().toISOString(),
      sentToContactId: body.sentToContactId,
      signedByContactId: null, signedAt: null, signedIp: null, signedUserAgent: null,
      objectedAt: null, objectionNote: null, pdfStorageKey: null,
      csatScore: null, csatComment: null,
    };
    db.obSignoffs.push(row);
    return ok(signoffDto(row, db), undefined, { status: 201 });
  }),

  http.get(url('/onboarding/signoffs/:signoffId'), ({ params }) => {
    const db = getDb();
    const s = db.obSignoffs.find((x) => x.id === Number(params.signoffId));
    return s ? ok(signoffDetailDto(s, db)) : notFound('Sign-off');
  }),

  http.post(url('/onboarding/signoffs/:signoffId/resend'), ({ params }) => {
    const db = getDb();
    const s = db.obSignoffs.find((x) => x.id === Number(params.signoffId));
    if (!s) return notFound('Sign-off');
    if (s.status !== 'PENDING' && s.status !== 'EXPIRED') {
      return unprocessable('A settled decision is not re-asked. Request a new sign-off instead.');
    }
    // A new token, and the old one stops working — two live links to one
    // decision would leave nothing to say which was used.
    s.token = `ob-signoff-${nextId(db, 'obSignoffToken')}-${Date.now()}`;
    s.tokenExpiresAt = new Date(Date.now() + 14 * 86_400_000).toISOString();
    s.status = 'PENDING';
    s.otp = null;
    // The lockout dies with the link it was protecting.
    s.otpAttempts = 0;
    return ok(signoffDto(s, db));
  }),

  http.post(url('/onboarding/signoffs/:signoffId/cancel'), async ({ params, request }) => {
    const db = getDb();
    const s = db.obSignoffs.find((x) => x.id === Number(params.signoffId));
    if (!s) return notFound('Sign-off');
    if (s.status === 'SIGNED' || s.status === 'OBJECTED') return unprocessable('This decision has already been made.');
    const { reason } = (await request.json()) as { reason?: string };
    if (!reason?.trim()) return validationFailed({ reason: ['must not be blank'] });
    s.status = 'CANCELLED';
    s.token = `cancelled-${s.id}`;
    return ok(signoffDto(s, db));
  }),

  http.get(url('/onboarding/signoffs/:signoffId/certificate'), ({ params }) => {
    const db = getDb();
    const s = db.obSignoffs.find((x) => x.id === Number(params.signoffId));
    // A certificate for a decision nobody has made does not exist.
    if (!s || s.status !== 'SIGNED' || !s.pdfStorageKey) return notFound('Certificate');
    return new HttpResponse(new Blob(['%PDF-1.4 mock signoff certificate'], { type: 'application/pdf' }), {
      headers: { 'Content-Type': 'application/pdf' },
    });
  }),

  // ── sign-off, public (A-120, A-121) ───────────────────────────────────────
  http.post(url('/public/onboarding/signoff/otp'), async ({ request }) => {
    const db = getDb();
    const { token } = (await request.json()) as { token?: string };
    if (!token) return validationFailed({ token: ['is required'] });
    const s = db.obSignoffs.find((x) => x.token === token && x.status === 'PENDING');
    // The code goes to the contact on the row, never to an address in the
    // request — a body naming its own recipient would make the OTP a formality.
    if (s && Date.parse(s.tokenExpiresAt) > Date.now()) {
      s.otp = '123456';
      s.otpAttempts = 0;
    }
    // 202 whatever happened. Saying whether a code was sent is the enumeration
    // oracle this surface exists to close.
    return new HttpResponse(null, { status: 202 });
  }),

  http.post(url('/public/onboarding/signoff/otp/verify'), async ({ request }) => {
    const db = getDb();
    const { token, otp } = (await request.json()) as { token?: string; otp?: string };
    if (!token || !otp) return validationFailed({ token: ['is required'], otp: ['is required'] });

    const s = db.obSignoffs.find((x) => x.token === token && x.status === 'PENDING');
    if (!s || Date.parse(s.tokenExpiresAt) < Date.now() || s.otpAttempts >= 5) return publicDenied();
    if (s.otp !== otp) {
      s.otpAttempts += 1;
      return publicDenied();
    }

    const session = {
      token: `ob-session-${nextId(db, 'obSignoffSession')}-${Date.now()}`,
      signoffId: s.id,
      expiresAt: new Date(Date.now() + 15 * 60_000).toISOString(),
      used: false,
    };
    db.obSignoffSessions.push(session);

    const client = db.obClients.find((c) => c.id === s.obClientId);
    const journey = client?.journeys.find((j) => j.id === s.journeyId);
    const step = s.stepId ? journey?.steps.find((x) => x.id === s.stepId) : undefined;
    const product = db.obProducts.find((p) => p.id === journey?.productId);

    // Only now does anything about the client leave the building.
    return ok({
      sessionToken: session.token,
      expiresAt: session.expiresAt,
      kind: s.kind,
      obClientName: client?.name ?? '',
      productName: product?.name ?? null,
      stepTitle: step?.name ?? null,
      checklist: (step?.items ?? []).map((i) => ({
        id: i.id, stepId: step?.id ?? 0, label: i.label,
        isDone: i.isDone, doneAt: i.doneAt ?? null, doneBy: userRef(i.doneById ?? null, db),
      })),
      csatOffered: s.kind === 'GO_LIVE' && s.csatScore == null,
    });
  }),

  http.post(url('/public/onboarding/signoff/accept'), async ({ request }) => {
    const db = getDb();
    const body = (await request.json()) as { sessionToken?: string; acceptedName?: string; note?: string | null };
    if (!body.acceptedName?.trim()) return validationFailed({ acceptedName: ['must not be blank'] });
    const resolved = resolveSession(db, body.sessionToken);
    if (!resolved) return publicDenied();
    const { session, signoff } = resolved;

    // The acceptance is recorded FIRST and unconditionally. The client did
    // accept; they are not the ones who left a document unattached.
    signoff.status = 'SIGNED';
    signoff.signedByContactId = signoff.sentToContactId;
    signoff.signedAt = new Date().toISOString();
    signoff.signedIp = '203.0.113.9';
    signoff.signedUserAgent = request.headers.get('user-agent') ?? 'mock';
    signoff.pdfStorageKey = `ob-signoffs/${signoff.id}/certificate.pdf`;

    const client = db.obClients.find((c) => c.id === signoff.obClientId);
    const journey = client?.journeys.find((j) => j.id === signoff.journeyId);
    const step = signoff.stepId ? journey?.steps.find((x) => x.id === signoff.stepId) : undefined;

    // Then the same completion gate every other route uses — PHASE-2-BUILD-PLAN
    // §3 #4. Failing it leaves the step IN_PROGRESS and keeps the acceptance.
    const gateFailures: string[] = [];
    if (step) {
      if (step.items?.some((i) => !i.isDone)) gateFailures.push('ob-step-items-unanswered');
      if (step.docs?.some((d) => d.isRequired && !d.attachmentId)) gateFailures.push('ob-step-docs-missing');
      if (gateFailures.length === 0) {
        step.status = 'DONE';
        step.finishedAt = new Date().toISOString();
      }
    }

    let clientWentLive = false;
    if (signoff.kind === 'GO_LIVE' && client) {
      const allDone = client.journeys.every((j) => j.steps.every((s) => s.status === 'DONE' || s.status === 'SKIPPED'));
      if (allDone && client.status !== 'LIVE') {
        client.status = 'LIVE';
        client.liveAt = new Date().toISOString();
        clientWentLive = true;
      }
    }

    session.used = true;
    return ok({
      signoff: signoffDetailDto(signoff, db),
      stepCompleted: step ? gateFailures.length === 0 : false,
      gateFailures,
      clientWentLive,
    });
  }),

  http.post(url('/public/onboarding/signoff/object'), async ({ request }) => {
    const db = getDb();
    const body = (await request.json()) as { sessionToken?: string; note?: string };
    if (!body.note?.trim()) return validationFailed({ note: ['must not be blank'] });
    const resolved = resolveSession(db, body.sessionToken);
    if (!resolved) return publicDenied();
    const { session, signoff } = resolved;

    signoff.status = 'OBJECTED';
    signoff.objectedAt = new Date().toISOString();
    signoff.objectionNote = body.note;

    // The step reverts — plan §8.
    const client = db.obClients.find((c) => c.id === signoff.obClientId);
    const journey = client?.journeys.find((j) => j.id === signoff.journeyId);
    const step = signoff.stepId ? journey?.steps.find((x) => x.id === signoff.stepId) : undefined;
    if (step) step.status = 'IN_PROGRESS';

    session.used = true;
    return ok(signoffDto(signoff, db));
  }),

  http.post(url('/public/onboarding/signoff/csat'), async ({ request }) => {
    const db = getDb();
    const body = (await request.json()) as { sessionToken?: string; score?: number; comment?: string | null };
    if (!body.score || body.score < 1 || body.score > 5) {
      return validationFailed({ score: ['must be between 1 and 5'] });
    }
    // A used session is fine here: CSAT follows acceptance on the same token.
    const session = db.obSignoffSessions.find((s) => s.token === body.sessionToken);
    const signoff = session ? db.obSignoffs.find((s) => s.id === session.signoffId) : undefined;
    if (!session || !signoff || Date.parse(session.expiresAt) < Date.now()) return publicDenied();
    if (signoff.kind !== 'GO_LIVE') return unprocessable('CSAT is asked once, with the go-live sign-off.');
    if (signoff.csatScore != null) return unprocessable('This go-live has already been surveyed.');

    signoff.csatScore = body.score;
    signoff.csatComment = body.comment ?? null;
    return new HttpResponse(null, { status: 200 });
  }),

  // ── escalations ───────────────────────────────────────────────────────────
  http.get(url('/onboarding/escalations'), ({ request }) => {
    const db = getDb();
    const requestUrl = new URL(request.url);
    const state = requestUrl.searchParams.get('state') ?? 'OPEN';
    let rows = db.obEscalations.filter((e) => {
      // OPEN means unresolved. An acknowledged rung is still open —
      // acknowledging says somebody has seen it, not that anything is fixed.
      if (state === 'OPEN') return e.resolvedAt == null;
      if (state === 'ACKNOWLEDGED') return e.resolvedAt == null && e.acknowledgedAt != null;
      return e.resolvedAt != null;
    });
    const p = (k: string) => requestUrl.searchParams.get(k);
    if (p('obClientId')) rows = rows.filter((e) => e.obClientId === Number(p('obClientId')));
    if (p('journeyId')) rows = rows.filter((e) => e.journeyId === Number(p('journeyId')));
    if (p('escalatedTo')) rows = rows.filter((e) => e.escalatedToId === Number(p('escalatedTo')));
    if (p('level')) rows = rows.filter((e) => e.level === p('level'));
    rows.sort((a, b) => a.escalatedAt.localeCompare(b.escalatedAt));
    const { page, meta } = paginate(rows, requestUrl);
    return ok(page.map((e) => escalationDto(e, db)), meta);
  }),

  http.post(url('/onboarding/escalations/:escalationId/acknowledge'), ({ params }) => {
    const db = getDb();
    const e = db.obEscalations.find((x) => x.id === Number(params.escalationId));
    if (!e) return notFound('Escalation');
    if (e.resolvedAt) return unprocessable('This rung is already resolved.');
    // Idempotent: the timestamp keeps saying when it was FIRST seen.
    if (!e.acknowledgedAt) {
      e.acknowledgedAt = new Date().toISOString();
      e.acknowledgedById = currentUser(db).id;
    }
    return ok(escalationDto(e, db));
  }),

  http.post(url('/onboarding/escalations/:escalationId/resolve'), async ({ params, request }) => {
    const db = getDb();
    const e = db.obEscalations.find((x) => x.id === Number(params.escalationId));
    if (!e) return notFound('Escalation');
    if (e.resolvedAt) return unprocessable('This rung is already resolved.');
    const { note } = (await request.json()) as { note?: string };
    if (!note?.trim()) return validationFailed({ note: ['must not be blank'] });
    // Resolving L1 does not resolve L2 or L3 — each went to a different person.
    e.resolvedAt = new Date().toISOString();
    e.resolvedById = currentUser(db).id;
    e.resolutionNote = note;
    return ok(escalationDto(e, db));
  }),

  http.get(url('/onboarding/client-escalations'), ({ request }) => {
    const db = getDb();
    const requestUrl = new URL(request.url);
    const state = requestUrl.searchParams.get('state') ?? 'OPEN';
    let rows = db.obClientEscalations.filter((e) => (state === 'OPEN' ? e.resolvedAt == null : e.resolvedAt != null));
    const p = (k: string) => requestUrl.searchParams.get(k);
    if (p('obClientId')) rows = rows.filter((e) => e.obClientId === Number(p('obClientId')));
    if (p('journeyId')) rows = rows.filter((e) => e.journeyId === Number(p('journeyId')));
    rows.sort((a, b) => b.raisedAt.localeCompare(a.raisedAt));
    const { page, meta } = paginate(rows, requestUrl);
    return ok(page.map((e) => clientEscalationDto(e, db)), meta);
  }),

  http.post(url('/onboarding/client-escalations/:escalationId/resolve'), async ({ params, request }) => {
    const db = getDb();
    const e = db.obClientEscalations.find((x) => x.id === Number(params.escalationId));
    if (!e) return notFound('Escalation');
    if (e.resolvedAt) return unprocessable('This escalation is already resolved.');
    const { note } = (await request.json()) as { note?: string };
    if (!note?.trim()) return validationFailed({ note: ['must not be blank'] });
    // This note goes to the client — the one difference from the internal ladder.
    e.resolvedAt = new Date().toISOString();
    e.resolvedById = currentUser(db).id;
    e.resolutionNote = note;
    return ok(clientEscalationDto(e, db));
  }),

  // ── OB-08 ─────────────────────────────────────────────────────────────────
  http.get(url('/onboarding/module-access'), ({ request }) => {
    const db = getDb();
    const requestUrl = new URL(request.url);
    const includeRevoked = requestUrl.searchParams.get('includeRevoked') === 'true';
    let rows = db.obModuleAccess.filter((g) => includeRevoked || g.revokedAt == null);
    const p = (k: string) => requestUrl.searchParams.get(k);
    if (p('module')) rows = rows.filter((g) => g.module === p('module'));
    if (p('userId')) rows = rows.filter((g) => g.userId === Number(p('userId')));
    if (p('moduleRole')) rows = rows.filter((g) => g.moduleRole === p('moduleRole'));
    rows.sort((a, b) => a.grantedAt.localeCompare(b.grantedAt));
    const { page, meta } = paginate(rows, requestUrl);
    return ok(page.map((g) => grantDto(g, db)), meta);
  }),

  http.post(url('/onboarding/module-access'), async ({ request }) => {
    const db = getDb();
    const body = (await request.json()) as Partial<ObModuleAccessRow>;
    if (!body.userId || !body.module || !body.moduleRole) {
      return validationFailed({ userId: ['is required'], module: ['is required'], moduleRole: ['is required'] });
    }
    // One live grant per (user, module): two would mean two module roles and
    // nothing says which the guard should believe.
    if (db.obModuleAccess.some((g) => g.userId === body.userId && g.module === body.module && g.revokedAt == null)) {
      return problem(409, 'ob-module-access-exists',
        'This user already holds a live grant for that module. Revoke it first — a role change leaves both facts in the audit trail.');
    }
    const row: ObModuleAccessRow = {
      id: nextId(db, 'obModuleAccess') + 10,
      userId: body.userId, module: body.module, moduleRole: body.moduleRole,
      grantedById: currentUser(db).id, grantedAt: new Date().toISOString(),
      revokedById: null, revokedAt: null,
    };
    db.obModuleAccess.push(row);
    return ok(grantDto(row, db), undefined, { status: 201 });
  }),

  http.post(url('/onboarding/module-access/:grantId/revoke'), ({ params }) => {
    const db = getDb();
    const g = db.obModuleAccess.find((x) => x.id === Number(params.grantId));
    if (!g) return notFound('Grant');
    if (g.revokedAt) return unprocessable('This grant is already revoked.');
    // The last administrator cannot be removed: a module with no admin cannot
    // grant anybody access to itself, and recovering means a database edit.
    const liveAdmins = db.obModuleAccess.filter(
      (x) => x.module === g.module && x.moduleRole === 'OB_ADMIN' && x.revokedAt == null);
    if (g.moduleRole === 'OB_ADMIN' && liveAdmins.length <= 1) {
      return unprocessable('This is the last live OB_ADMIN grant. Grant another administrator first.');
    }
    // Revoked, not deleted — an access audit has to be able to answer.
    g.revokedAt = new Date().toISOString();
    g.revokedById = currentUser(db).id;
    return ok(grantDto(g, db));
  }),

  // ── OB-11 ─────────────────────────────────────────────────────────────────
  http.get(url('/onboarding/settings'), () => ok(settingsDto(getDb()))),

  http.put(url('/onboarding/settings'), async ({ request }) => {
    const db = getDb();
    const body = (await request.json()) as {
      amberThresholdPercent?: number; scannerIntervalMinutes?: number;
      ladder?: { level: 'L1' | 'L2' | 'L3'; afterWorkingHours: number; recipient: string }[];
    };
    if (!body.amberThresholdPercent || body.amberThresholdPercent < 1 || body.amberThresholdPercent > 99) {
      return validationFailed({ amberThresholdPercent: ['must be between 1 and 99'] });
    }
    if (!body.scannerIntervalMinutes || body.scannerIntervalMinutes < 1 || body.scannerIntervalMinutes > 60) {
      return validationFailed({ scannerIntervalMinutes: ['must be between 1 and 60'] });
    }
    if (!body.ladder || body.ladder.length !== 3) {
      return validationFailed({ ladder: ['must contain exactly three rungs'] });
    }
    // A ladder whose L3 fires before its L2 is not a ladder, and the failure
    // would surface weeks later as an escalation that overtook its own warning.
    const ascending = body.ladder.every((r, i) => i === 0 || r.afterWorkingHours >= body.ladder![i - 1].afterWorkingHours);
    if (!ascending) return validationFailed({ ladder: ['rungs must ascend by afterWorkingHours'] });

    db.obSettings = {
      amberThresholdPercent: body.amberThresholdPercent,
      scannerIntervalMinutes: body.scannerIntervalMinutes,
      ladder: body.ladder,
      updatedById: currentUser(db).id,
      updatedAt: new Date().toISOString(),
    };
    return ok(settingsDto(db));
  }),

  // ── OB-12 ─────────────────────────────────────────────────────────────────
  http.get(url('/onboarding/notification-templates'), ({ request }) => {
    const db = getDb();
    const requestUrl = new URL(request.url);
    let rows = [...db.obNotificationTemplates];
    const channel = requestUrl.searchParams.get('channel');
    if (channel) rows = rows.filter((t) => t.channel === channel);
    const category = requestUrl.searchParams.get('category');
    if (category) rows = rows.filter((t) => t.category === category);
    return ok(rows.map(templateDto));
  }),

  http.get(url('/onboarding/notification-templates/vocabulary'), () => {
    const db = getDb();
    const seen = new Map<string, ObNotificationTemplateRow>();
    for (const t of db.obNotificationTemplates) if (!seen.has(t.eventCode)) seen.set(t.eventCode, t);
    return ok({
      events: [...seen.values()].map((t) => ({
        code: t.eventCode, category: t.category, mandatoryMail: isMandatory({ ...t, channel: 'EMAIL' }),
      })),
      channels: ['EMAIL', 'WHATSAPP', 'IN_APP'],
      recipients: ['CLIENT_SPOC', 'CLIENT_CONTACT', 'STEP_OWNER', 'BACKUP_OWNER', 'ONBOARDING_MANAGER', 'OB_ADMIN', 'VERIFIER'],
      mergeTags: MERGE_TAGS,
    });
  }),

  http.patch(url('/onboarding/notification-templates/:templateId'), async ({ params, request }) => {
    const db = getDb();
    const t = db.obNotificationTemplates.find((x) => x.id === Number(params.templateId));
    if (!t) return notFound('Template');
    const body = (await request.json()) as Partial<ObNotificationTemplateRow>;

    if (body.isActive === false && isMandatory(t)) {
      return problem(409, 'ob-template-mandatory',
        'Escalation and sign-off mail cannot be switched off.');
    }
    if (body.bodyTemplate !== undefined) {
      // An unknown merge tag renders as literal braces in a client's inbox, and
      // this is the last place to catch it.
      const unknown = (body.bodyTemplate.match(/\{\{[a-z_]+\}\}/g) ?? []).filter((tag) => !MERGE_TAGS.includes(tag));
      if (unknown.length > 0) {
        return validationFailed({ bodyTemplate: [`unknown merge tag(s): ${unknown.join(', ')}`] });
      }
      if (!body.bodyTemplate.trim()) return validationFailed({ bodyTemplate: ['must not be blank'] });
      t.bodyTemplate = body.bodyTemplate;
    }
    if (body.subjectTemplate !== undefined) t.subjectTemplate = body.subjectTemplate;
    if (body.recipients !== undefined) {
      if (body.recipients.length === 0) return validationFailed({ recipients: ['must not be empty'] });
      t.recipients = body.recipients;
    }
    if (body.isActive !== undefined) t.isActive = body.isActive;
    return ok(templateDto(t));
  }),

  // OB-13's notification centre is B-112's, in obNotifications.ts. The OB-12
  // templates above are a different resource: what the centre says, not what it
  // has said.
];
