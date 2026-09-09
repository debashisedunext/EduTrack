import * as React from 'react';

import {
  acceptObSignoff,
  requestObSignoffOtp,
  verifyObSignoffOtp,
} from '@/api/generated/onboarding/onboarding';
import type {
  ObSignoffAcceptResult,
  ObSignoffSession,
} from '@/api/generated/model';
import { ApiError } from '@/api/http';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';

/**
 * OB-09 — the public sign-off page. B-115.
 *
 * ## Shell-less, and more than cosmetically
 *
 * The task line is "shell-less, no navigation, nothing that hints at the rest
 * of the application", and each clause is doing work. The reader is a
 * customer's SPOC who has no account here and never will: a sidebar would be a
 * menu of routes that all 401, a top bar would query `/me` for a principal that
 * does not exist, and a logo linking home would invite someone to go looking.
 * The route is `/signoff` rather than `/onboarding/signoff` for the same reason
 * — the path itself is the first thing that hints at a module.
 *
 * ## Three states, because the API has three steps
 *
 * The link proves possession of a mailbox; the OTP proves who is holding it.
 * A-121's contract note puts it as "a link on its own proves possession of an
 * email; it does not prove identity". So: request a code, verify it for a
 * session, then act with that session. Nothing about the sign-off — not the
 * client's name, not the service, not the checklist — is fetched before the
 * code is proved, because `verifyObSignoffOtp` is the call that returns it.
 *
 * ## The token leaves the address bar as soon as it is read
 *
 * The contract states the concern once for the whole surface: a URL carrying a
 * credential "lands in browser history, in the `Referer` of every asset the
 * page loads, and in the access log of everything in between". The API side is
 * answered by taking the token in a POST body. The half the API cannot answer
 * is the address bar of the browser that followed the link, so this page reads
 * the token once into memory and replaces the URL — that removes it from the
 * back/forward entry and from the `Referer` of everything loaded afterwards. It
 * cannot un-log the request that delivered the page, and does not pretend to.
 *
 * ## Every failure reads the same, because every failure answers the same
 *
 * `ObSignoffTokens` returns nothing for an unknown, expired, cancelled and
 * already-decided token alike, and `PublicSignoffAccess` renders one 401 for
 * all of them. A screen that guessed which — "this link has expired" — would
 * rebuild the enumeration oracle the server refuses to be, in the one place
 * nobody would think to look for it. So there is one message, and it tells the
 * reader the thing that is actually actionable: ask the person who sent it.
 */
export function PublicSignoffPage() {
  const [token] = React.useState(readTokenFromUrl);
  const [session, setSession] = React.useState<ObSignoffSession | null>(null);
  const [result, setResult] = React.useState<ObSignoffAcceptResult | null>(null);

  React.useEffect(() => {
    stripTokenFromUrl();
  }, []);

  if (!token) {
    return (
      <SignoffShell>
        <Notice
          title="This link is not complete"
          body="Open the sign-off link from your email exactly as it was sent — copying part
                of it leaves out the piece that identifies your approval."
        />
      </SignoffShell>
    );
  }

  if (result) {
    return (
      <SignoffShell>
        <AcceptedPanel result={result} />
      </SignoffShell>
    );
  }

  if (!session) {
    return (
      <SignoffShell>
        <IdentifyPanel token={token} onVerified={setSession} />
      </SignoffShell>
    );
  }

  return (
    <SignoffShell>
      <ReviewPanel session={session} onAccepted={setResult} />
    </SignoffShell>
  );
}

/* ── step one: prove who is holding the link ───────────────────────────── */

/**
 * Request a code, then exchange it for a session.
 *
 * Both halves live in one component because they are one conversation with one
 * mailbox, and splitting them would put the "resend" affordance somewhere that
 * cannot see whether a code has already been sent.
 */
