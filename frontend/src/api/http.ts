/**
 * The single fetch used by every generated API call.
 *
 * Orval generates typed functions and TanStack Query hooks; this is what they
 * actually call. Everything that must be true of *every* request lives here —
 * base URL, auth, error shape — so no feature has to remember it.
 *
 * Owned by Stream D (D-003). If you need behaviour on every request, add it
 * here rather than wrapping calls at the feature level.
 */

/** RFC 9457 problem document. See contracts/CONVENTIONS.md §3. */
export interface Problem {
  type: string;
  title: string;
  status: number;
  detail?: string;
  instance?: string;
  traceId?: string;
  /** Field-keyed messages on a 400. */
  errors?: Record<string, string[]>;
}

/**
 * Thrown for any non-2xx response.
 *
 * Branch on `problem.type`, which is a stable URI. **Never match on `title` or
 * `detail`** — they are written for humans and may be reworded without notice.
 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly problem: Problem,
    readonly response: Response,
  ) {
    super(problem.title || `HTTP ${status}`);
    this.name = 'ApiError';
  }

  /** Field-level messages from a 400, for React Hook Form's `setError`. */
  get fieldErrors(): Record<string, string[]> {
    return this.problem.errors ?? {};
  }

  is(type: string): boolean {
    return this.problem.type?.endsWith(type) ?? false;
  }
}

/**
 * Exported so a feature that must read a *response header* can build the same
 * URL rather than duplicating this default.
 *
 * `http()` returns a parsed body and drops the response, which is right for the
 * generated client — but `ETag` is part of the contract for the working-week
 * resource (B-023), and an optimistic-concurrency guard cannot work without it.
 * Duplicating `'/api/v1'` in a feature would mean `VITE_API_BASE` silently
 * applying to some calls and not others.
 */
export const BASE = import.meta.env.VITE_API_BASE ?? '/api/v1';

let accessToken: string | null = null;

/** Called by the auth store on login, refresh and logout. */
export function setAccessToken(token: string | null): void {
  accessToken = token;
}

export function getAccessToken(): string | null {
  return accessToken;
}

/**
 * Generate an idempotency key for a create.
 *
 * **Generate this once, before the mutation — not inside it.** TanStack Query
 * re-invokes the mutation function on retry, so a key created per attempt
 * changes every time and defends against nothing. The whole point is that a
 * retried request after a timeout is recognised as the same request.
 *
 *   const key = newIdempotencyKey();
 *   mutate({ data: body, headers: { 'Idempotency-Key': key } });
 */
export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}

export interface RequestConfig {
  url: string;
  /** Orval emits these uppercase. */
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  params?: Record<string, unknown>;
  data?: unknown;
  headers?: Record<string, string>;
  signal?: AbortSignal;
  responseType?: string;
}

function query(params?: Record<string, unknown>): string {
  if (!params) return '';
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    // Arrays are `form` style, comma-joined — matches `explode: false` in the spec.
    search.append(key, Array.isArray(value) ? value.join(',') : String(value));
  }
  const qs = search.toString();
  return qs ? `?${qs}` : '';
}

export async function http<T>({
  url,
  method,
  params,
  data,
  headers,
  signal,
  responseType,
}: RequestConfig): Promise<T> {
  const isForm = data instanceof FormData;
  const response = await fetch(`${BASE}${url}${query(params)}`, {
    method,
    signal,
    // The refresh token is an HttpOnly cookie, so it has to ride along.
    credentials: 'include',
    headers: {
      ...(isForm ? {} : data !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
      ...headers,
    },
    body: isForm ? data : data !== undefined ? JSON.stringify(data) : undefined,
  });

  // 204, and 304 from a conditional GET, carry no body.
  if (response.status === 204 || response.status === 304) {
    return undefined as T;
  }

  if (!response.ok) {
    throw new ApiError(response.status, await readProblem(response), response);
  }

  if (responseType === 'blob') return (await response.blob()) as T;
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/**
 * Never let error handling throw.
 *
 * A gateway timeout or a proxy returning HTML would otherwise fail inside the
 * JSON parse and surface as "Unexpected token <", which tells the user nothing
 * and sends the developer to the wrong layer entirely.
 */
async function readProblem(response: Response): Promise<Problem> {
  try {
    const body = (await response.json()) as Partial<Problem>;
    if (body && typeof body === 'object' && body.title) {
      return { type: 'about:blank', status: response.status, ...body } as Problem;
    }
  } catch {
    /* not JSON — fall through */
  }
  return {
    type: 'about:blank',
    title: response.statusText || `HTTP ${response.status}`,
    status: response.status,
  };
}

/** The mutator orval is configured to call. */
export default http;
