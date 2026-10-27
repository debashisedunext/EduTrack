import { http } from 'msw';
import type {
  Db, ObClient, ObClientEscalationRow, ObClientPrereqTaskRow, ObContact, ObJourney,
  ObPrereqCommentRow, ObSignoffRow, ObStep,
} from '../db';
import { getDb, nextId } from '../db';
import { noContent, notFound, ok, paginate, problem, url, userRef, validationFailed } from './util';
import { portalClientId } from './portal';
import { acceptSignoffRow, contactOf } from './onboardingAdmin';
import { findTask, prereqsDto, taskDetailDto, taskDto } from './onboardingPrereqs';
import { journeyRag, percentComplete, stepRag } from './onboardingSteps';

/**
 * C-121 · the client portal's onboarding side — CP-01 to CP-04.
 *
 * Kept apart from `portal.ts`, whose header promises "four reads and nothing
 * else" for the ticketing side; this side has writes, and a file that says it
 * has none should keep being right.
 *
 * **Every row served here belongs to the signed-in account's client.** The
 * real principal is a `principal_type: CLIENT` token resolved to an
 * `ob_client_accounts` row; the mock has no token, so it stands in the first
 * active account in the fixture — GreenValley, through Deepa Kulkarni. A
 * step, sign-off or prerequisite task under any other client answers the same
 * `404` a non-existent id does, in every handler: `notFound` is reached only
 * through a lookup already narrowed to this client, so there is no branch that
 * could tell the two apart even by accident. That is plan §9's
 * no-existence-leak rule, and it is the property the tests beside this file
 * hold.
 *
 * **Separate serializers, never staff DTOs with fields hidden.** `PortalSignoff`
 * carries a product *name* and a step *title* where the staff row carries ids
 * and a `requestedBy`; `PortalStepDot` carries a status and a colour and none of
 * the owner, the block reason or the TAT internals. Where the contract does
 * reuse a staff shape (`ObClientPrereqs`, `ObClientPrereqTask`) the staff
 * builder is called, because those shapes are the same on the wire by design.
 */

// ── principal ───────────────────────────────────────────────────────────────

export function portalAccount(db: Db = getDb()) {
  return db.obClientAccounts.find((a) => a.isActive) ?? null;
}

export function portalObClientId(db: Db = getDb()): number | null {
  return portalAccount(db)?.obClientId ?? null;
}

function portalClient(db: Db): ObClient | undefined {
  const id = portalObClientId(db);
  return id == null ? undefined : db.obClients.find((c) => c.id === id);
}

/** The SPOC a client-side write is attributed to. Primary first; any active contact otherwise. */
function primaryContact(client: ObClient): ObContact | null {
  return client.contacts.find((c) => c.isActive && c.isPrimary)
    ?? client.contacts.find((c) => c.isActive)
    ?? null;
}

const contactRef = (c: ObContact | null) => (c ? { id: c.id, name: c.name, email: c.email } : null);

const unauthenticated = () => problem(401, 'unauthenticated', 'No portal session.');
const noContact = () =>
  problem(422, 'ob-portal-no-contact', 'The client has no active primary contact to attribute this to.');

/** Content-derived, mirroring `portal.ts#etagOf`. */
function etagOf(detail: unknown): string {
  const str = JSON.stringify(detail);
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    hash = (hash * 31 + str.charCodeAt(i)) | 0;
  }
  return (hash >>> 0).toString(16);
}

// ── CP-03 · home ────────────────────────────────────────────────────────────

const openEscalationOf = (db: Db, stepId: number) => {
  const e = db.obClientEscalations.find((x) => x.stepId === stepId && x.resolvedAt == null);
  return e ? { id: e.id, comment: e.comment, raisedAt: e.raisedAt } : null;
};

/** `PortalStepDot` — status and colour, and nothing plan §9 lists as never-visible. */
const stepDot = (step: ObStep, db: Db) => ({
  id: step.id,
  sequence: step.sequence,
  name: step.name,
  status: step.status,
  rag: stepRag(step),
  dependsOnStepId: step.dependsOnStepId,
  openEscalation: openEscalationOf(db, step.id),
});

