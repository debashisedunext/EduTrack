import { http } from 'msw';
import type {
  Db, ObClientPrereqTaskRow, ObPrereqStatus, ObPrereqTemplateTaskRow,
} from '../db';
import { getDb, nextId } from '../db';
import {
  currentUser, noContent, notFound, ok, paginate, problem, unprocessable, url,
  userRef, validationFailed,
} from './util';

/**
 * A-118 · mocks for the prerequisites layer — OB-14's master and the per-client
 * instances that gate every journey.
 *
 * ⚠️ **Stream A adding handlers in Stream D's `mocks/` directory (D-004)** —
 * the same flag `onboarding.ts`, `onboardingJourneys.ts` and
 * `onboardingSteps.ts` all carry. `coverage.test.ts` refuses a contract
 * operation with no handler, so the alternative to this file is a red
 * `develop` the moment the contract lands.
 *
 * **Three rules are mirrored from the contract deliberately**, because a screen
 * built against a mock laxer than the server is a screen that breaks on its
 * first real call — the lesson PHASE-2-BUILD-PLAN §8 paid three shipped bugs
 * and a blank detail page for:
 *
 *   1. **The gate has no override.** Nothing here opens it directly. It opens
 *      as a consequence of the verification or skip that clears the last
 *      outstanding task, in `applyGate` below, and nowhere else.
 *   2. **A mandatory task cannot be skipped.** `422`, not a role check.
 *   3. **History is append-only.** These handlers only ever push, and there is
 *      no route that edits or removes an entry.
 */

// ── shared shapes ───────────────────────────────────────────────────────────

const docDto = (d: ObPrereqTemplateTaskRow['docs'][number], templateTaskId: number) => ({
  id: d.id, templateTaskId, label: d.label,
  attachmentId: d.attachmentId, fileName: d.fileName, sizeBytes: d.sizeBytes,
});

const templateTaskDto = (t: ObPrereqTemplateTaskRow) => ({
  id: t.id, sequence: t.sequence, title: t.title, description: t.description,
  tatDays: t.tatDays, isMandatory: t.isMandatory, isActive: t.isActive,
  docs: t.docs.map((d) => docDto(d, t.id)),
});

function templateDto(version: number, db: Db) {
  const v = db.obPrereqVersions.find((x) => x.version === version);
  if (!v) return null;
  const tasks = db.obPrereqTemplateTasks
    .filter((t) => t.templateVersion === version)
    .sort((a, b) => a.sequence - b.sequence);
  return {
    version: v.version, isDraft: v.isDraft, isActive: v.isActive,
    publishedAt: v.publishedAt, publishedBy: userRef(v.publishedById, db),
    mandatoryCount: tasks.filter((t) => t.isMandatory).length,
    tasks: tasks.map(templateTaskDto),
  };
}

const activeVersion = (db: Db) => db.obPrereqVersions.find((v) => v.isActive);
const draftVersion = (db: Db) => db.obPrereqVersions.find((v) => v.isDraft);

/** `isOverdue` is derived, never stored — so it cannot disagree with `dueAt`. */
const isOverdue = (t: ObClientPrereqTaskRow) =>
  (t.status === 'PENDING' || t.status === 'SUBMITTED') && Date.parse(t.dueAt) < Date.now();

function taskDto(t: ObClientPrereqTaskRow, db: Db) {
  return {
    id: t.id, obClientId: t.obClientId, templateTaskId: t.templateTaskId,
    sequence: t.sequence, title: t.title, description: t.description,
    isMandatory: t.isMandatory, isAdHoc: t.isAdHoc,
    status: t.status, dueAt: t.dueAt, isOverdue: isOverdue(t),
    submittedAt: t.submittedAt, submittedVia: t.submittedVia,
    verifiedAt: t.verifiedAt, verifiedBy: userRef(t.verifiedById, db),
    skippedAt: t.skippedAt, skippedBy: userRef(t.skippedById, db),
    skipReason: t.skipReason,
    commentCount: db.obPrereqComments.filter((c) => c.prereqTaskId === t.id).length,
    attachmentCount: t.submissions.length,
  };
}

const taskDetailDto = (t: ObClientPrereqTaskRow, db: Db) => ({
  ...taskDto(t, db),
  referenceDocs: t.referenceDocs.map((d) => docDto(d, t.templateTaskId ?? t.id)),
  submissions: t.submissions,
});

