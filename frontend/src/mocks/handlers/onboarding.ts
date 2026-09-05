import { http } from 'msw';
import type { Db, ObApplication, ObClient, ObContact, ObJourney, ObProduct, ObStep } from '../db';
import { getDb, nextId } from '../db';
import { notFound, ok, paginate, problem, url, userRef, validationFailed } from './util';

/**
 * A-118 · mocks for the Client Onboarding module.
 *
 * **Stream A adding handlers in Stream D's directory (D-004)** — the same
 * situation `dashboardTabs.ts` documents, and flagged here the same way rather
 * than done quietly. `coverage.test.ts` refuses a contract operation with no
 * handler, so the alternative to this file is a red `develop` the moment the
 * contract lands.
 *
 * **Nothing here joins the ticketing fixtures.** `db.obClients` is a separate
 * table from `db.clients` with no key between them (plan §1.2); the one
 * sanctioned bridge is `client_accounts` at the identity layer, and it is not
 * in this slice. The mock is the first place that separation is either kept or
 * quietly lost, so it is kept here even where a join would be convenient.
 *
 * ## What is deliberately modelled rather than stubbed
 *
 * Four rules that a screen can be built entirely wrong against if the mock
 * waves them through, so all four are enforced here:
 *
 * 1. **RAG is null while the gate is locked**, not GREEN. Nothing is running
 *    to colour, and OB-03 renders that as "Prerequisites pending".
 * 2. **`LIVE` cannot be set** — `422`. It is the go-live flip, earned when
 *    every journey completes.
 * 3. **PAN is masked on every read**, including the detail, because the reveal
 *    operation and its audit are A-113 and do not exist yet. Masked for
 *    everyone is the safe direction to be wrong in.
 * 4. **A product with no active template cannot be bought** — `409`. A
 *    purchase with nothing to instantiate would board a client into nothing.
 */

// ── mappers to the contract's shapes ────────────────────────────────────────

const productRef = (id: number, db: Db) => {
  const p = db.obProducts.find((x) => x.id === id);
  return p ? { id: p.id, code: p.code, name: p.name } : null;
};

/**
 * `ABCDE1234F` → `ABCDE****F`.
 *
 * The masking rule lives here and not in a component: a field the server masks
 * is a field the client never receives unmasked, and a mock that sent the real
 * value would let a screen "work" by unmasking something the API will not send.
 */
const maskPan = (pan: string | null): string | null =>
  pan && pan.length >= 6 ? `${pan.slice(0, 5)}****${pan.slice(-1)}` : pan;

/**
 * A step's own colour: RED once its TAT is spent, AMBER from 75%, else GREEN.
 *
 * `WAITING_ON_CLIENT` is **not** red however long it has waited — the clock is
 * paused and the wait is attributed to the client (plan §5.7). Colouring it
 * would make every TAT report disputable, which is the failure the pause
 * exists to prevent.
 */
const AMBER_AT = 0.75;
const HOURS_PER_DAY = 9;

/** Exported for `onboardingAdmin.ts` (A-118) — a third copy of this rule is
 * how one of them ends up disagreeing with the other two about amber. */
export function stepRag(step: ObStep): 'GREEN' | 'AMBER' | 'RED' | null {
  if (step.status === 'DONE' || step.status === 'SKIPPED') return 'GREEN';
  if (step.status === 'PENDING' || step.status === 'WAITING_ON_CLIENT') return null;
  if (step.status === 'BLOCKED') return 'RED';
  const spent = step.usedHours / (step.tatDays * HOURS_PER_DAY);
  if (spent >= 1) return 'RED';
  return spent >= AMBER_AT ? 'AMBER' : 'GREEN';
}

/** Worst-wins, the roll-up of plan §5.9 at every level. */
function worst(rags: (string | null)[]): 'GREEN' | 'AMBER' | 'RED' | null {
  if (rags.includes('RED')) return 'RED';
  if (rags.includes('AMBER')) return 'AMBER';
  return rags.includes('GREEN') ? 'GREEN' : null;
}