function stripDto(journey: ObJourney, db: Db) {
  const product = db.obProducts.find((p) => p.id === journey.productId);
  return {
    id: journey.id,
    product: { id: journey.productId, code: product?.code ?? '', name: product?.name ?? '' },
    gateStatus: journey.gateStatus,
    rag: journeyRag(journey),
    percentComplete: percentComplete(journey),
    heldByJourneyId: journey.heldByJourneyId,
    steps: journey.steps.map((s) => stepDot(s, db)),
  };
}

/** A client boarded before the prerequisites layer existed has an empty, open checklist. */
const emptyPrereqs = (obClientId: number) => ({
  obClientId, templateVersion: 0, status: 'CLEARED' as const, clearedAt: null,
  gateStatus: 'OPEN' as const, mandatoryTotal: 0, mandatoryVerified: 0, optionalOutstanding: 0, tasks: [],
});

// ── sign-offs ───────────────────────────────────────────────────────────────

function signoffContext(s: ObSignoffRow, db: Db) {
  const client = db.obClients.find((c) => c.id === s.obClientId);
  const journey = client?.journeys.find((j) => j.id === s.journeyId);
  const step = s.stepId ? journey?.steps.find((x) => x.id === s.stepId) : undefined;
  const product = db.obProducts.find((p) => p.id === journey?.productId);
  return { client, journey, step, product };
}

const csatOffered = (s: ObSignoffRow) => s.kind === 'GO_LIVE' && s.status === 'SIGNED' && s.csatScore == null;

function portalSignoffDto(s: ObSignoffRow, db: Db) {
  const { product, step } = signoffContext(s, db);
  return {
    id: s.id, kind: s.kind, status: s.status,
    productName: product?.name ?? null,
    stepTitle: step?.name ?? null,
    requestedAt: s.requestedAt,
    tokenExpiresAt: s.tokenExpiresAt,
    sentToEmail: contactOf(db, s.obClientId, s.sentToContactId)?.email ?? null,
    signedAt: s.signedAt, signedName: s.signedName ?? null, acceptanceNote: s.acceptanceNote ?? null,
    objectedAt: s.objectedAt, objectionNote: s.objectionNote,
    hasCertificate: s.pdfStorageKey != null,
  };
}

function portalSignoffReviewDto(s: ObSignoffRow, db: Db) {
  const { client, product, step } = signoffContext(s, db);
  return {
    id: s.id, kind: s.kind, status: s.status,
    clientName: client?.name ?? null,
    productName: product?.name ?? null,
    stepTitle: step?.name ?? null,
    requestedAt: s.requestedAt,
    canDecide: s.status === 'PENDING' && Date.parse(s.tokenExpiresAt) > Date.now(),
    csatOffered: csatOffered(s),
    checklist: (step?.items ?? []).map((i) => ({
      id: i.id, sequence: i.sequence, label: i.label, isMandatory: i.isMandatory, isDone: i.isDone,
    })),
  };
}

function portalSignoffDecisionDto(
  s: ObSignoffRow,
  outcome: { stepCompleted: boolean; gateFailures: string[]; clientWentLive: boolean },
) {
  return {
    id: s.id, status: s.status,
    signedAt: s.signedAt, signedName: s.signedName ?? null, acceptanceNote: s.acceptanceNote ?? null,
    objectedAt: s.objectedAt, objectionNote: s.objectionNote,
    ...outcome,
    hasCertificate: s.pdfStorageKey != null,
    csatOffered: csatOffered(s),
  };
}

const mySignoffs = (db: Db) => db.obSignoffs.filter((s) => s.obClientId === portalObClientId(db));
const mySignoff = (db: Db, id: number) => mySignoffs(db).find((s) => s.id === id);