const clientTasks = (obClientId: number, db: Db) =>
  db.obClientPrereqTasks
    .filter((t) => t.obClientId === obClientId)
    .sort((a, b) => a.sequence - b.sequence);

/**
 * The gate condition, in one place — plan §5.3.
 *
 * Every mandatory task `VERIFIED`, every non-mandatory one `VERIFIED` **or**
 * `SKIPPED`. Stated once and called from both transitions that can satisfy it,
 * rather than written out at each: two copies of this is how one of them ends
 * up subtly different and a gate opens a task early.
 */
const gateSatisfied = (tasks: ObClientPrereqTaskRow[]) =>
  tasks.every((t) => (t.isMandatory ? t.status === 'VERIFIED' : t.status === 'VERIFIED' || t.status === 'SKIPPED'));

function prereqsDto(obClientId: number, db: Db) {
  const header = db.obClientPrereqs.find((h) => h.obClientId === obClientId);
  if (!header) return null;
  const tasks = clientTasks(obClientId, db);
  const mandatory = tasks.filter((t) => t.isMandatory);
  return {
    obClientId,
    templateVersion: header.templateVersion,
    status: header.status,
    clearedAt: header.clearedAt,
    gateStatus: header.status === 'CLEARED' ? 'OPEN' : 'LOCKED',
    mandatoryTotal: mandatory.length,
    mandatoryVerified: mandatory.filter((t) => t.status === 'VERIFIED').length,
    optionalOutstanding: tasks.filter(
      (t) => !t.isMandatory && t.status !== 'VERIFIED' && t.status !== 'SKIPPED',
    ).length,
    tasks: tasks.map((t) => taskDto(t, db)),
  };
}

function pushHistory(
  db: Db,
  task: ObClientPrereqTaskRow,
  from: ObPrereqStatus | null,
  to: ObPrereqStatus,
  reason: string | null,
) {
  db.obPrereqHistory.push({
    id: nextId(db, 'obPrereqHistory'), prereqTaskId: task.id,
    at: new Date().toISOString(), actorType: 'STAFF',
    staffActorId: currentUser(db).id, clientActorId: null,
    fromStatus: from, toStatus: to, reason,
    isCorrection: false, correctsEntryId: null,
  });
}

/**
 * Apply the gate after a transition, and report what it did.
 *
 * `gateOpened` is true **at most once in a client's life** — on the transition
 * that clears the last outstanding task. Reporting it from the transition is
 * what stops OB-05 inferring it from `gateStatus`, which reads `OPEN` on every
 * subsequent call too and would have the ribbon refreshing forever.
 */
function applyGate(db: Db, task: ObClientPrereqTaskRow) {
  const header = db.obClientPrereqs.find((h) => h.obClientId === task.obClientId);
  const tasks = clientTasks(task.obClientId, db);
  const mandatory = tasks.filter((t) => t.isMandatory);
  const openedJourneyIds: number[] = [];
  let gateOpened = false;

  if (header && header.status === 'IN_PROGRESS' && gateSatisfied(tasks)) {
    header.status = 'CLEARED';
    header.clearedAt = new Date().toISOString();
    gateOpened = true;
    const client = db.obClients.find((c) => c.id === task.obClientId);
    for (const j of client?.journeys ?? []) {
      // A journey held behind a sibling stays held — the gate and the service
      // dependency are two different holds, and clearing one does not clear
      // the other (plan §5.5).
      if (j.gateStatus === 'LOCKED' && j.heldByJourneyId == null) {
        j.gateStatus = 'OPEN';
        j.startedAt = new Date().toISOString();
        openedJourneyIds.push(j.id);
      }
    }
  }

  return ok({
    task: taskDto(task, db),
    gateStatus: header?.status === 'CLEARED' ? 'OPEN' : 'LOCKED',
    gateOpened,
    openedJourneyIds,
    mandatoryTotal: mandatory.length,
    mandatoryVerified: mandatory.filter((t) => t.status === 'VERIFIED').length,
  });
}

const findTask = (db: Db, id: number) => db.obClientPrereqTasks.find((t) => t.id === id);

/** A draft is required for every master write — editing a published version is refused. */
const noDraft = () =>
  problem(409, 'ob-prereq-no-draft', 'There is no draft to edit. Begin a revision first.');

const publishedTask = () =>
  problem(409, 'ob-prereq-published', 'This task belongs to a published version. Begin a revision first.');

