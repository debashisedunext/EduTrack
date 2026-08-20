import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { http, ApiError, setAccessToken, newIdempotencyKey } from './http';

/**
 * The mutator is the one piece of hand-written code every generated call goes
 * through. If it is wrong, all 79 endpoints are wrong in the same way.
 */

/** The same base http.ts uses, so these assertions survive a config change. */
const BASE = import.meta.env.VITE_API_BASE ?? '/api/v1';

const ok = (body: unknown, init: ResponseInit = {}) =>
  new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  });

/** Await a call that must reject, and get the ApiError back typed. */
async function rejectsWith(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise;
  } catch (error) {
    if (error instanceof ApiError) return error;
    throw error;
  }
  throw new Error('expected the request to reject with ApiError, but it resolved');
}

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
  setAccessToken(null);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('http', () => {
  it('prefixes the API base and returns the parsed envelope', async () => {
    fetchMock.mockImplementation(async () => ok({ data: { ticketId: 'CRM-26-00347' } }));

    const result = await http<{ data: { ticketId: string } }>({
      url: '/tickets/CRM-26-00347/full',
      method: 'GET',
    });

    expect(fetchMock).toHaveBeenCalledWith(
      `${BASE}/tickets/CRM-26-00347/full`,
      expect.objectContaining({ method: 'GET' }),
    );
    expect(result.data.ticketId).toBe('CRM-26-00347');
  });

  it('attaches the bearer token once set, and stops when cleared', async () => {
    fetchMock.mockImplementation(async () => ok({ data: {} }));
    setAccessToken('token-abc');
    await http({ url: '/me', method: 'GET' });
    expect(fetchMock.mock.calls[0][1].headers).toMatchObject({
      Authorization: 'Bearer token-abc',
    });

    setAccessToken(null);
    await http({ url: '/me', method: 'GET' });
    expect(fetchMock.mock.calls[1][1].headers).not.toHaveProperty('Authorization');
  });

  it('sends the refresh cookie — it is HttpOnly, so it has to ride along', async () => {
    fetchMock.mockImplementation(async () => ok({ data: {} }));
    await http({ url: '/auth/refresh', method: 'POST' });
    expect(fetchMock.mock.calls[0][1].credentials).toBe('include');
  });

  it('drops empty query params rather than sending cursor=&limit=', async () => {
    fetchMock.mockImplementation(async () => ok({ data: [] }));
    await http({
      url: '/tickets',
      method: 'GET',
      params: { cursor: undefined, limit: 50, status: '', level: null, stage: 'QA' },
    });
    expect(fetchMock.mock.calls[0][0]).toBe(`${BASE}/tickets?limit=50&stage=QA`);
  });

  it('comma-joins array params, matching explode:false in the spec', async () => {
    fetchMock.mockImplementation(async () => ok({ data: [] }));
    await http({
      url: '/tickets/T-1/history',
      method: 'GET',
      params: { include: ['comments', 'attachments'] },
    });
    expect(fetchMock.mock.calls[0][0]).toContain('include=comments%2Cattachments');
  });

  it('throws ApiError carrying the problem document', async () => {
    fetchMock.mockImplementation(async () =>
      new Response(
        JSON.stringify({
          type: 'https://edutrack/errors/stage-owner-required',
          title: 'Only the current stage owner may advance this ticket',
          status: 422,
        }),
        { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
      ),
    );

    const error = await rejectsWith(http({ url: '/tickets/T-1/handoff', method: 'POST' }));
    expect(error.status).toBe(422);
    expect(error.is('stage-owner-required')).toBe(true);
    expect(error.is('something-else')).toBe(false);
  });

  it('exposes field errors from a 400 for React Hook Form', async () => {
    fetchMock.mockImplementation(async () =>
      new Response(
        JSON.stringify({
          type: 'https://edutrack/errors/validation',
          title: 'Validation failed',
          status: 400,
          errors: { title: ['must not be blank'] },
        }),
        { status: 400 },
      ),
    );

    const error = await rejectsWith(http({ url: '/tickets', method: 'POST' }));
    expect(error.fieldErrors).toEqual({ title: ['must not be blank'] });
  });

  it('survives a non-JSON error body instead of failing inside the parse', async () => {
    // A proxy or gateway returning an HTML error page. Parsing this as JSON
    // would surface "Unexpected token <", which sends you to the wrong layer.
    fetchMock.mockImplementation(async () =>
      new Response('<html>504 Gateway Timeout</html>', {
        status: 504,
        statusText: 'Gateway Timeout',
      }),
    );

    const error = await rejectsWith(http({ url: '/tickets', method: 'GET' }));
    expect(error.status).toBe(504);
    expect(error.problem.title).toBe('Gateway Timeout');
  });

  it('returns undefined for 204 and 304 rather than parsing an empty body', async () => {
    fetchMock.mockImplementation(async () => new Response(null, { status: 204 }));
    await expect(http({ url: '/notifications/1/read', method: 'PATCH' })).resolves.toBeUndefined();

    fetchMock.mockImplementation(async () => new Response(null, { status: 304 }));
    await expect(http({ url: '/dashboard/widget/velocity', method: 'GET' })).resolves.toBeUndefined();
  });

  it('does not set Content-Type on FormData — the browser must add the boundary', async () => {
    fetchMock.mockImplementation(async () => ok({ data: {} }));
    const form = new FormData();
    form.append('file', new Blob(['x']), 'shot.png');

    await http({ url: '/tickets/T-1/attachments', method: 'POST', data: form });

    expect(fetchMock.mock.calls[0][1].headers).not.toHaveProperty('Content-Type');
    expect(fetchMock.mock.calls[0][1].body).toBe(form);
  });
});

describe('newIdempotencyKey', () => {
  it('returns a distinct key per call', () => {
    // Callers must generate one *before* the mutation and reuse it across
    // retries. A key created per attempt changes every time and protects
    // against nothing.
    expect(newIdempotencyKey()).not.toBe(newIdempotencyKey());
  });
});

// ── A-074 · the CSRF double submit ────────────────────────────────────────────

describe('http · CSRF', () => {
  /**
   * jsdom's `document.cookie` accumulates, so each test sets its own value and
   * the expiry below clears it again. Without that, a token set in one test
   * leaks into the next and the "no cookie" case passes for the wrong reason.
   */
  const setCookie = (value: string | null) => {
    if (value === null) {
      document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
    } else {
      document.cookie = `XSRF-TOKEN=${value}; path=/`;
    }
  };

  beforeEach(() => setCookie(null));
  afterEach(() => setCookie(null));

  it('echoes the XSRF-TOKEN cookie as a header on an unsafe request', async () => {
    fetchMock.mockImplementation(async () => ok({ data: {} }));
    setCookie('token-from-the-server');

    await http({ url: '/auth/refresh', method: 'POST' });

    expect(fetchMock.mock.calls[0][1].headers).toMatchObject({
      'X-XSRF-TOKEN': 'token-from-the-server',
    });
  });

  it.each(['POST', 'PUT', 'PATCH', 'DELETE'] as const)(
    'sends the header on %s',
    async (method) => {
      fetchMock.mockImplementation(async () => ok({ data: {} }));
      setCookie('t');

      await http({ url: '/tickets', method });

      expect(fetchMock.mock.calls[0][1].headers).toHaveProperty('X-XSRF-TOKEN', 't');
    },
  );

  it('does not send the header on GET — CsrfFilter exempts safe methods', async () => {
    fetchMock.mockImplementation(async () => ok({ data: {} }));
    setCookie('t');

    await http({ url: '/tickets', method: 'GET' });

    expect(fetchMock.mock.calls[0][1].headers).not.toHaveProperty('X-XSRF-TOKEN');
  });

  /**
   * The startup refresh runs before any cookie exists on a first-ever visit.
   * Sending `X-XSRF-TOKEN: undefined` would be worse than sending nothing — the
   * server would compare it against the absent cookie and refuse for a reason
   * that reads like a bug.
   */
  it('sends no header when there is no cookie', async () => {
    fetchMock.mockImplementation(async () => ok({ data: {} }));

    await http({ url: '/auth/refresh', method: 'POST' });

    expect(fetchMock.mock.calls[0][1].headers).not.toHaveProperty('X-XSRF-TOKEN');
  });

  it('reads the right cookie when others are present', async () => {
    fetchMock.mockImplementation(async () => ok({ data: {} }));
    document.cookie = 'other=first; path=/';
    setCookie('the-one-we-want');
    document.cookie = 'XSRF-TOKEN-DECOY=nope; path=/';

    await http({ url: '/auth/logout', method: 'POST' });

    expect(fetchMock.mock.calls[0][1].headers).toHaveProperty(
      'X-XSRF-TOKEN',
      'the-one-we-want',
    );

    document.cookie = 'other=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
    document.cookie = 'XSRF-TOKEN-DECOY=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
  });

  it('lets an explicit per-call header win', async () => {
    fetchMock.mockImplementation(async () => ok({ data: {} }));
    setCookie('from-the-cookie');

    await http({
      url: '/auth/refresh',
      method: 'POST',
      headers: { 'X-XSRF-TOKEN': 'explicitly-set' },
    });

    expect(fetchMock.mock.calls[0][1].headers).toHaveProperty(
      'X-XSRF-TOKEN',
      'explicitly-set',
    );
  });
});
