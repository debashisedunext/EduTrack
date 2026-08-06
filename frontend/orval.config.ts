import { defineConfig } from 'orval';

/**
 * OpenAPI → TypeScript client + Zod schemas. D-003.
 *
 * Source of truth today is `contracts/openapi.yaml`, the reviewed contract.
 * Once controllers exist, springdoc emits the same document from the Java DTOs
 * and this points at that instead — at which point **Bean Validation
 * annotations become the single source of truth for validation rules** and the
 * frontend stops having any hand-written ones (PLAN.md §2.2, deviation D-4).
 *
 * `frontend/src/api/generated/` is generated. Never hand-edit it; CI regenerates
 * and fails the build if the committed output is stale (D-005).
 */
export default defineConfig({
  client: {
    input: {
      target: '../contracts/openapi.yaml',
    },
    output: {
      target: './src/api/generated/edutrack.ts',
      schemas: './src/api/generated/model',
      client: 'react-query',
      mode: 'tags-split',
      clean: true,
      prettier: false,
      override: {
        // The default (axios-shaped) mutator contract, deliberately — NOT
        // `httpClient: 'fetch'`. That variant returns a `{ data, status }`
        // union and never throws, so every query would resolve successfully
        // and React Query would never enter an error state: no retry, no
        // `isError`, no error boundary. This contract gives the success type
        // back and throws ApiError on anything else. No axios dependency is
        // pulled in — the mutator is ours.
        mutator: {
          path: './src/api/http.ts',
          name: 'http',
        },
        query: {
          useQuery: true,
          // `useInfinite` is off deliberately. It generates an infinite hook for
          // every GET — including the twenty that are single resources or
          // deliberately unpaginated — and orval 7.21's output does not typecheck
          // against @tanstack/react-query 5.101 (UseInfiniteQueryOptions takes at
          // most 5 generics; it passes 6). 144 type errors for hooks nobody asked
          // for.
          //
          // The handful of lists that genuinely need infinite scroll — the ticket
          // list, history, chat — compose useInfiniteQuery over the generated
          // fetcher directly, paging on `meta.nextCursor`. See src/api/README.md.
          useInfinite: false,
        },
      },
    },
  },

  /**
   * Zod schemas for React Hook Form, generated from the same document.
   *
   * These carry the `minLength`, `pattern` and `required` that springdoc will
   * emit from the Java DTOs' Bean Validation annotations. That is the whole
   * mitigation for losing shared Zod schemas when the backend stopped being
   * TypeScript: authored once in Java, executed on both sides.
   */
  zod: {
    input: {
      target: '../contracts/openapi.yaml',
    },
    output: {
      target: './src/api/generated/zod',
      client: 'zod',
      mode: 'tags-split',
      fileExtension: '.zod.ts',
      clean: true,
      prettier: false,
    },
  },
});