function IdentifyPanel({
  token,
  onVerified,
}: {
  token: string;
  onVerified: (session: ObSignoffSession) => void;
}) {
  const [sent, setSent] = React.useState(false);
  const [otp, setOtp] = React.useState('');
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);

  async function send() {
    setBusy(true);
    setError(null);
    try {
      await requestObSignoffOtp({ token });
      // 202 whatever happens — the contract calls this "deliberately
      // indistinguishable from the successful case". There is nothing to
      // branch on, and inventing a branch here would undo that.
      setSent(true);
    } catch (caught) {
      setError(messageFor(caught));
    } finally {
      setBusy(false);
    }
  }

  async function verify(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const response = await verifyObSignoffOtp({ token, otp });
      onVerified(response.data);
    } catch (caught) {
      setError(messageFor(caught));
    } finally {
      setBusy(false);
    }
  }

  if (!sent) {
    return (
      <section aria-labelledby="signoff-identify">
        <h1 id="signoff-identify" className="text-xl font-semibold text-slate-900">
          Confirm it is you
        </h1>
        <p className="mt-2 text-sm text-slate-600">
          We will email a six-digit code to the address this link was sent to.
        </p>
        {error ? <Alert>{error}</Alert> : null}
        <Button className="mt-6 w-full" onClick={send} disabled={busy}>
          {busy ? 'Sending…' : 'Email me a code'}
        </Button>
      </section>
    );
  }

  return (
    <form onSubmit={verify} aria-labelledby="signoff-otp">
      <h1 id="signoff-otp" className="text-xl font-semibold text-slate-900">
        Enter your code
      </h1>
      <p className="mt-2 text-sm text-slate-600">
        We have emailed a six-digit code to the address this link was sent to. It is
        good for a few minutes.
      </p>
      {error ? <Alert>{error}</Alert> : null}
      <label htmlFor="signoff-otp-input" className="mt-6 block text-sm font-medium text-slate-700">
        Six-digit code
      </label>
      <Input
        id="signoff-otp-input"
        className="mt-1 tracking-[0.4em]"
        value={otp}
        onChange={(event) => setOtp(event.target.value.replace(/\D/g, '').slice(0, 6))}
        inputMode="numeric"
        autoComplete="one-time-code"
        // Six digits, matched to the server's own @Pattern. Rejected here means
        // no attempt is spent from the persisted budget — the lockout is for
        // wrong guesses, and a blank field is not one.
        pattern="[0-9]{6}"
        maxLength={6}
        required
        autoFocus
      />
      <Button type="submit" className="mt-6 w-full" disabled={busy || otp.length !== 6}>
        {busy ? 'Checking…' : 'Continue'}
      </Button>
      <button
        type="button"
        onClick={send}
        disabled={busy}
        className="mt-4 w-full text-sm text-slate-500 underline underline-offset-2 disabled:opacity-50"
      >
        Send another code
      </button>
    </form>
  );
}

/* ── step two: read what is being accepted, and accept it ──────────────── */

function ReviewPanel({
  session,
  onAccepted,
}: {
  session: ObSignoffSession;
  onAccepted: (result: ObSignoffAcceptResult) => void;
}) {
  const [name, setName] = React.useState('');
  const [note, setNote] = React.useState('');
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);

  async function accept(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const response = await acceptObSignoff({
        sessionToken: session.sessionToken,
        acceptedName: name.trim(),
        note: note.trim() || null,
      });
      onAccepted(response.data);
    } catch (caught) {
      setError(messageFor(caught));
    } finally {
      setBusy(false);
    }
  }

  // `checklist` is optional on the generated type — a GO_LIVE session omits it
  // rather than sending an empty array.
  const checklist = session.checklist ?? [];

  const heading =
    session.kind === 'GO_LIVE'
      ? 'Confirm go-live'
      : session.stepTitle ?? 'Confirm this service';

  return (
    <form onSubmit={accept} aria-labelledby="signoff-review">
      <p className="text-sm font-medium uppercase tracking-wide text-slate-500">
        {session.obClientName}
        {session.productName ? ` · ${session.productName}` : ''}
      </p>
      <h1 id="signoff-review" className="mt-1 text-xl font-semibold text-slate-900">
        {heading}
      </h1>

      {checklist.length > 0 ? (
        <>
          <h2 className="mt-6 text-sm font-medium text-slate-700">What you are accepting</h2>
          <ul className="mt-2 space-y-2">
            {checklist.map((item) => (
              <li key={item.id} className="flex gap-2 text-sm text-slate-700">
                {/*
                  `isDone` means ANSWERED, not answered True — C-111's
                  distinction, restated on A-121's DTO. So the mark is
                  "recorded" rather than a tick, which would tell the reader
                  something the field does not say.
                */}
                <span aria-hidden="true" className={item.isDone ? 'text-slate-900' : 'text-slate-300'}>
                  {item.isDone ? '●' : '○'}
                </span>
                <span>
                  {item.label}
                  <span className="sr-only">{item.isDone ? ' — recorded' : ' — not yet recorded'}</span>
                </span>
              </li>
            ))}
          </ul>
        </>
      ) : null}

      {error ? <Alert>{error}</Alert> : null}

      <label htmlFor="signoff-name" className="mt-6 block text-sm font-medium text-slate-700">
        Your full name
      </label>
      <p className="text-xs text-slate-500">
        Typing your name records this acceptance against it, with the date and time.
      </p>
      <Input
        id="signoff-name"
        className="mt-1"
        value={name}
        onChange={(event) => setName(event.target.value)}
        maxLength={160}
        autoComplete="name"
        required
      />

      <label htmlFor="signoff-note" className="mt-4 block text-sm font-medium text-slate-700">
        Anything to add <span className="font-normal text-slate-500">(optional)</span>
      </label>
      <textarea
        id="signoff-note"
        className="mt-1 w-full rounded-md border border-slate-300 p-2 text-sm"
        rows={3}
        value={note}
        onChange={(event) => setNote(event.target.value)}
        maxLength={2000}
      />

      <Button type="submit" className="mt-6 w-full" disabled={busy || name.trim().length === 0}>
        {busy ? 'Recording…' : 'Accept'}
      </Button>
    </form>
  );
}

