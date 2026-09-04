import { delay, http } from 'msw';
import { dashboardTabHandlers } from './dashboardTabs';
import { fileHandlers } from './files';
import { onboardingHandlers } from './onboarding';
import { onboardingJourneyHandlers } from './onboardingJourneys';
import { ribbonHandlers } from './ribbon';
import { restHandlers } from './rest';
import { slaHandlers } from './sla';
import { ticketHandlers } from './tickets';
import { BASE, LATENCY_MS, problem } from './util';

/**
 * Every handler, in one list. D-004.
 *
 * Order matters: the catch-all at the end must stay last.
 */
export const handlers = [
  // A little latency on everything, so loading states are visible while
  // developing rather than only appearing on a slow connection in production.
  //
  // Returning nothing falls through to the next matching handler. Returning
  // `passthrough()` would NOT — that bypasses every remaining handler and sends
  // the request to the real network, where it fails with "fetch failed" and
  // looks nothing like a mocking problem.
  http.all(`${BASE}/*`, async () => {
    await delay(LATENCY_MS);
  }),

  // Before `ticketHandlers`: `/tickets/planned-close-date` is a literal segment
  // sitting where `/tickets/:ticketId` also matches, and MSW takes the first
  // handler that does. The literal could never be a valid ticket id, but the
  // pattern does not know that.
  ...slaHandlers,
  ...ticketHandlers,
  ...ribbonHandlers,
  ...restHandlers,
  // S-05 rework · the three tab endpoints. After restHandlers, which holds the
  // other dashboard routes; no path overlaps, so order is presentation only.
  ...dashboardTabHandlers,

  // A-118 · the onboarding module. Nothing under /onboarding overlaps any
  // ticketing route, so position here is presentation only — the module's
  // whole premise is that its paths, tables and schemas are disjoint.
  ...onboardingHandlers,
  // C-102 · the OB-07 template designer's own routes. See onboardingJourneys.ts.
  ...onboardingJourneyHandlers,

  // C-026 · `/mock-files/*`, the stand-in object store. Deliberately outside the
  // `/api/v1` prefix, because a signed URL points at MinIO and not at the API —
  // which is also why the 501 catch-all below never covered it, and why every
  // attachment URL the mock has minted since C-023 had nothing behind it.
  ...fileHandlers,

  /**
   * Anything under /api/v1 with no handler.
   *
   * **This exists so a missing mock is loud.** Without it, MSW logs a warning
   * and lets the request hit the network, where it fails with a confusing CORS
   * or 404 error and the developer goes looking in the wrong place. A 501
   * naming the method and path says exactly what is missing and who adds it.
   */
  http.all(`${BASE}/*`, ({ request }) => {
    const path = new URL(request.url).pathname;
    return problem(
      501,
      'mock-not-implemented',
      `No mock handler for ${request.method} ${path}`,
      { hint: 'Add one in frontend/src/mocks/handlers/ — Stream D owns D-004.' },
    );
  }),
];
