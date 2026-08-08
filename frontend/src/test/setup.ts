import '@testing-library/jest-dom';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { server } from '../mocks/server';
import { resetDb } from '../mocks/db';

/**
 * Every test runs against the mock API. `onUnhandledRequest: 'error'` is
 * deliberate: a request with no handler should fail the test loudly rather than
 * escape to the network and fail later with something unrelated.
 */
beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' });
  // Must come after listen(): MSW installs its own global fetch, and it is
  // MSW's `new Request(...)` that throws. Wrapping first would put this shim
  // underneath MSW, where mocked responses never reach it.
  makeAbortSignalsCrossRealmSafe();
});

afterEach(() => {
  server.resetHandlers();
  // The mock DB is stateful, so one test's handoff would otherwise leak into
  // the next and produce a failure that only reproduces in a full run.
  resetDb();
});

afterAll(() => server.close());

/**
 * Let a jsdom `AbortSignal` survive contact with Node's `fetch`.
 *
 * Node 24 brand-checks the signal on `RequestInit` and rejects anything that is
 * not *its own* `AbortSignal`. Under vitest's jsdom environment the global
 * `AbortController` is jsdom's, so every signal is from the wrong realm and
 * every request dies with:
 *
 *     TypeError: RequestInit: Expected signal ("AbortSignal {}")
 *                to be an instance of AbortSignal.
 *
 * TanStack Query attaches a signal to every query, so on Node 24 this failed
 * *all* of them — `/me`, `/projects`, `/notifications`, `/chat/threads` — and the
 * app rendered permanently empty. CI pins Node 22, where the check is lenient,
 * so it stayed green while a clean checkout was red for anyone on 24.
 *
 * There is no way to hand jsdom Node's class: the two live in different realms
 * and even `AbortSignal.timeout()` produces a jsdom instance. So the signal is
 * removed before `fetch` sees it and the abort is honoured by racing the
 * request instead.
 *
 * **The request itself is not cancelled**, only the promise settles early. That
 * is the one behavioural difference, and it is invisible to a test asserting on
 * rendered output. Anything asserting that an in-flight request was truly torn
 * down should not rely on this.
 *
 * Delete this the day Node and jsdom agree, or the day CI and developers run
 * the same major version.
 */
function makeAbortSignalsCrossRealmSafe(): void {
  const inner = globalThis.fetch;

  globalThis.fetch = function patchedFetch(input, init) {
    const signal = init?.signal;
    if (!signal) return inner(input, init);

    const rest: RequestInit = { ...(init as RequestInit) };
    delete rest.signal;

    if (signal.aborted) {
      return Promise.reject(new DOMException('The operation was aborted.', 'AbortError'));
    }

    return Promise.race([
      inner(input, rest),
      new Promise<Response>((_resolve, reject) => {
        signal.addEventListener(
          'abort',
          () => reject(new DOMException('The operation was aborted.', 'AbortError')),
          { once: true },
        );
      }),
    ]);
  } as typeof globalThis.fetch;
}
