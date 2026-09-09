/**
 * B-109 · the `type` URIs `createObClient` can answer with, as constants.
 *
 * `ApiError.is()` matches on the suffix of `problem.type` — see `api/http.ts`.
 * `problemTypes.ts` in `features/auth/` is the precedent: one file naming
 * every outcome a screen can hit, rather than a string literal repeated at
 * each `catch`.
 */

/** Final. `errors.pan` names the field; `existingClientName` is present only when the caller may see it. */
export const OB_CLIENT_PAN_DUPLICATE = 'ob-client-pan-duplicate';

/** Forceable with `acknowledgeSimilarNames`. `candidates` and `hiddenCandidateCount` ride along. */
export const OB_CLIENT_NAME_SIMILAR = 'ob-client-name-similar';

/** A selected product has no published journey template. `productIds` names which. */
export const OB_PRODUCT_NO_TEMPLATE = 'ob-product-no-template';

/** Nothing published on OB-14 — B-125's prerequisites master. Not fixable from this form. */
export const OB_CLIENT_NO_PREREQ_MASTER = 'ob-client-no-prereq-master';

/** `createPortalLogin: true` ahead of B-126. Refused before anything is written. */
export const OB_CLIENT_PORTAL_LOGIN_UNAVAILABLE = 'ob-client-portal-login-unavailable';
