import { useEffect, useState } from 'react';
import { permissionState, subscribe, unsubscribe, type PushState } from './pushSubscription';

/**
 * D-045 · the permission prompt.
 *
 * <p>Rendered wherever a user manages how they are reached — S-26's preference
 * screen — and <strong>never on page load</strong>.
 *
 * That is the whole design of this component. A browser permission prompt fired
 * on load is the most reliable way to be denied forever: `denied` cannot be
 * reversed by the site, only by the user going into browser settings by hand,
 * which nobody does. So the prompt is a button the user presses, next to an
 * explanation of what it is for, and the browser's own dialog appears only after
 * that. One deliberate click beats one accidental refusal that can never be
 * undone.
 */
export function PushOptIn() {
  const [state, setState] = useState<PushState>('unsupported');
  const [busy, setBusy] = useState(false);

  // Reading the current permission is not prompting — `Notification.permission`
  // is a getter and shows no dialog. Nothing here asks for anything.
  useEffect(() => setState(permissionState()), []);

  if (state === 'unsupported') {
    return null;
  }

  const enable = async () => {
    setBusy(true);
    try {
      setState(await subscribe());
    } finally {
      setBusy(false);
    }
  };

  const disable = async () => {
    setBusy(true);
    try {
      await unsubscribe();
      // Permission stays granted — we have unsubscribed, not revoked. Saying
      // "blocked" here would be a lie the user could not act on.
      setState('available');
    } finally {
      setBusy(false);
    }
  };

  return (
    <section aria-labelledby="push-opt-in-heading" className="rounded-lg border border-slate-200 p-4">
      <h3 id="push-opt-in-heading" className="text-sm font-semibold text-slate-900">
        Browser notifications
      </h3>

      {state === 'granted' && (
        <>
          <p className="mt-1 text-sm text-slate-600">
            This browser will show a notification when something needs you, even when
            EduTrack is not the tab you are looking at.
          </p>
          <button
            type="button"
            onClick={disable}
            disabled={busy}
            className="mt-3 rounded-md border border-slate-300 px-3 py-1.5 text-sm font-medium text-slate-700 disabled:opacity-60"
          >
            Turn off on this browser
          </button>
        </>
      )}

      {state === 'available' && (
        <>
          <p className="mt-1 text-sm text-slate-600">
            Get a notification on this device when a ticket is handed to you or an SLA is
            about to breach. You can turn it off again here at any time.
          </p>
          <button
            type="button"
            onClick={enable}
            disabled={busy}
            className="mt-3 rounded-md bg-indigo-600 px-3 py-1.5 text-sm font-medium text-white disabled:opacity-60"
          >
            {busy ? 'Asking…' : 'Turn on notifications'}
          </button>
        </>
      )}

      {state === 'denied' && (
        // Deliberately not a button. Pressing it again would do nothing —
        // the browser will not re-prompt an origin it has been told to block,
        // and a button that silently fails is worse than an explanation.
        <p className="mt-1 text-sm text-slate-600">
          This browser is blocking notifications from EduTrack. To turn them on, allow
          notifications for this site in your browser settings — we cannot ask again from
          here.
        </p>
      )}

      {state === 'unavailable' && (
        <p className="mt-1 text-sm text-slate-600">
          Browser notifications are not configured on this EduTrack deployment yet.
        </p>
      )}
    </section>
  );
}
