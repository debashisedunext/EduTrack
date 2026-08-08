import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { parse } from 'yaml';
import { describe, expect, it } from 'vitest';
import { handlers } from './handlers';
import { BASE } from './handlers/util';

/**
 * D-004 · every contract operation has a mock handler.
 *
 * **Why this exists.** CI compares the *generated client* to the spec (D-005),
 * so a new endpoint can never ship with a stale client. Nothing compared the
 * *mocks* to the spec, so a new endpoint shipped unmocked and silently — and
 * the person who found out was whoever built against it, days later, with a 501
 * and no idea whose job it was. D-053 added `searchChatMessages` and this gap
 * is exactly how it went unnoticed.
 *
 * The check reads MSW's own handler registry rather than grepping source, so it
 * cannot be fooled by a handler that is defined but never exported into
 * `handlers`. That is the failure it most needs to catch: an unexported handler
 * looks completely correct in review.
 */

const VERBS = ['get', 'post', 'put', 'patch', 'delete'] as const;

/**
 * A route key both sides can be compared on.
 *
 * <p>Path parameters are reduced to a placeholder rather than compared by name.
 * MSW matches by position, so a handler written as `:id` where the contract
 * says `{notificationId}` is entirely correct — failing the build for that
 * would be a false alarm about naming dressed up as a coverage gap, and the
 * quickest way to teach everyone to distrust this test. Literal segments and
 * arity still have to match, which is what actually decides whether a request
 * finds a handler.
 */
function routeKey(path: string): string {
  return path.replace(/\{(\w+)\}/g, ':param').replace(/:(\w+)/g, ':param');
}

/** `/chat/threads/{threadId}` in the spec is `/api/v1/chat/threads/:threadId` to MSW. */
function toMswPath(specPath: string): string {
  return BASE + specPath;
}

function specOperations(): { method: string; path: string; operationId: string }[] {
  // Vite rewrites import.meta.url to a non-file scheme, so resolve from the
  // working directory instead — vitest runs with `frontend/` as cwd.
  const specFile = resolve(process.cwd(), '../contracts/openapi.yaml');
  const spec = parse(readFileSync(specFile, 'utf8')) as {
    paths: Record<string, Record<string, { operationId?: string }>>;
  };

  return Object.entries(spec.paths).flatMap(([path, item]) =>
    VERBS.filter((verb) => item[verb]).map((verb) => ({
      method: verb.toUpperCase(),
      path: toMswPath(path),
      operationId: item[verb].operationId ?? `${verb.toUpperCase()} ${path}`,
    })),
  );
}

/** What MSW will actually match, as MSW itself sees it. */
function mockedRoutes(): Set<string> {
  const routes = new Set<string>();
  for (const handler of handlers) {
    const info = (handler as { info?: { method?: unknown; path?: unknown } }).info;
    // The two catch-alls are `http.all(...)` with a wildcard path. They must not
    // count as coverage — the whole point of the second one is to 501 loudly
    // for exactly the endpoints this test is looking for.
    if (typeof info?.path !== 'string' || info.path.endsWith('/*')) continue;
    routes.add(`${String(info.method).toUpperCase()} ${routeKey(info.path)}`);
  }
  return routes;
}

describe('mock coverage', () => {
  it('every contract operation has a handler', () => {
    const mocked = mockedRoutes();

    const missing = specOperations()
      .filter(({ method, path }) => !mocked.has(`${method} ${routeKey(path)}`))
      .map(({ operationId, method, path }) => `${operationId} — ${method} ${path}`);

    expect(
      missing,
      `Contract operations with no MSW handler. Add them in frontend/src/mocks/handlers/ `
        + `(Stream D owns D-004), or Stream C builds against a 501:\n  ${missing.join('\n  ')}`,
    ).toEqual([]);
  });

  it('reads a spec that actually has operations in it', () => {
    // Without this, a spec that failed to parse — or a refactor that renamed
    // `paths` — would make the test above pass by finding nothing to check.
    expect(specOperations().length).toBeGreaterThan(50);
  });
});