const signoffNotOpen = (s: ObSignoffRow) =>
  problem(422, 'ob-signoff-not-open', `This sign-off is ${s.status.toLowerCase()} and can no longer be decided.`, {
    signoffStatus: s.status,
  });

// ── escalations ─────────────────────────────────────────────────────────────

const clientEscalationDto = (e: ObClientEscalationRow, isNew: boolean) => ({
  id: e.id, comment: e.comment, raisedAt: e.raisedAt, isNew,
});

// ── prerequisites ───────────────────────────────────────────────────────────

/** A task under this client, or nothing — another client's task and no task look the same. */
function myTask(db: Db, id: number): ObClientPrereqTaskRow | undefined {
  const task = findTask(db, id);
  return task && task.obClientId === portalObClientId(db) ? task : undefined;
}

function prereqCommentDto(c: ObPrereqCommentRow, task: ObClientPrereqTaskRow, db: Db) {
  return {
    id: c.id, prereqTaskId: c.prereqTaskId, authorType: c.authorType,
    staffAuthor: c.authorType === 'STAFF' ? userRef(c.staffAuthorId, db) : null,
    clientAuthor: c.authorType === 'CLIENT' && c.clientContactId != null
      ? contactRef(contactOf(db, task.obClientId, c.clientContactId))
      : null,
    body: c.body, isSystem: c.isSystem, createdAt: c.createdAt,
  };
}

const nextAttachmentId = (db: Db) =>
  Math.max(
    0,
    ...db.obClientPrereqTasks.flatMap((t) => [
      ...t.submissions.map((s) => s.attachmentId),
      ...t.referenceDocs.map((d) => d.attachmentId),
    ]),
  ) + 1;

// ── CP-01 · credentials ─────────────────────────────────────────────────────

/**
 * The dev-stack password, so the mock and the real dev profile agree — see
 * `createObClientAccount` in onboarding.ts, which hands it back on create.
 */
const DEV_PASSWORD = 'Demo-Passw0rd!';

/**
 * The credential link's token is `credential-<accountId>` here. It is live
 * while the credential was sent and nobody has signed in since — the mock's
 * reading of `client_credential_tokens.used_at`, without a second table.
 */
function accountForCredential(db: Db, token: unknown) {
  const m = /^credential-(\d+)$/.exec(String(token));
  const account = m ? db.obClientAccounts.find((a) => a.id === Number(m[1])) : undefined;
  if (!account || !account.isActive || !account.credentialSentAt) return null;
  if (account.lastLoginAt && account.lastLoginAt >= account.credentialSentAt) return null;
  return account;
}

const credentialSpent = () =>
  problem(410, 'portal-credential-link-invalid', 'That link is not valid. Ask your implementor for a new one.');

// ── handlers ────────────────────────────────────────────────────────────────

