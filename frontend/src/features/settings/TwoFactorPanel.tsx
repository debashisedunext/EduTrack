import * as React from 'react';
import {
  beginTwoFactorEnrolment,
  confirmTwoFactorEnrolment,
  disableTwoFactor,
} from '@/api/generated/auth/auth';
import type { TwoFactorSetup } from '@/api/generated/model/twoFactorSetup';
import { ApiError } from '@/api/http';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { toast } from '@/components/ui/use-toast';
import { AuthAlert, AuthField } from '@/features/auth/AuthField';
import {
  INVALID_CREDENTIALS,
  INVALID_TOTP_CODE,
  TWO_FACTOR_ALREADY_ENABLED,
  TWO_FACTOR_NOT_ENROLLED,
  VALIDATION,
} from '@/features/auth/problemTypes';
import { SettingsSection } from './SettingsTabs';

/**
 * A-029's second factor, given the screen it never had.
 *
 * `POST /me/2fa/setup`, `/confirm` and `/disable` have been implemented and
 * integration-tested since A-029; outside the login challenge there has been no
 * UI for any of them, so 2FA has been switchable only with a REST client. This
 * panel is that UI.
 *
 * ## Why it never claims 2FA is off
 *
 * **`GET /me` does not report whether the second factor is on.** `Me` carries
 * id, name, role, username, email, permissions, projects, reportees and
 * timezone — `AuthUserRow.totpEnabled` exists and the login path consults it,
 * but it is not projected onto `Me`, so this screen has no read for it.
 *
 * So the resting state offers both doors rather than asserting a state it
 * cannot know: "Set up" and, quieter, "already using it — turn it off". A panel
 * that opened saying *"Two-factor authentication is off"* would be a security
 * claim made on no evidence, and would be wrong for exactly the users who most
 * need it to be right.
 *
 * The truth is still reachable, because the API refuses honestly: `setup`
 * answers **409 `two-factor-already-enabled`** when it is on, and that response
 * flips this panel to the enabled view. It costs one press and it cannot
 * mislead. Pressing it is safe either way — `setup` has no effect on an enabled
 * account (it is refused) and only replaces an *unconfirmed* secret on a
 * disabled one, so nobody is locked out by finding out.
 *
 * **The real fix is a contract change, not a workaround here.** Adding
 * `twoFactorEnabled` to `MeResponse` would let this panel open in the right
 * state, and `contracts/openapi.yaml` is Stream D's file — so it is raised as
 * its own task rather than smuggled into a frontend pull request. When that
 * field lands, the `'unknown'` stage below is deleted and nothing else changes.
 *
 * ## Recovery codes are shown once and the screen behaves like it
 *
 * `confirm` returns them, they are stored hashed, and they are never
 * retrievable again. So the acknowledgement is a checkbox rather than a
 * dismiss — there is no way to reopen this step, and a panel that let them
 * scroll away silently would be handing someone a locked account on the day
 * they lose a phone.
 */
type Stage =
  /** We have not asked, and cannot know. See the note above. */
  | { kind: 'unknown' }
  | { kind: 'enrolling'; setup: TwoFactorSetup }
  | { kind: 'codes'; codes: string[] }
  | { kind: 'enabled' }
  | { kind: 'disabling' };

