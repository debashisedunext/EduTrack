import * as React from 'react';

/**
 * The chrome every authentication screen shares — S-01's "centred card on a soft
 * indigo gradient, product logo, no dark surfaces".
 *
 * S-01 through S-04 are one flow a user walks through in a single sitting, often
 * with a redirect between two of them. Giving each its own layout makes the
 * background jump on that redirect, which reads as having been thrown out to a
 * different site at the exact moment the user is being asked to trust the page
 * with a password.
 *
 * ## The gradient introduces no new colour
 *
 * `tokens.css` is Stream C's and frozen after Sprint 0 — other streams request a
 * token rather than adding one. There is no gradient token, so this composes one
 * from two that already exist: `--primary-soft` (#EEF2FF) into `--bg-app`
 * (#F7F8FC). Both are blueprint §12.1 values, the ramp is the indigo tint S-01
 * asks for, and nothing here needs Divyansh's sign-off because no value is
 * invented. If the design later wants a distinct gradient stop, that is a token
 * request, not a hex in this file.
 */
export function AuthCard({
  title,
  description,
  children,
  footer,
}: {
  title: string;
  /** One or two lines under the heading. Optional — S-01 itself has none. */
  description?: React.ReactNode;
  children: React.ReactNode;
  /** Secondary navigation, below the card. Kept outside so it is not read as part of the form. */
  footer?: React.ReactNode;
}) {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-gradient-to-b from-primary-soft to-app px-4 py-10">
      {/*
        `main` rather than a bare div: on these four screens the card is the
        entire page, so it is the main landmark, and a screen-reader user's
        "skip to main content" has to land on the form rather than on the logo.
      */}
      <main className="w-full max-w-[400px]">
        <div className="mb-6 flex flex-col items-center gap-3">
          <ProductLogo />
          <p className="text-caption text-content-muted">Task &amp; ticket management</p>
        </div>

        <div className="rounded-card border border-border bg-surface p-6 shadow-modal">
          <h1 className="text-h2 text-content">{title}</h1>
          {description ? (
            <p className="mt-1 text-sm text-content-muted">{description}</p>
          ) : null}
          <div className="mt-5">{children}</div>
        </div>

        {footer ? <div className="mt-4 text-center text-sm">{footer}</div> : null}
      </main>
    </div>
  );
}

/**
 * A wordmark, not an image.
 *
 * The brand asset does not exist yet and a placeholder `<img>` would 404 on the
 * one screen every user sees first. Text scales, prints, needs no alt attribute
 * to be maintained, and is replaced by Divyansh's asset in one place when there
 * is one.
 */
function ProductLogo() {
  return (
    <div className="flex items-center gap-2">
      <span
        aria-hidden="true"
        className="flex h-9 w-9 items-center justify-center rounded-control bg-primary text-base font-semibold text-white"
      >
        E
      </span>
      <span className="text-h1 font-semibold tracking-tight text-content">EduTrack</span>
    </div>
  );
}