export const portalOnboardingHandlers = [
  // CP-01
  http.post(url('/portal/auth/login'), async ({ request }) => {
    const db = getDb();
    const body = (await request.json().catch(() => ({}))) as { username?: string; password?: string };
    const errors: Record<string, string[]> = {};
    if (!body.username?.trim()) errors.username = ['must not be blank'];
    if (!body.password) errors.password = ['must not be blank'];
    if (Object.keys(errors).length) return validationFailed(errors);

    const account = db.obClientAccounts.find(
      (a) => a.username.toLowerCase() === body.username!.trim().toLowerCase(),
    );
    // One body for an unknown username, a wrong password and an inactive
    // account — a caller who can tell them apart can enumerate usernames.
    const denied = () => problem(401, 'portal-invalid-credentials', 'Invalid credentials.');
    if (!account || !account.isActive) return denied();
    if (account.lockedUntil && Date.parse(account.lockedUntil) > Date.now()) {
      return problem(423, 'portal-account-locked', 'Account locked after repeated failures.', {
        lockedUntil: account.lockedUntil,
      });
    }
    if (body.password !== DEV_PASSWORD) return denied();

    account.lastLoginAt = new Date().toISOString();
    return ok({
      accessToken: `mock-portal-token-${account.id}`,
      expiresIn: 900,
      client: {
        username: account.username,
        displayName: account.displayName,
        hasTicketing: portalClientId(db) != null,
        hasOnboarding: true,
      },
    });
  }),

  http.get(url('/portal/auth/credential/:token'), ({ params }) => {
    const account = accountForCredential(getDb(), params.token);
    if (!account) return credentialSpent();
    return ok({
      username: account.username,
      displayName: account.displayName,
      expiresAt: new Date(Date.parse(account.credentialSentAt!) + 7 * 24 * 3_600_000).toISOString(),
    });
  }),

  http.post(url('/portal/auth/credential/:token'), async ({ params, request }) => {
    const db = getDb();
    const body = (await request.json().catch(() => ({}))) as { password?: string };
    if (!body.password || body.password.length < 8) {
      return validationFailed({ password: ['must be at least 8 characters'] });
    }
    const account = accountForCredential(db, params.token);
    if (!account) return credentialSpent();
    // Spent: a second GET on the same link answers 410 from here on.
    account.lastLoginAt = new Date().toISOString();
    account.mustChangePassword = false;
    return noContent();
  }),

  // CP-03
  http.get(url('/portal/onboarding/home'), () => {
    const db = getDb();
    const client = portalClient(db);
    if (!client) return unauthenticated();
    return ok({
      obClientId: client.id,
      clientName: client.name,
      prereqs: prereqsDto(client.id, db) ?? emptyPrereqs(client.id),
      journeys: client.journeys
        .filter((j) => !j.archivedAt)
        .map((j) => stripDto(j, db)),
    });
  }),

  http.post(url('/portal/onboarding/steps/:stepId/escalate'), async ({ params, request }) => {
    const db = getDb();
    const client = portalClient(db);
    if (!client) return unauthenticated();
    const body = (await request.json().catch(() => ({}))) as { comment?: string };
    if (!body.comment?.trim()) return validationFailed({ comment: ['must not be blank'] });

    const stepId = Number(params.stepId);
    let found: { journey: ObJourney; step: ObStep } | undefined;
    for (const journey of client.journeys) {
      const step = journey.steps.find((s) => s.id === stepId);
      if (step) {
        found = { journey, step };
        break;
      }
    }
    if (!found) return notFound('Step');

    // One open escalation per service (`uq_ob_client_escalations_open`): a
    // second raise returns the first, unchanged, rather than a duplicate.
    const open = db.obClientEscalations.find((e) => e.stepId === stepId && e.resolvedAt == null);
    if (open) return ok(clientEscalationDto(open, false));

    if (found.step.status !== 'IN_PROGRESS') {
      return problem(422, 'ob-step-not-running', 'This service is not currently running and cannot be escalated.', {
        stepStatus: found.step.status,
      });
    }
    const contact = primaryContact(client);
    if (!contact) return noContact();

    const row: ObClientEscalationRow = {
      id: Math.max(0, ...db.obClientEscalations.map((e) => e.id)) + 1,
      obClientId: client.id, journeyId: found.journey.id, stepId,
      raisedByContactId: contact.id, comment: body.comment.trim(),
      raisedAt: new Date().toISOString(),
      resolvedById: null, resolvedAt: null, resolutionNote: null,
    };
    db.obClientEscalations.push(row);
    return ok(clientEscalationDto(row, true), undefined, { status: 201 });
  }),

  // CP-02 · sign-offs
  http.get(url('/portal/onboarding/signoffs'), () => {
    const db = getDb();
    if (portalObClientId(db) == null) return unauthenticated();
    const rows = [...mySignoffs(db)].sort((a, b) => b.requestedAt.localeCompare(a.requestedAt));
    return ok(rows.map((s) => portalSignoffDto(s, db)));
  }),

  http.get(url('/portal/onboarding/signoffs/:signoffId'), ({ params }) => {
    const db = getDb();
    if (portalObClientId(db) == null) return unauthenticated();
    const s = mySignoff(db, Number(params.signoffId));
    if (!s) return notFound('Sign-off');
    const dto = portalSignoffReviewDto(s, db);
    return ok(dto, undefined, { headers: { ETag: etagOf(dto) } });
  }),

  http.post(url('/portal/onboarding/signoffs/:signoffId/accept'), async ({ params, request }) => {
    const db = getDb();
    if (portalObClientId(db) == null) return unauthenticated();
    const body = (await request.json().catch(() => ({}))) as { acceptedName?: string; note?: string | null };
    if (!body.acceptedName?.trim()) return validationFailed({ acceptedName: ['must not be blank'] });
    const s = mySignoff(db, Number(params.signoffId));
    if (!s) return notFound('Sign-off');
    if (s.status !== 'PENDING') return signoffNotOpen(s);

    // Recorded first and unconditionally, then the gate — the client did
    // accept; they are not the ones who left a document unattached. The gate
    // lives in `acceptSignoffRow`, shared with OB-09's public route.
    s.signedName = body.acceptedName.trim();
    s.acceptanceNote = body.note?.trim() || null;
    const result = acceptSignoffRow(db, s, request.headers.get('user-agent') ?? 'mock');
    return ok(portalSignoffDecisionDto(s, result));
  }),

  http.post(url('/portal/onboarding/signoffs/:signoffId/object'), async ({ params, request }) => {
    const db = getDb();
    if (portalObClientId(db) == null) return unauthenticated();
    const body = (await request.json().catch(() => ({}))) as { note?: string };
    if (!body.note?.trim()) return validationFailed({ note: ['must not be blank'] });
    const s = mySignoff(db, Number(params.signoffId));
    if (!s) return notFound('Sign-off');
    if (s.status !== 'PENDING') return signoffNotOpen(s);

    s.status = 'OBJECTED';
    s.objectedAt = new Date().toISOString();
    s.objectionNote = body.note.trim();
    // The step reverts — plan §8 — exactly as the public route does it.
    const { step } = signoffContext(s, db);
    if (step) step.status = 'IN_PROGRESS';
    return ok(portalSignoffDecisionDto(s, { stepCompleted: false, gateFailures: [], clientWentLive: false }));
  }),

  http.post(url('/portal/onboarding/signoffs/:signoffId/csat'), async ({ params, request }) => {
    const db = getDb();
    if (portalObClientId(db) == null) return unauthenticated();
    const body = (await request.json().catch(() => ({}))) as { score?: number; comment?: string | null };
    if (!body.score || body.score < 1 || body.score > 5) {
      return validationFailed({ score: ['must be between 1 and 5'] });
    }
    const s = mySignoff(db, Number(params.signoffId));
    if (!s) return notFound('Sign-off');
    if (!csatOffered(s)) {
      return problem(422, 'ob-csat-not-offered', 'This sign-off is not offering a survey.', {
        signoffStatus: s.status, kind: s.kind, surveyed: s.csatScore != null,
      });
    }
    s.csatScore = body.score;
    s.csatComment = body.comment?.trim() || null;
    return noContent();
  }),

  // CP-04 · prerequisites
  http.get(url('/portal/onboarding/prereq-tasks/:prereqTaskId'), ({ params }) => {
    const db = getDb();
    if (portalObClientId(db) == null) return unauthenticated();
    const task = myTask(db, Number(params.prereqTaskId));
    if (!task) return notFound('Prerequisite task');
    const dto = taskDetailDto(task, db);
    return ok(dto, undefined, { headers: { ETag: etagOf(dto) } });
  }),

  http.post(url('/portal/onboarding/prereq-tasks/:prereqTaskId/submit'), async ({ params, request }) => {
    const db = getDb();
    const client = portalClient(db);
    if (!client) return unauthenticated();
    const task = myTask(db, Number(params.prereqTaskId));
    if (!task) return notFound('Prerequisite task');
    if (task.status !== 'PENDING') {
      return problem(422, 'ob-prereq-not-submittable', `The task is ${task.status}.`, { status_: task.status });
    }
    const contact = primaryContact(client);
    if (!contact) return noContact();
    const body = (await request.json().catch(() => ({}))) as { note?: string | null };

    const from = task.status;
    task.status = 'SUBMITTED';
    task.submittedAt = new Date().toISOString();
    task.submittedVia = 'PORTAL';
    // The history row names the SPOC, not a member of staff — the one field
    // that makes "who sent this" answerable in a dispute.
    db.obPrereqHistory.push({
      id: nextId(db, 'obPrereqHistory'), prereqTaskId: task.id,
      at: task.submittedAt, actorType: 'CLIENT',
      staffActorId: null, clientActorId: contact.id,
      fromStatus: from, toStatus: 'SUBMITTED', reason: body.note?.trim() || null,
      isCorrection: false, correctsEntryId: null,
    });
    return ok(taskDto(task, db));
  }),

  http.get(url('/portal/onboarding/prereq-tasks/:prereqTaskId/comments'), ({ params, request }) => {
    const db = getDb();
    if (portalObClientId(db) == null) return unauthenticated();
    const task = myTask(db, Number(params.prereqTaskId));
    if (!task) return notFound('Prerequisite task');
    const rows = db.obPrereqComments
      .filter((c) => c.prereqTaskId === task.id)
      .sort((a, b) => a.createdAt.localeCompare(b.createdAt));
    const { page, meta } = paginate(rows, new URL(request.url));
    return ok(page.map((c) => prereqCommentDto(c, task, db)), meta);
  }),

  http.post(url('/portal/onboarding/prereq-tasks/:prereqTaskId/comments'), async ({ params, request }) => {
    const db = getDb();
    const client = portalClient(db);
    if (!client) return unauthenticated();
    const task = myTask(db, Number(params.prereqTaskId));
    if (!task) return notFound('Prerequisite task');
    const { body } = (await request.json().catch(() => ({}))) as { body?: string };
    if (!body?.trim()) return validationFailed({ body: ['must not be blank'] });
    const contact = primaryContact(client);
    if (!contact) return noContact();

    // The author comes from the principal, never from the body.
    const row: ObPrereqCommentRow = {
      id: nextId(db, 'obPrereqComment'), prereqTaskId: task.id,
      authorType: 'CLIENT', staffAuthorId: null, clientContactId: contact.id,
      body: body.trim(), isSystem: false, createdAt: new Date().toISOString(),
    };
    db.obPrereqComments.push(row);
    return ok(prereqCommentDto(row, task, db), undefined, { status: 201 });
  }),

  http.post(url('/portal/onboarding/prereq-tasks/:prereqTaskId/attachments'), async ({ params, request }) => {
    const db = getDb();
    const client = portalClient(db);
    if (!client) return unauthenticated();
    const task = myTask(db, Number(params.prereqTaskId));
    if (!task) return notFound('Prerequisite task');
    const contact = primaryContact(client);
    if (!contact) return noContact();
    const form = await request.formData().catch(() => null);
    const file = form?.get('file');
    // Not `instanceof File`: the test realm's File and MSW's are different
    // constructors under jsdom, the same cross-realm trap test/setup.ts shims
    // for AbortSignal. A string is the only other thing FormData can hold.
    if (!file || typeof file === 'string' || file.size === 0) {
      return validationFailed({ file: ['is required'] });
    }

    // Filed with the scan pending and no download URL yet — the same shape
    // `uploadObPrereqAttachment` answers on the staff side.
    const submission = {
      attachmentId: nextAttachmentId(db),
      fileName: file.name,
      sizeBytes: file.size,
      uploadedByType: 'CLIENT' as const,
      uploadedAt: new Date().toISOString(),
    };
    task.submissions.push(submission);
    return ok({ ...submission, downloadUrl: null }, undefined, { status: 201 });
  }),
];