export function TwoFactorPanel() {
  const [stage, setStage] = React.useState<Stage>({ kind: 'unknown' });
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string | null>(null);

  async function begin() {
    setError(null);
    setBusy(true);
    try {
      const response = await beginTwoFactorEnrolment();
      setStage({ kind: 'enrolling', setup: response.data });
    } catch (caught) {
      if (caught instanceof ApiError && caught.is(TWO_FACTOR_ALREADY_ENABLED)) {
        // Not an error the user caused — it is the answer to the question they
        // asked by pressing the button, so it is reported as state and not as a
        // failure in red.
        setStage({ kind: 'enabled' });
        return;
      }
      setError('Could not start setup. Try again.');
    } finally {
      setBusy(false);
    }
  }

  async function confirm(code: string) {
    setError(null);
    setBusy(true);
    try {
      const response = await confirmTwoFactorEnrolment({ code });
      setStage({ kind: 'codes', codes: response.data.recoveryCodes });
    } catch (caught) {
      if (caught instanceof ApiError && caught.is(TWO_FACTOR_ALREADY_ENABLED)) {
        setStage({ kind: 'enabled' });
        return;
      }
      setError(confirmMessage(caught));
    } finally {
      setBusy(false);
    }
  }

  async function turnOff(password: string) {
    setError(null);
    setBusy(true);
    try {
      await disableTwoFactor({ password });
      toast({ title: 'Two-factor authentication turned off', variant: 'success' });
      setStage({ kind: 'unknown' });
    } catch (caught) {
      if (caught instanceof ApiError && caught.is(TWO_FACTOR_NOT_ENROLLED)) {
        // It was already off. Saying so beats an error about a state the user
        // is already in.
        setError('Two-factor authentication is not currently on for your account.');
        setStage({ kind: 'unknown' });
        return;
      }
      setError(
        caught instanceof ApiError && caught.is(INVALID_CREDENTIALS)
          ? 'That password is not correct.'
          : 'Could not turn off two-factor authentication. Try again.',
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <SettingsSection
      title="Two-factor authentication"
      description="An authenticator app generates a six-digit code you enter after your password, so a stolen password is not enough on its own."
    >
      {error ? <AuthAlert>{error}</AuthAlert> : null}

      {stage.kind === 'unknown' ? (
        <div className="mt-4 flex flex-wrap items-center gap-3">
          <Button type="button" onClick={begin} disabled={busy}>
            {busy ? 'Starting…' : 'Set up two-factor authentication'}
          </Button>
          <Button
            type="button"
            variant="ghost"
            disabled={busy}
            onClick={() => {
              setError(null);
              setStage({ kind: 'disabling' });
            }}
          >
            Already using it? Turn it off
          </Button>
        </div>
      ) : null}

      {stage.kind === 'enrolling' ? (
        <EnrolmentStep setup={stage.setup} busy={busy} onConfirm={confirm} />
      ) : null}

      {stage.kind === 'codes' ? (
        <RecoveryCodes codes={stage.codes} onAcknowledge={() => setStage({ kind: 'enabled' })} />
      ) : null}

      {stage.kind === 'enabled' ? (
        <div className="mt-4 flex flex-wrap items-center gap-3">
          <p className="text-sm font-medium text-content">Two-factor authentication is on.</p>
          <Button
            type="button"
            variant="secondary"
            disabled={busy}
            onClick={() => {
              setError(null);
              setStage({ kind: 'disabling' });
            }}
          >
            Turn off
          </Button>
        </div>
      ) : null}

      {stage.kind === 'disabling' ? (
        <DisableStep
          busy={busy}
          onCancel={() => {
            setError(null);
            setStage({ kind: 'unknown' });
          }}
          onConfirm={turnOff}
        />
      ) : null}
    </SettingsSection>
  );
}

/**
 * The secret, and the code that proves it was added.
 *
 * **The `otpauth://` URI is not rendered as a QR code.** Doing so means a QR
 * encoder, and there is no such dependency in `package.json` — adding one puts
 * a lockfile change in an otherwise frontend-only pull request, which is the
 * same trade `TicketDetailTabs` declined for its tab strip. Every authenticator
 * app accepts a typed Base32 key, so the flow works today and the QR is a
 * follow-up that improves it rather than a prerequisite that blocks it.
 */
function EnrolmentStep({
  setup,
  busy,
  onConfirm,
}: {
  setup: TwoFactorSetup;
  busy: boolean;
  onConfirm: (code: string) => void;
}) {
  const [code, setCode] = React.useState('');

  return (
    <form
      className="mt-4 flex flex-col gap-4"
      onSubmit={(event) => {
        event.preventDefault();
        onConfirm(code);
      }}
      noValidate
    >
      <ol className="flex list-decimal flex-col gap-3 pl-5 text-sm text-content">
        <li>Open your authenticator app and choose to add an account by entering a key.</li>
        <li>
          Enter this setup key:
          <CopyableSecret value={setup.secret} label="Setup key" />
        </li>
        <li>Type the six-digit code it shows.</li>
      </ol>

      <AuthField id="totp-code" label="Six-digit code">
        {(aria) => (
          <Input
            {...aria}
            value={code}
            onChange={(event) => setCode(event.target.value.replace(/\D/g, '').slice(0, 6))}
            inputMode="numeric"
            autoComplete="one-time-code"
            /* The server's pattern is ^\d{6}$. Matching it here turns a round
               trip into a disabled button. */
            pattern="\d{6}"
            maxLength={6}
            autoFocus
          />
        )}
      </AuthField>

      <div>
        <Button type="submit" disabled={busy || code.length !== 6}>
          {busy ? 'Checking…' : 'Turn on two-factor authentication'}
        </Button>
      </div>

      <p className="text-caption text-content-muted">
        Nothing changes until this code verifies — if the key will not work in your app, leave this
        tab and start again.
      </p>
    </form>
  );
}

/** Shown once, and the acknowledgement is the only way past it. */
function RecoveryCodes({ codes, onAcknowledge }: { codes: string[]; onAcknowledge: () => void }) {
  const [acknowledged, setAcknowledged] = React.useState(false);

  return (
    <div className="mt-4 flex flex-col gap-4">
      <div
        role="status"
        className="rounded-control border border-warning bg-surface p-3 text-sm text-warning-text"
      >
        <p className="font-medium">Save these recovery codes now. They are shown once.</p>
        <p className="mt-1">
          Each one works instead of a six-digit code, once. They are the only way back in if you
          lose the device your authenticator is on.
        </p>
      </div>

      <ul className="grid gap-2 sm:grid-cols-2" aria-label="Recovery codes">
        {codes.map((code) => (
          <li
            key={code}
            className="rounded-control border border-border bg-subtle px-3 py-2 font-mono text-sm text-content"
          >
            {code}
          </li>
        ))}
      </ul>

      <div>
        <CopyButton value={codes.join('\n')} label="Copy all codes" />
      </div>

      <label className="flex items-start gap-2 text-sm text-content">
        <input
          type="checkbox"
          checked={acknowledged}
          onChange={(event) => setAcknowledged(event.target.checked)}
          className="mt-0.5 h-4 w-4 rounded border-border text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
        />
        I have saved these codes somewhere I can reach without this device.
      </label>

      <div>
        <Button type="button" onClick={onAcknowledge} disabled={!acknowledged}>
          Done
        </Button>
      </div>
    </div>
  );
}

/**
 * Turning it off costs the password, not just the session.
 *
 * The contract's reasoning, kept on screen because the form looks like an
 * over-ask without it: stripping the second factor is the first thing a stolen
 * fifteen-minute access token would be used for, and a token must not be enough
 * to remove the protection it was layered under.
 */
function DisableStep({
  busy,
  onCancel,
  onConfirm,
}: {
  busy: boolean;
  onCancel: () => void;
  onConfirm: (password: string) => void;
}) {
  const [password, setPassword] = React.useState('');

  return (
    <form
      className="mt-4 flex flex-col gap-4"
      onSubmit={(event) => {
        event.preventDefault();
        onConfirm(password);
      }}
      noValidate
    >
      <AuthField id="disable-2fa-password" label="Your password">
        {(aria) => (
          <Input
            {...aria}
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            type="password"
            autoComplete="current-password"
            autoFocus
          />
        )}
      </AuthField>

      <p className="text-sm text-content-muted">
        This clears your authenticator key and every recovery code. Turning it back on means setting
        up a new key.
      </p>

      <div className="flex gap-3">
        <Button type="submit" variant="danger" disabled={busy || password.length === 0}>
          {busy ? 'Turning off…' : 'Turn off'}
        </Button>
        <Button type="button" variant="ghost" onClick={onCancel} disabled={busy}>
          Cancel
        </Button>
      </div>
    </form>
  );
}

function CopyableSecret({ value, label }: { value: string; label: string }) {
  return (
    <div className="mt-2 flex flex-wrap items-center gap-2">
      <code
        aria-label={label}
        className="select-all rounded-control border border-border bg-subtle px-3 py-2 font-mono text-sm tracking-wider text-content"
      >
        {value}
      </code>
      <CopyButton value={value} label="Copy setup key" />
    </div>
  );
}

/**
 * `navigator.clipboard` is absent over plain HTTP and in jsdom, and rejects when
 * the document is not focused — so the copy is best-effort and the value is
 * always selectable text beside it. A button that throws is worse than one that
 * says it could not.
 */
function CopyButton({ value, label }: { value: string; label: string }) {
  const [copied, setCopied] = React.useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
    } catch {
      toast({ title: 'Could not copy — select the text and copy it by hand', variant: 'danger' });
    }
  }

  return (
    <Button type="button" variant="secondary" size="sm" onClick={copy}>
      {copied ? 'Copied' : label}
    </Button>
  );
}

function confirmMessage(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return 'Could not reach the server. Check your connection and try again.';
  }
  if (error.is(INVALID_TOTP_CODE)) {
    return 'That code did not verify. Codes expire every 30 seconds — try the current one.';
  }
  if (error.is(VALIDATION)) {
    return Object.values(error.fieldErrors)[0]?.[0] ?? 'Enter the six digits from your app.';
  }
  if (error.is(TWO_FACTOR_NOT_ENROLLED)) {
    return 'That setup expired. Start again.';
  }
  return 'Could not turn on two-factor authentication. Try again.';
}
