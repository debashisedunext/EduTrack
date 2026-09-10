import * as React from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import {
  createObClientAccount,
  getObClientAccount,
  resetObClientAccountPassword,
  setObClientAccountStatus,
} from '@/api/generated/onboarding/onboarding';
import type { ObClientAccount } from '@/api/generated/model';
import { ApiError } from '@/api/http';
import { Button } from '@/components/ui/button';

/**
 * B-126 — the client-account panel for OB-05 and OB-08.
 *
 * ## A component rather than a page, because OB-05 is not built yet
 *
 * The client detail screen is C-110's and is not on `develop`. This is the
 * panel that mounts on it, finished and tested on its own — the same call
 * B-112 made for the notification bell, which shipped complete while the shell
 * that mounts it was somebody else's task. Building a page here to host it
 * would be building the second half of a screen this task does not own.
 *
 * ## Three actions, and the panel says which one it is offering
 *
 * A client either has a login or does not, and the two states share almost no
 * controls: no login offers Create, a login offers Reset and Enable/Disable.
 * Rendering all three at once with two of them disabled would teach the reader
 * that the panel is broken, which is `ObDashboardCardTile`'s argument about
 * dead controls, applied here.
 *
 * ## Nothing on this panel is a credential — except under one switch
 *
 * The response carries no password, no hash and no link, so there is nothing
 * here to copy and paste. That is deliberate on the server (see
 * `ObClientAccountResponse`), and the screen states what actually happened —
 * "a link has been emailed to …" — rather than implying the operator now holds
 * something they must pass on.
 *
 * The exception is `devPassword`, which is null unless the server sets
 * `edutrack.portal.dev-credentials.enabled` — and `PortalDevCredentialConfig`
 * refuses to start with that outside a development profile. It exists because
 * a demo database's mail transport is `logging`, so the link the paragraph
 * above depends on arrives nowhere and the portal is unreachable.
 *
 * When it is present the panel says so in as many words, and shows it in an
 * amber block rather than beside the username: a working client password
 * rendered as ordinary panel content is one nobody reads twice.
 */