function journeyRag(journey: ObJourney): 'GREEN' | 'AMBER' | 'RED' | null {
  if (journey.gateStatus === 'LOCKED') return null;
  return worst(journey.steps.map(stepRag));
}

/** Worst across **open** journeys only. Null while every journey is locked. */
function clientRag(client: ObClient): 'GREEN' | 'AMBER' | 'RED' | null {
  return worst(client.journeys.filter((j) => j.gateStatus === 'OPEN').map(journeyRag));
}

/** A client's gate is open once any journey's is — they clear together. */
const gateStatusOf = (client: ObClient): 'LOCKED' | 'OPEN' =>
  client.journeys.some((j) => j.gateStatus === 'OPEN') ? 'OPEN' : 'LOCKED';

const journeyIsComplete = (j: ObJourney): boolean =>
  j.steps.every((s) => s.status === 'DONE' || s.status === 'SKIPPED');

function stepDto(step: ObStep) {
  return {
    id: step.id,
    sequence: step.sequence,
    name: step.name,
    status: step.status,
    rag: stepRag(step),
    dependsOnStepId: step.dependsOnStepId,
  };
}

function journeyDto(journey: ObJourney, db: Db) {
  const done = journey.steps.filter((s) => s.status === 'DONE' || s.status === 'SKIPPED').length;
  return {
    id: journey.id,
    product: productRef(journey.productId, db),
    gateStatus: journey.gateStatus,
    rag: journeyRag(journey),
    percentComplete: journey.steps.length
      ? Math.round((done / journey.steps.length) * 100)
      : 0,
    heldByJourneyId: journey.heldByJourneyId,
    totalTatDays: journey.steps.reduce((sum, s) => sum + s.tatDays, 0),
    // Derived from the steps at read time — never a stored aggregate, which
    // could disagree with the parts it claims to sum.
    utilizedHours: Math.round(journey.steps.reduce((sum, s) => sum + s.usedHours, 0) * 10) / 10,
    steps: journey.steps.map(stepDto),
  };
}

function productDto(p: ObProduct, db: Db) {
  return {
    ...p,
    journeyCount: db.obClients.reduce(
      (n, c) => n + c.journeys.filter((j) => j.productId === p.id).length,
      0,
    ),
  };
}

const contactDto = (c: ObContact) => ({ ...c });

const applicationDto = (a: ObApplication, db: Db) => ({
  id: a.id,
  product: productRef(a.productId, db),
  licenseType: a.licenseType,
  units: a.units,
  licenseStart: a.licenseStart,
  licenseEnd: a.licenseEnd,
});

/** The OB-03 row. No PAN and no address — identity data belongs to the detail. */
function obClientDto(c: ObClient, db: Db) {
  return {
    id: c.id,
    name: c.name,
    onboardingDate: c.onboardingDate,
    status: c.status,
    rag: clientRag(c),
    gateStatus: gateStatusOf(c),
    journeyCount: c.journeys.length,
    journeysComplete: c.journeys.filter(journeyIsComplete).length,
    products: c.applications.map((a) => productRef(a.productId, db)).filter(Boolean),
    salesPerson: userRef(c.salesPersonId, db),
    primaryContact: c.contacts.find((x) => x.isPrimary) ?? null,
    liveAt: c.liveAt,
    hasPortalLogin: c.hasPortalLogin,
  };
}

function obClientDetailDto(c: ObClient, db: Db) {
  return {
    ...obClientDto(c, db),
    description: c.description,
    address: c.address,
    licenseType: c.licenseType,
    pan: maskPan(c.pan),
    contacts: c.contacts.map(contactDto),
    applications: c.applications.map((a) => applicationDto(a, db)),
    requirements: c.requirements,
    journeys: c.journeys.map((j) => journeyDto(j, db)),
    createdBy: userRef(c.createdById, db),
    createdAt: c.createdAt,
  };
}

