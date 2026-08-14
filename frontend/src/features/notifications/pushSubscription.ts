import { http } from '../../api/http';

/**
 * D-045 · subscribing this browser to push.
 *
 * Plain functions rather than a hook, so the awkward parts — base64url keys,
 * an already-granted permission, a subscription the browser kept from a
 * previous session — are testable without rendering anything.
 */

/** Where the worker lives. Root scope, so it controls the whole app. */
const WORKER_URL = '/edutrack-push-sw.js';

export type PushState =
  | 'unsupported'
  | 'denied'
  | 'granted'
  | 'available'
  | 'unavailable';

/**
 * What we can offer this browser right now.
 *
 * `unavailable` is not `unsupported`: the browser can do push but this
 * deployment has no VAPID key, which is the normal state of a developer
 * machine. Telling those two apart is what stops the prompt appearing where
 * pressing it could only fail.
 */
export function supportsPush(): boolean {
  return (
    typeof window !== 'undefined' &&
    'serviceWorker' in navigator &&
    'PushManager' in window &&
    'Notification' in window
  );
}

export function permissionState(): PushState {
  if (!supportsPush()) return 'unsupported';
  if (Notification.permission === 'denied') return 'denied';
  if (Notification.permission === 'granted') return 'granted';
  return 'available';
}

/**
 * The server's VAPID public key, or null when this deployment has none.
 *
 * A 404 here is a deployment without push configured, not an error — the opt-in
 * half chose 404 over an empty string precisely so a browser cannot subscribe
 * with a key that will never authenticate.
 */
export async function fetchPublicKey(): Promise<string | null> {
  try {
    const response = await http<{ data: { publicKey: string } }>({
      url: '/push/public-key',
      method: 'GET',
    });
    return response.data.publicKey || null;
  } catch {
    return null;
  }
}

/**
 * base64url → the `Uint8Array` `pushManager.subscribe` demands.
 *
 * The browser rejects anything else with a `DOMException` that names neither
 * the key nor the encoding, so getting this wrong reads as "push is broken".
 */
export function decodeKey(base64Url: string): Uint8Array<ArrayBuffer> {
  const padded = base64Url.padEnd(base64Url.length + ((4 - (base64Url.length % 4)) % 4), '=');
  const binary = atob(padded.replace(/-/g, '+').replace(/_/g, '/'));
  // Backed by an explicit ArrayBuffer rather than `Uint8Array.from`, which
  // infers ArrayBufferLike — and `applicationServerKey` will not accept a view
  // that might sit on a SharedArrayBuffer.
  const bytes = new Uint8Array(new ArrayBuffer(binary.length));
  for (let i = 0; i < binary.length; i += 1) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

/** base64url of an `ArrayBuffer`, matching what the server stores. */
export function encodeKey(buffer: ArrayBuffer | null): string {
  if (!buffer) return '';
  return btoa(String.fromCharCode(...new Uint8Array(buffer)))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');
}

export async function registerWorker(): Promise<ServiceWorkerRegistration> {
  return navigator.serviceWorker.register(WORKER_URL);
}

/**
 * Ask, subscribe, and tell the server.
 *
 * **Only ever call this from a click.** Browsers require a user gesture for a
 * permission prompt, and — far more importantly — a prompt fired on page load
 * is the single most reliable way to get `denied` forever. `denied` cannot be
 * undone by the site: the user has to go into browser settings and reverse it
 * by hand, which nobody does. That is why nothing here runs on mount.
 *
 * @returns the resulting state, so a caller can render what happened rather
 *          than guessing from an exception
 */
export async function subscribe(): Promise<PushState> {
  if (!supportsPush()) return 'unsupported';

  const publicKey = await fetchPublicKey();
  if (!publicKey) {
    // No VAPID pair on this deployment. Asking now would spend the one
    // permission prompt this origin gets on a channel that cannot deliver.
    return 'unavailable';
  }

  const permission = await Notification.requestPermission();
  if (permission !== 'granted') {
    return permission === 'denied' ? 'denied' : 'available';
  }

  const registration = await registerWorker();
  // An existing subscription is reused rather than replaced: `subscribe()` with
  // a different key throws, and a browser that was already subscribed under the
  // same key would otherwise churn the row on every visit.
  const existing = await registration.pushManager.getSubscription();
  const subscription =
    existing ??
    (await registration.pushManager.subscribe({
      // Required by Chrome, and honest: every push we send shows a
      // notification. A silent push would cost the site its permission.
      userVisibleOnly: true,
      applicationServerKey: decodeKey(publicKey),
    }));

  await http({
    url: '/me/push-subscriptions',
    method: 'POST',
    data: {
      endpoint: subscription.endpoint,
      keys: {
        p256dh: encodeKey(subscription.getKey('p256dh')),
        auth: encodeKey(subscription.getKey('auth')),
      },
    },
  });

  return 'granted';
}

/**
 * Stop pushing to this browser.
 *
 * Both sides, in this order: the server first, so a failure leaves the user
 * still subscribed rather than receiving pushes from a row they believe they
 * deleted. The browser-side `unsubscribe()` is best-effort — if it fails the
 * server has already stopped sending, and the next push service 410 would have
 * cleaned the row anyway.
 */
export async function unsubscribe(): Promise<void> {
  if (!supportsPush()) return;
  const registration = await navigator.serviceWorker.getRegistration(WORKER_URL);
  const subscription = await registration?.pushManager.getSubscription();
  if (!subscription) return;

  await http({
    url: `/me/push-subscriptions?endpoint=${encodeURIComponent(subscription.endpoint)}`,
    method: 'DELETE',
  });
  await subscription.unsubscribe().catch(() => undefined);
}