/** Working days added to today, weekends skipped — CLAUDE.md's calendar rule. */
function dueFromTat(tatDays: number): string {
  const d = new Date();
  let left = tatDays;
  while (left > 0) {
    d.setUTCDate(d.getUTCDate() + 1);
    if (d.getUTCDay() !== 0 && d.getUTCDay() !== 6) left -= 1;
  }
  return d.toISOString();
}

export const obPrereqHandlers = [
  // ── the master (OB-14) ────────────────────────────────────────────────────
  http.get(url('/onboarding/prereq-template'), ({ request }) => {
    const db = getDb();
    const asked = new URL(request.url).searchParams.get('version');
    const version = asked ? Number(asked) : activeVersion(db)?.version;
    const dto = version ? templateDto(version, db) : null;
    return dto ? ok(dto) : notFound('Prerequisite template');
  }),

  http.post(url('/onboarding/prereq-template/revisions'), () => {
    const db = getDb();
    if (draftVersion(db)) {
      return problem(409, 'ob-prereq-draft-exists', 'A draft already exists — publish or discard it first.');
    }
    const active = activeVersion(db);
    if (!active) return notFound('Prerequisite template');

    const version = Math.max(...db.obPrereqVersions.map((v) => v.version)) + 1;
    db.obPrereqVersions.push({ version, isDraft: true, isActive: false, publishedAt: null, publishedById: null });
    // Clone the tasks and their docs. The source version is never written —
    // every client already boarded keeps the checklist they agreed to.
    for (const t of db.obPrereqTemplateTasks.filter((x) => x.templateVersion === active.version)) {
      db.obPrereqTemplateTasks.push({
        ...structuredClone(t),
        id: nextId(db, 'obPrereqTemplateTask') + 1000,
        templateVersion: version,
      });
    }
    return ok(templateDto(version, db), undefined, { status: 201 });
  }),

  http.post(url('/onboarding/prereq-template/publish'), () => {
    const db = getDb();
    const draft = draftVersion(db);
    if (!draft) return problem(409, 'ob-prereq-no-draft', 'There is no draft to publish.');

    const tasks = db.obPrereqTemplateTasks.filter((t) => t.templateVersion === draft.version);
    if (!tasks.some((t) => t.isMandatory)) {
      return unprocessable('A published checklist with no mandatory task would clear its own gate at boarding.');
    }
    const previous = activeVersion(db);
    if (previous) previous.isActive = false;
    draft.isDraft = false;
    draft.isActive = true;
    draft.publishedAt = new Date().toISOString();
    draft.publishedById = currentUser(db).id;
    return ok(templateDto(draft.version, db));
  }),

  http.post(url('/onboarding/prereq-template/tasks'), async ({ request }) => {
    const db = getDb();
    const draft = draftVersion(db);
    if (!draft) return noDraft();
    const body = (await request.json()) as Partial<ObPrereqTemplateTaskRow>;
    if (!body.title?.trim()) return validationFailed({ title: ['must not be blank'] });

    const siblings = db.obPrereqTemplateTasks.filter((t) => t.templateVersion === draft.version);
    const row: ObPrereqTemplateTaskRow = {
      id: nextId(db, 'obPrereqTemplateTask') + 1000,
      templateVersion: draft.version,
      sequence: siblings.length + 1,
      title: body.title,
      description: body.description ?? null,
      tatDays: body.tatDays ?? 1,
      isMandatory: body.isMandatory ?? false,
      isActive: body.isActive ?? true,
      docs: [],
    };
    db.obPrereqTemplateTasks.push(row);
    return ok(templateTaskDto(row), undefined, { status: 201 });
  }),

  http.put(url('/onboarding/prereq-template/tasks/order'), async ({ request }) => {
    const db = getDb();
    const draft = draftVersion(db);
    if (!draft) return noDraft();
    const { taskIds } = (await request.json()) as { taskIds?: number[] };
    const tasks = db.obPrereqTemplateTasks.filter((t) => t.templateVersion === draft.version);
    // A partial list is refused: positions only mean something against the
    // complete set, and reordering around unseen tasks is a silent wrong answer.
    if (!taskIds || taskIds.length !== tasks.length) {
      return validationFailed({ taskIds: ['must name every task in the draft'] });
    }
    taskIds.forEach((id, i) => {
      const t = tasks.find((x) => x.id === id);
      if (t) t.sequence = i + 1;
    });
    return ok(templateDto(draft.version, db));
  }),

  http.patch(url('/onboarding/prereq-template-tasks/:templateTaskId'), async ({ params, request }) => {
    const db = getDb();
    const task = db.obPrereqTemplateTasks.find((t) => t.id === Number(params.templateTaskId));
    if (!task) return notFound('Template task');
    const version = db.obPrereqVersions.find((v) => v.version === task.templateVersion);
    if (!version?.isDraft) return publishedTask();

    const body = (await request.json()) as Partial<ObPrereqTemplateTaskRow>;
    if (body.title !== undefined) {
      if (!body.title.trim()) return validationFailed({ title: ['must not be blank'] });
      task.title = body.title;
    }
    if (body.description !== undefined) task.description = body.description;
    if (body.tatDays !== undefined) task.tatDays = body.tatDays;
    if (body.isMandatory !== undefined) task.isMandatory = body.isMandatory;
    if (body.isActive !== undefined) task.isActive = body.isActive;
    return ok(templateTaskDto(task));
  }),

  http.delete(url('/onboarding/prereq-template-tasks/:templateTaskId'), ({ params }) => {
    const db = getDb();
    const i = db.obPrereqTemplateTasks.findIndex((t) => t.id === Number(params.templateTaskId));
    if (i < 0) return notFound('Template task');
    const version = db.obPrereqVersions.find((v) => v.version === db.obPrereqTemplateTasks[i].templateVersion);
    if (!version?.isDraft) return publishedTask();
    db.obPrereqTemplateTasks.splice(i, 1);
    return noContent();
  }),

  http.post(url('/onboarding/prereq-template-tasks/:templateTaskId/docs'), async ({ params, request }) => {
    const db = getDb();
    const task = db.obPrereqTemplateTasks.find((t) => t.id === Number(params.templateTaskId));
    if (!task) return notFound('Template task');
    const version = db.obPrereqVersions.find((v) => v.version === task.templateVersion);
    if (!version?.isDraft) return publishedTask();

    const body = (await request.json()) as { label?: string; attachmentId?: number };
    if (!body.label?.trim() || !body.attachmentId) {
      return validationFailed({ label: ['must not be blank'], attachmentId: ['is required'] });
    }
    const doc = {
      id: nextId(db, 'obPrereqDoc') + 500,
      label: body.label,
      attachmentId: body.attachmentId,
      fileName: `${body.label.toLowerCase().replace(/\W+/g, '-')}.pdf`,
      sizeBytes: 96_000,
    };
    task.docs.push(doc);
    return ok(docDto(doc, task.id), undefined, { status: 201 });
  }),

  http.delete(url('/onboarding/prereq-template-task-docs/:docId'), ({ params }) => {
    const db = getDb();
    const id = Number(params.docId);
    const task = db.obPrereqTemplateTasks.find((t) => t.docs.some((d) => d.id === id));
    if (!task) return notFound('Reference document');
    const version = db.obPrereqVersions.find((v) => v.version === task.templateVersion);
    if (!version?.isDraft) return publishedTask();
    task.docs = task.docs.filter((d) => d.id !== id);
    return noContent();
  }),

  // ── the per-client instance (B-125) ───────────────────────────────────────
  http.get(url('/onboarding/clients/:obClientId/prereqs'), ({ params }) => {
    const dto = prereqsDto(Number(params.obClientId), getDb());
    return dto ? ok(dto) : notFound('Client prerequisites');
  }),

  http.post(url('/onboarding/clients/:obClientId/prereq-tasks'), async ({ params, request }) => {
    const db = getDb();
    const obClientId = Number(params.obClientId);
    const header = db.obClientPrereqs.find((h) => h.obClientId === obClientId);
    if (!header) return notFound('Client prerequisites');

    const body = (await request.json()) as { title?: string; description?: string | null; tatDays?: number; isMandatory?: boolean };
    if (!body.title?.trim()) return validationFailed({ title: ['must not be blank'] });

    const row: ObClientPrereqTaskRow = {
      id: nextId(db, 'obClientPrereqTask') + 200,
      obClientId, templateTaskId: null,
      sequence: clientTasks(obClientId, db).length + 1,
      title: body.title, description: body.description ?? null,
      isMandatory: body.isMandatory ?? false, isAdHoc: true,
      status: 'PENDING', dueAt: dueFromTat(body.tatDays ?? 1),
      submittedAt: null, submittedVia: null,
      verifiedAt: null, verifiedById: null,
      skippedAt: null, skippedById: null, skipReason: null,
      referenceDocs: [], submissions: [],
    };
    db.obClientPrereqTasks.push(row);
    // A mandatory addition re-locks a gate that had opened. The contract
    // preconditions this create on the client's ETag for exactly that reason;
    // the mock mirrors the consequence so a screen meets it here first.
    if (row.isMandatory && header.status === 'CLEARED') {
      header.status = 'IN_PROGRESS';
      header.clearedAt = null;
    }
    pushHistory(db, row, null, 'PENDING', null);
    return ok(taskDto(row, db), undefined, { status: 201 });
  }),

  http.get(url('/onboarding/prereq-tasks/:prereqTaskId'), ({ params }) => {
    const db = getDb();
    const task = findTask(db, Number(params.prereqTaskId));
    return task ? ok(taskDetailDto(task, db)) : notFound('Prerequisite task');
  }),

  http.patch(url('/onboarding/prereq-tasks/:prereqTaskId'), async ({ params, request }) => {
    const db = getDb();
    const task = findTask(db, Number(params.prereqTaskId));
    if (!task) return notFound('Prerequisite task');
    if (task.status === 'VERIFIED' || task.status === 'SKIPPED') {
      return unprocessable('A settled task is not reworded — the client agreed to what it said.');
    }
    const body = (await request.json()) as { title?: string; description?: string | null; tatDays?: number };
    if (body.title !== undefined) {
      if (!body.title.trim()) return validationFailed({ title: ['must not be blank'] });
      task.title = body.title;
    }
    if (body.description !== undefined) task.description = body.description;
    // dueAt is recomputed against the calendar, never taken from the client.
    if (body.tatDays !== undefined) task.dueAt = dueFromTat(body.tatDays);
    return ok(taskDto(task, db));
  }),

  http.post(url('/onboarding/prereq-tasks/:prereqTaskId/submit'), async ({ params, request }) => {
    const db = getDb();
    const task = findTask(db, Number(params.prereqTaskId));
    if (!task) return notFound('Prerequisite task');
    if (task.status !== 'PENDING') {
      return problem(422, 'ob-prereq-not-submittable', `The task is ${task.status}.`, { status_: task.status });
    }
    const body = (await request.json().catch(() => ({}))) as { note?: string | null; attachmentIds?: number[] };
    const from = task.status;
    task.status = 'SUBMITTED';
    task.submittedAt = new Date().toISOString();
    // Staff here, because the mock's principal is always a staff user. The
    // column exists because a SPOC frequently emails a document instead.
    task.submittedVia = 'STAFF';
    for (const attachmentId of body.attachmentIds ?? []) {
      task.submissions.push({
        attachmentId, fileName: `submission-${attachmentId}.pdf`, sizeBytes: 110_000,
        uploadedByType: 'STAFF', uploadedAt: task.submittedAt,
      });
    }
    pushHistory(db, task, from, 'SUBMITTED', body.note ?? null);
    return ok(taskDto(task, db));
  }),

  http.post(url('/onboarding/prereq-tasks/:prereqTaskId/verify'), async ({ params, request }) => {
    const db = getDb();
    const task = findTask(db, Number(params.prereqTaskId));
    if (!task) return notFound('Prerequisite task');
    if (task.status !== 'SUBMITTED') {
      return problem(422, 'ob-prereq-not-verifiable', `The task is ${task.status}, not SUBMITTED.`);
    }
    const body = (await request.json().catch(() => ({}))) as { note?: string | null };
    const from = task.status;
    task.status = 'VERIFIED';
    task.verifiedAt = new Date().toISOString();
    task.verifiedById = currentUser(db).id;
    pushHistory(db, task, from, 'VERIFIED', body.note ?? null);
    return applyGate(db, task);
  }),

  http.post(url('/onboarding/prereq-tasks/:prereqTaskId/return'), async ({ params, request }) => {
    const db = getDb();
    const task = findTask(db, Number(params.prereqTaskId));
    if (!task) return notFound('Prerequisite task');
    if (task.status !== 'SUBMITTED') {
      return problem(422, 'ob-prereq-not-returnable', `The task is ${task.status}, not SUBMITTED.`);
    }
    const { comment } = (await request.json()) as { comment?: string };
    if (!comment?.trim()) return validationFailed({ comment: ['must not be blank'] });

    const from = task.status;
    task.status = 'PENDING';
    // The clock is NOT reset — an incomplete submission does not buy an
    // extension on time attributed to the client (plan §5.4).
    db.obPrereqComments.push({
      id: nextId(db, 'obPrereqComment'), prereqTaskId: task.id,
      authorType: 'STAFF', staffAuthorId: currentUser(db).id, clientContactId: null,
      body: comment, isSystem: true, createdAt: new Date().toISOString(),
    });
    pushHistory(db, task, from, 'PENDING', comment);
    return ok(taskDto(task, db));
  }),

  http.post(url('/onboarding/prereq-tasks/:prereqTaskId/skip'), async ({ params, request }) => {
    const db = getDb();
    const task = findTask(db, Number(params.prereqTaskId));
    if (!task) return notFound('Prerequisite task');
    // Rule 2. A skippable mandatory task is not a mandatory task, so this is a
    // 422 about the row rather than a 403 about the caller.
    if (task.isMandatory) {
      return problem(422, 'ob-prereq-mandatory-not-skippable',
        'A mandatory task cannot be waived — the gate would be a convention rather than a guarantee.');
    }
    if (task.status === 'VERIFIED' || task.status === 'SKIPPED') {
      return problem(422, 'ob-prereq-not-skippable', `The task is already ${task.status}.`);
    }
    const { reason } = (await request.json()) as { reason?: string };
    if (!reason?.trim()) return validationFailed({ reason: ['must not be blank'] });

    const from = task.status;
    task.status = 'SKIPPED';
    task.skippedAt = new Date().toISOString();
    task.skippedById = currentUser(db).id;
    task.skipReason = reason;
    pushHistory(db, task, from, 'SKIPPED', reason);
    return applyGate(db, task);
  }),

  http.get(url('/onboarding/prereq-tasks/:prereqTaskId/comments'), ({ params, request }) => {
    const db = getDb();
    const id = Number(params.prereqTaskId);
    if (!findTask(db, id)) return notFound('Prerequisite task');
    const rows = db.obPrereqComments
      .filter((c) => c.prereqTaskId === id)
      .sort((a, b) => a.createdAt.localeCompare(b.createdAt));
    const { page, meta } = paginate(rows, new URL(request.url));
    return ok(page.map((c) => ({
      id: c.id, prereqTaskId: c.prereqTaskId, authorType: c.authorType,
      staffAuthor: userRef(c.staffAuthorId, db),
      clientAuthor: null,
      body: c.body, isSystem: c.isSystem, createdAt: c.createdAt,
    })), meta);
  }),

  http.post(url('/onboarding/prereq-tasks/:prereqTaskId/comments'), async ({ params, request }) => {
    const db = getDb();
    const id = Number(params.prereqTaskId);
    if (!findTask(db, id)) return notFound('Prerequisite task');
    const { body } = (await request.json()) as { body?: string };
    if (!body?.trim()) return validationFailed({ body: ['must not be blank'] });

    // The author comes from the principal, never from the body — a client who
    // could name their own author id could write as a member of staff.
    const row = {
      id: nextId(db, 'obPrereqComment'), prereqTaskId: id,
      authorType: 'STAFF' as const, staffAuthorId: currentUser(db).id, clientContactId: null,
      body, isSystem: false, createdAt: new Date().toISOString(),
    };
    db.obPrereqComments.push(row);
    return ok({
      id: row.id, prereqTaskId: row.prereqTaskId, authorType: row.authorType,
      staffAuthor: userRef(row.staffAuthorId, db), clientAuthor: null,
      body: row.body, isSystem: row.isSystem, createdAt: row.createdAt,
    }, undefined, { status: 201 });
  }),

  http.get(url('/onboarding/prereq-tasks/:prereqTaskId/history'), ({ params, request }) => {
    const db = getDb();
    const id = Number(params.prereqTaskId);
    if (!findTask(db, id)) return notFound('Prerequisite task');
    const rows = db.obPrereqHistory
      .filter((h) => h.prereqTaskId === id)
      .sort((a, b) => a.at.localeCompare(b.at));
    const { page, meta } = paginate(rows, new URL(request.url));
    return ok(page.map((h) => ({
      id: h.id, prereqTaskId: h.prereqTaskId, at: h.at, actorType: h.actorType,
      staffActor: userRef(h.staffActorId, db), clientActor: null,
      fromStatus: h.fromStatus, toStatus: h.toStatus, reason: h.reason,
      isCorrection: h.isCorrection, correctsEntryId: h.correctsEntryId,
    })), meta);
  }),
];