// ── the similar-name guard ──────────────────────────────────────────────────
/**
 * Normalised comparison, so "Acme Pvt Ltd" and "Acme Private Limited" collide.
 *
 * Deliberately crude — the real check is a trigram index. What matters for a
 * screen built against this is that the `409` is *reachable* and *forceable*;
 * a mock that never fired it would let the acknowledge path ship untested.
 */
const SUFFIXES = /\b(pvt|private|ltd|limited|llp|inc|corp|corporation|co|company|trust|technologies|tech)\b/g;

const normaliseName = (name: string): string =>
  name.toLowerCase().replace(/[^a-z0-9\s]/g, ' ').replace(SUFFIXES, ' ').replace(/\s+/g, ' ').trim();

// ── handlers ────────────────────────────────────────────────────────────────

export const onboardingHandlers = [
  // ── products ──────────────────────────────────────────────────────────────
  http.get(url('/onboarding/products'), ({ request }) => {
    const db = getDb();
    const isActive = new URL(request.url).searchParams.get('isActive');
    let rows = [...db.obProducts].sort((a, b) => a.name.localeCompare(b.name));
    if (isActive != null) rows = rows.filter((p) => p.isActive === (isActive === 'true'));
    // No `meta`: unpaginated by CONVENTIONS.md §6, and the absent meta is the
    // signal that the list is complete.
    return ok(rows.map((p) => productDto(p, db)));
  }),

  http.post(url('/onboarding/products'), async ({ request }) => {
    const db = getDb();
    const body = (await request.json()) as Partial<ObProduct>;
    if (!body.code || !body.name) {
      return validationFailed({
        ...(body.code ? {} : { code: ['Code is required'] }),
        ...(body.name ? {} : { name: ['Name is required'] }),
      });
    }
    // Case-insensitive, like `uq_ob_products_code` under utf8mb4_0900_ai_ci.
    if (db.obProducts.some((p) => p.code.toLowerCase() === body.code!.toLowerCase())) {
      return problem(409, 'ob-product-code-duplicate', 'That product code already exists', {
        errors: { code: ['Already in use'] },
      });
    }
    const created: ObProduct = {
      id: nextId(db, 'obProduct') + db.obProducts.length,
      code: body.code,
      name: body.name,
      isActive: body.isActive ?? true,
      // A product is not bookable until a template exists for it — creating one
      // here does not make it selectable on OB-04.
      hasActiveTemplate: false,
      totalTatDays: null,
    };
    db.obProducts.push(created);
    return ok(productDto(created, db), undefined, { status: 201 });
  }),

  http.get(url('/onboarding/products/:obProductId'), ({ params }) => {
    const db = getDb();
    const p = db.obProducts.find((x) => x.id === Number(params.obProductId));
    return p ? ok(productDto(p, db)) : notFound('Product');
  }),

  http.patch(url('/onboarding/products/:obProductId'), async ({ params, request }) => {
    const db = getDb();
    const p = db.obProducts.find((x) => x.id === Number(params.obProductId));
    if (!p) return notFound('Product');
    const body = (await request.json()) as Partial<ObProduct>;
    const inUse = db.obClients.some((c) => c.journeys.some((j) => j.productId === p.id));
    if (body.code != null && body.code !== p.code && inUse) {
      return problem(409, 'ob-product-code-immutable',
        'The code cannot change once a client has bought this product', {
          errors: { code: ['Immutable — clients have journeys for this product'] },
        });
    }
    if (body.code != null) p.code = body.code;
    if (body.name != null) p.name = body.name;
    // Retiring changes the picker and nothing else — in-flight journeys keep
    // running, which is why nothing below touches them.
    if (body.isActive != null) p.isActive = body.isActive;
    return ok(productDto(p, db));
  }),

  // ── clients ───────────────────────────────────────────────────────────────
  http.get(url('/onboarding/clients'), ({ request }) => {
    const db = getDb();
    const q = new URL(request.url);
    let rows = [...db.obClients].sort(
      (a, b) => b.onboardingDate.localeCompare(a.onboardingDate) || a.id - b.id,
    );

    const term = q.searchParams.get('q');
    if (term) {
      const needle = term.toLowerCase();
      // Name only. Never PAN — it is masked on the way out, so matching on it
      // here would make the mock an oracle for a value the API will not return.
      rows = rows.filter((c) => c.name.toLowerCase().includes(needle));
    }
    const status = q.searchParams.get('status');
    if (status) rows = rows.filter((c) => c.status === status);
    const gate = q.searchParams.get('gateStatus');
    if (gate) rows = rows.filter((c) => gateStatusOf(c) === gate);
    const rag = q.searchParams.get('rag');
    // A locked client has no colour, so it matches none of the three — which is
    // why `gateStatus` exists as a separate filter rather than a fourth value.
    if (rag) rows = rows.filter((c) => clientRag(c) === rag);
    const productId = q.searchParams.get('productId');
    if (productId) {
      rows = rows.filter((c) => c.journeys.some((j) => j.productId === Number(productId)));
    }
    const salesPersonId = q.searchParams.get('salesPersonId');
    if (salesPersonId) rows = rows.filter((c) => c.salesPersonId === Number(salesPersonId));

    const { page, meta } = paginate(rows, q);
    return ok(page.map((c) => obClientDto(c, db)), meta);
  }),

  http.post(url('/onboarding/clients'), async ({ request }) => {
    const db = getDb();
    const body = (await request.json()) as {
      name?: string; description?: string | null; onboardingDate?: string;
      pan?: string | null; address?: string | null; salesPersonId?: number | null;
      licenseType?: string | null;
      contacts?: ObContact[];
      applications?: { productId: number; licenseType?: string | null; units?: number | null;
        licenseStart?: string | null; licenseEnd?: string | null }[];
      requirements?: string[];
      createPortalLogin?: boolean;
      acknowledgeSimilarNames?: boolean;
    };

    const errors: Record<string, string[]> = {};
    if (!body.name) errors.name = ['Name is required'];
    if (!body.onboardingDate) errors.onboardingDate = ['Onboarding date is required'];
    if (!body.contacts?.length) errors.contacts = ['At least one SPOC is required'];
    else if (body.contacts.filter((c) => c.isPrimary).length !== 1) {
      errors.contacts = ['Exactly one contact must be primary'];
    }
    if (!body.applications?.length) errors.applications = ['At least one product is required'];
    if (Object.keys(errors).length) return validationFailed(errors);

    if (body.pan && db.obClients.some((c) => c.pan === body.pan)) {
      // Final. Two rows for one legal entity is the state the guard exists to
      // prevent, so there is no acknowledge flag for this one.
      return problem(409, 'ob-client-pan-duplicate', 'A client with that PAN already exists', {
        errors: { pan: ['Already boarded'] },
      });
    }

    if (!body.acknowledgeSimilarNames) {
      const needle = normaliseName(body.name!);
      const similar = db.obClients.filter((c) => normaliseName(c.name) === needle);
      if (similar.length) {
        return problem(409, 'ob-client-name-similar',
          'A client with a very similar name already exists', {
            detail: similar.map((c) => c.name).join(', '),
            hint: 'Resubmit with acknowledgeSimilarNames: true if these are different companies.',
          });
      }
    }

    for (const app of body.applications!) {
      const product = db.obProducts.find((p) => p.id === app.productId);
      if (!product) return validationFailed({ applications: [`Unknown product ${app.productId}`] });
      if (!product.hasActiveTemplate) {
        return problem(409, 'ob-product-no-template',
          `${product.name} has no active journey template`, {
            detail: 'A purchase with no template to instantiate would board this client into nothing.',
          });
      }
    }

    const clientId = Math.max(0, ...db.obClients.map((c) => c.id)) + 1;
    let contactId = Math.max(0, ...db.obClients.flatMap((c) => c.contacts.map((x) => x.id)));
    let applicationId = Math.max(0, ...db.obClients.flatMap((c) => c.applications.map((x) => x.id)));
    let journeyId = Math.max(0, ...db.obClients.flatMap((c) => c.journeys.map((j) => j.id)));
    let stepId = Math.max(0, ...db.obClients.flatMap((c) => c.journeys.flatMap((j) => j.steps.map((s) => s.id))));

    const created: ObClient = {
      id: clientId,
      name: body.name!,
      description: body.description ?? null,
      onboardingDate: body.onboardingDate!,
      pan: body.pan ?? null,
      address: body.address ?? null,
      licenseType: body.licenseType ?? null,
      salesPersonId: body.salesPersonId ?? null,
      status: 'ONBOARDING',
      liveAt: null,
      hasPortalLogin: body.createPortalLogin ?? false,
      contacts: body.contacts!.map((c) => ({ ...c, id: ++contactId })),
      applications: body.applications!.map((a) => ({
        id: ++applicationId,
        productId: a.productId,
        licenseType: a.licenseType ?? null,
        units: a.units ?? null,
        licenseStart: a.licenseStart ?? null,
        licenseEnd: a.licenseEnd ?? null,
      })),
      requirements: body.requirements ?? [],
      // One journey per purchased product, every one LOCKED. Steps are copied
      // from an existing journey for that product, which stands in for the
      // template snapshot the real service takes — the point being that the
      // plan is fully visible from day one with no clock running.
      journeys: body.applications!.map((a) => {
        const specimen = db.obClients
          .flatMap((c) => c.journeys)
          .find((j) => j.productId === a.productId);
        return {
          id: ++journeyId,
          productId: a.productId,
          gateStatus: 'LOCKED' as const,
          heldByJourneyId: null,
          steps: (specimen?.steps ?? []).map((s) => ({
            id: ++stepId,
            sequence: s.sequence,
            name: s.name,
            status: 'PENDING' as const,
            tatDays: s.tatDays,
            usedHours: 0,
            dependsOnStepId: null,
          })),
        };
      }),
      createdById: db.currentUserId,
      createdAt: new Date().toISOString(),
    };

    db.obClients.push(created);
    return ok(obClientDetailDto(created, db), undefined, { status: 201 });
  }),

  http.get(url('/onboarding/clients/:obClientId'), ({ params }) => {
    const db = getDb();
    const c = db.obClients.find((x) => x.id === Number(params.obClientId));
    return c ? ok(obClientDetailDto(c, db)) : notFound('Client');
  }),

  http.patch(url('/onboarding/clients/:obClientId'), async ({ params, request }) => {
    const db = getDb();
    const c = db.obClients.find((x) => x.id === Number(params.obClientId));
    if (!c) return notFound('Client');
    const body = (await request.json()) as Partial<ObClient> & { statusReason?: string | null };

    if (body.status === 'LIVE') {
      return problem(422, 'ob-client-live-not-earned',
        'LIVE is earned when every journey completes, never set directly');
    }
    if ((body.status === 'ON_HOLD' || body.status === 'DROPPED') && !body.statusReason) {
      return validationFailed({ statusReason: ['A reason is required for this status'] });
    }

    if (body.name != null) c.name = body.name;
    if (body.description !== undefined) c.description = body.description;
    if (body.address !== undefined) c.address = body.address;
    if (body.salesPersonId !== undefined) c.salesPersonId = body.salesPersonId;
    if (body.licenseType !== undefined) c.licenseType = body.licenseType;
    if (body.status != null) c.status = body.status;
    // `pan`, `contacts` and `applications` are absent deliberately — immutable,
    // and two operations with side effects a field update cannot carry.
    return ok(obClientDetailDto(c, db));
  }),
];