export function ObClientAccountPanel({ obClientId }: { obClientId: number }) {
  const queryClient = useQueryClient();
  const queryKey = ['obClientAccount', obClientId];
  const [error, setError] = React.useState<string | null>(null);
  const [issued, setIssued] = React.useState<string | null>(null);

  const account = useQuery({
    queryKey,
    queryFn: () => getObClientAccount(obClientId),
    // A client with no login answers 404, which is a normal state and not a
    // failure to retry. Retrying it would delay the empty state that offers to
    // create one.
    retry: false,
  });

  function onSettled(message: string | null) {
    return {
      onSuccess: (data: { data: ObClientAccount }) => {
        queryClient.setQueryData(queryKey, data);
        setError(null);
        setIssued(message);
      },
      onError: (caught: unknown) => {
        setIssued(null);
        setError(messageFor(caught));
      },
    };
  }

  const create = useMutation({
    mutationFn: () => createObClientAccount(obClientId),
    ...onSettled('A credential link has been emailed to the primary SPOC.'),
  });

  const reset = useMutation({
    mutationFn: () => resetObClientAccountPassword(obClientId),
    ...onSettled('A fresh link has been emailed. Any earlier link no longer works.'),
  });

  /*
    The development switch's password, held for as long as the panel stays
    mounted and no longer. It is on the create/reset response and on no read, so
    navigating away is the same as never having seen it — which is the property
    that keeps this from becoming a credential the panel stores.

    Read from the mutation rather than from `account.data`, deliberately:
    `onSettled` writes the whole response into the query cache, so a refetch of
    `getObClientAccount` would replace it with the same account minus the
    password. Sourcing it here means the display cannot outlive the action that
    produced it.
  */
  const devPassword = create.data?.data.devPassword ?? reset.data?.data.devPassword ?? null;

  const setStatus = useMutation({
    mutationFn: (isActive: boolean) => setObClientAccountStatus(obClientId, { isActive }),
    ...onSettled(null),
  });

  const busy = create.isPending || reset.isPending || setStatus.isPending;
  const data = account.data?.data;
  const missing = account.isError && account.error instanceof ApiError
    && account.error.status === 404;

  /*
    Styled from the design tokens rather than raw palette classes, and matching
    `ObClientInfoCard` exactly — same surface, radius, border and shadow. The
    two sit side by side on OB-05, and a panel that is a different shade of
    white with a different corner radius reads as a different kind of thing
    rather than as the card beside it.
  */
  return (
    <section
      aria-labelledby="ob-client-account"
      className="rounded-card border border-border bg-surface p-5 shadow-sm"
    >
      <h2 id="ob-client-account" className="m-0 text-base font-semibold text-content">
        Client portal login
      </h2>

      {error ? (
        <p role="alert" className="mt-3 rounded-md bg-red-50 p-3 text-sm text-red-800">{error}</p>
      ) : null}
      {issued ? (
        <p role="status" className="mt-3 rounded-md bg-emerald-50 p-3 text-sm text-emerald-900">
          {issued}
        </p>
      ) : null}

      {/*
        Development builds only — `devPassword` is null on every deployment that
        has not set `edutrack.portal.dev-credentials.enabled`, which the server
        refuses to start with outside a development profile.

        Stated as loudly as it is useful. A panel that showed a working client
        password with no explanation would read as a feature, and the next
        person to see it would reasonably assume production does this too.
      */}
      {devPassword ? (
        <div
          role="status"
          className="mt-3 rounded-md border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900"
        >
          <p className="m-0 font-semibold">Development build — sign-in details</p>
          <dl className="mt-2 grid grid-cols-[auto,1fr] gap-x-4 gap-y-1">
            <dt>Username</dt>
            <dd className="font-mono">{create.data?.data.username ?? reset.data?.data.username}</dd>
            <dt>Password</dt>
            <dd className="font-mono">{devPassword}</dd>
          </dl>
          <p className="m-0 mt-2 text-xs">
            Shown because this deployment sets{' '}
            <code>edutrack.portal.dev-credentials</code>. It is not shown again once you
            leave this page, and no production build ever shows it.
          </p>
        </div>
      ) : null}

      {account.isPending ? <p className="mt-3 text-sm text-content-muted">Loading…</p> : null}

      {missing && !data ? (
        <>
          <p className="mt-3 text-sm text-content-muted">
            This client has no portal login. Creating one emails a single-use link to the
            primary SPOC.
          </p>
          <Button className="mt-4" onClick={() => create.mutate()} disabled={busy}>
            {create.isPending ? 'Creating…' : 'Create portal login'}
          </Button>
        </>
      ) : null}

      {data ? (
        <>
          <dl className="mt-3 grid grid-cols-[auto,1fr] gap-x-4 gap-y-1 text-sm">
            <dt className="text-content-muted">Username</dt>
            <dd className="font-mono text-content">{data.username}</dd>
            <dt className="text-content-muted">Issued to</dt>
            <dd className="text-content">{data.displayName} · {data.email}</dd>
            <dt className="text-content-muted">Status</dt>
            <dd className="text-content">{data.isActive ? 'Active' : 'Disabled'}</dd>
            <dt className="text-content-muted">Last signed in</dt>
            {/*
              Never, rather than a blank. "This client has never used their
              login" is the answer support is actually looking for, and an
              empty cell reads as missing data.
            */}
            <dd className="text-content">
              {data.lastLoginAt ? new Date(data.lastLoginAt).toLocaleString() : 'Never'}
            </dd>
            {data.lockedUntil ? (
              <>
                <dt className="text-content-muted">Locked until</dt>
                <dd className="text-content">{new Date(data.lockedUntil).toLocaleString()}</dd>
              </>
            ) : null}
          </dl>

          {data.mustChangePassword ? (
            <p className="mt-3 text-caption text-content-muted">
              The client has not set their own password yet.
            </p>
          ) : null}

          <div className="mt-4 flex gap-2">
            <Button variant="secondary" onClick={() => reset.mutate()} disabled={busy}>
              {reset.isPending ? 'Sending…' : 'Email a new link'}
            </Button>
            <Button
              variant="secondary"
              onClick={() => setStatus.mutate(!data.isActive)}
              disabled={busy}
            >
              {data.isActive ? 'Disable login' : 'Enable login'}
            </Button>
          </div>

          {data.isActive ? (
            <p className="mt-2 text-caption text-content-muted">
              Disabling also invalidates any link already emailed.
            </p>
          ) : null}
        </>
      ) : null}
    </section>
  );
}

/**
 * The two refusals an operator can act on get their own wording; everything
 * else gets one message.
 *
 * `ob-client-no-primary-contact` is the one worth spelling out — the fix is on
 * the contacts panel a few inches up the same screen, and a generic "could not
 * create" would send somebody looking in the wrong place.
 */
function messageFor(caught: unknown): string {
  if (caught instanceof ApiError) {
    if (caught.problem.type?.endsWith('ob-client-no-primary-contact')) {
      return 'This client has no active primary SPOC, so there is nobody to send credentials to. '
        + 'Add a primary contact first.';
    }
    if (caught.problem.type?.endsWith('ob-client-account-exists')) {
      return 'This client already has a portal login. Use "Email a new link" instead.';
    }
  }
  return 'That did not work. Please try again, or ask an administrator.';
}

export default ObClientAccountPanel;
