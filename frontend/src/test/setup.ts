import '@testing-library/jest-dom';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { server } from '../mocks/server';
import { resetDb } from '../mocks/db';

/**
 * Every test runs against the mock API. `onUnhandledRequest: 'error'` is
 * deliberate: a request with no handler should fail the test loudly rather than
 * escape to the network and fail later with something unrelated.
 */
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));

afterEach(() => {
  server.resetHandlers();
  // The mock DB is stateful, so one test's handoff would otherwise leak into
  // the next and produce a failure that only reproduces in a full run.
  resetDb();
});

afterAll(() => server.close());