/* ── step three: what happened ─────────────────────────────────────────── */

/**
 * The acceptance is recorded either way, and the page says so either way.
 *
 * `stepCompleted: false` is a normal outcome, not an error — our completion
 * gate refused, which is our own record being incomplete rather than anything
 * the client did. The contract's wording for this reader is "your acceptance is
 * recorded; we are finishing our side", and `gateFailures` is deliberately not
 * rendered: those codes tell the owner what to attach, and mean nothing to the
 * person reading this.
 */
function AcceptedPanel({ result }: { result: ObSignoffAcceptResult }) {
  return (
    <section aria-labelledby="signoff-done" role="status">
      <h1 id="signoff-done" className="text-xl font-semibold text-slate-900">
        Thank you — your acceptance is recorded
      </h1>
      <p className="mt-2 text-sm text-slate-600">
        {result.stepCompleted
          ? 'This is now confirmed complete. There is nothing further for you to do.'
          : 'We are finishing our side and will be in touch. There is nothing further for you to do.'}
      </p>
      {result.signoff.signedAt ? (
        <p className="mt-4 text-xs text-slate-500">
          Recorded {new Date(result.signoff.signedAt).toLocaleString()}.
        </p>
      ) : null}
    </section>
  );
}

/* ── chrome, such as it is ─────────────────────────────────────────────── */

/**
 * The whole page frame: a card on a plain ground, and nothing else.
 *
 * No logo link, no footer links, no support address that is really a login
 * page. Whatever is put here is reachable by someone we know nothing about
 * beyond their having been sent one email.
 */
function SignoffShell({ children }: { children: React.ReactNode }) {
  return (
    <main className="flex min-h-screen items-center justify-center bg-slate-50 p-4">
      <div className="w-full max-w-md rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
        {children}
      </div>
    </main>
  );
}

function Alert({ children }: { children: React.ReactNode }) {
  return (
    <p role="alert" className="mt-4 rounded-md bg-red-50 p-3 text-sm text-red-800">
      {children}
    </p>
  );
}

function Notice({ title, body }: { title: string; body: string }) {
  return (
    <section aria-labelledby="signoff-notice">
      <h1 id="signoff-notice" className="text-xl font-semibold text-slate-900">
        {title}
      </h1>
      <p className="mt-2 text-sm text-slate-600">{body}</p>
    </section>
  );
}

/* ── helpers ───────────────────────────────────────────────────────────── */

function readTokenFromUrl(): string | null {
  if (typeof window === 'undefined') {
    return null;
  }
  const token = new URLSearchParams(window.location.search).get('token');
  return token && token.trim().length > 0 ? token.trim() : null;
}

/**
 * Replace rather than push, so the token-bearing URL is not left behind in the
 * history entry the back button returns to.
 */
function stripTokenFromUrl(): void {
  if (typeof window === 'undefined' || !window.history?.replaceState) {
    return;
  }
  const url = new URL(window.location.href);
  if (!url.searchParams.has('token')) {
    return;
  }
  url.searchParams.delete('token');
  window.history.replaceState(null, '', `${url.pathname}${url.search}${url.hash}`);
}

/**
 * One message for every refusal the surface makes, on purpose.
 *
 * 429 is the exception and it is not a leak: being told to slow down says
 * nothing about whether a token is real — `PublicSignoffAccess` spends the
 * budget *before* it looks the token up, precisely so that it cannot.
 */
function messageFor(caught: unknown): string {
  if (caught instanceof ApiError && caught.status === 429) {
    return 'Too many attempts. Please wait a few minutes and try again.';
  }
  return 'We could not continue with this link. Please ask whoever sent it to send a new one.';
}

export default PublicSignoffPage;
