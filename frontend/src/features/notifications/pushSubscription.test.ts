import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest';
import { decodeKey, encodeKey, permissionState, subscribe } from './pushSubscription';

/**
 * D-045 · the browser side of push.
 *
 * The assertion that matters most is a negative one: **nothing asks for
 * permission until there is a key to subscribe with.** A permission prompt is a
 * one-shot resource — `denied` cannot be reversed by the site, only by the user
 * going into browser settings by hand — so spending it on a deployment that
 * cannot push is a mistake with no recovery.
 */
describe('push subscription', () => {
  const originalNotification = globalThis.Notification;
  const fetchSpy = vi.fn();

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchSpy);
    fetchSpy.mockReset();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    if (originalNotification) globalThis.Notification = originalNotification;
    vi.restoreAllMocks();
  });

  // ── key encoding ──────────────────────────────────────────────────────────

  describe('base64url', () => {
    it('decodes a VAPID key to the byte array pushManager demands', () => {
      // 65 bytes, an uncompressed P-256 point. The browser rejects anything
      // else with a DOMException naming neither the key nor the encoding, so
      // getting this wrong reads as "push is broken".
      const bytes = decodeKey('BEl6-8mZ_g');
      expect(bytes).toBeInstanceOf(Uint8Array);
      expect(bytes.buffer).toBeInstanceOf(ArrayBuffer);
    });

    it('round-trips through encode and decode', () => {
      const original = 'q7Zt-8Aa_bCdEfGh';
      expect(encodeKey(decodeKey(original).buffer)).toBe(original);
    });

    it('handles the url alphabet, which is the whole reason this is hand-rolled', () => {
      // `-` and `_` are base64url's substitutes for `+` and `/`. atob does not
      // know them, and a key containing either is common rather than exotic.
      const decoded = decodeKey('-_-_');
      expect(Array.from(decoded)).toEqual([251, 255, 191]);
    });

    it('encodes null as empty rather than throwing', () => {
      // getKey() returns null when a subscription has no such key, and a throw
      // here would take out the whole subscribe flow.
      expect(encodeKey(null)).toBe('');
    });
  });

  // ── what we can offer ─────────────────────────────────────────────────────

  describe('permission state', () => {
    it('reports unsupported where the browser has no push', () => {
      // jsdom has no PushManager, which is the honest default here.
      expect(permissionState()).toBe('unsupported');
    });

    it('distinguishes denied from not-yet-asked', () => {
      stubPushCapableBrowser('denied');
      expect(permissionState()).toBe('denied');

      stubPushCapableBrowser('default');
      expect(permissionState()).toBe('available');

      stubPushCapableBrowser('granted');
      expect(permissionState()).toBe('granted');
    });
  });

  // ── the one that must not go wrong ────────────────────────────────────────

  describe('subscribing', () => {
    it('does not ask for permission when the deployment has no VAPID key', async () => {
      const requestPermission = stubPushCapableBrowser('default');
      // The opt-in half answers 404 rather than an empty string precisely so a
      // browser cannot subscribe with a key that will never authenticate.
      fetchSpy.mockResolvedValue(response(404, {}));

      await expect(subscribe()).resolves.toBe('unavailable');

      expect(requestPermission).not.toHaveBeenCalled();
    });

    it('asks only after it has a key, and reports a refusal', async () => {
      const requestPermission = stubPushCapableBrowser('default', 'denied');
      fetchSpy.mockResolvedValue(response(200, { data: { publicKey: 'BEl6-8mZ_g' } }));

      await expect(subscribe()).resolves.toBe('denied');

      expect(requestPermission).toHaveBeenCalledOnce();
      // Nothing was posted: there is no subscription to register.
      expect(postedTo('/me/push-subscriptions')).toBe(false);
    });

    it('registers the subscription with the server once granted', async () => {
      stubPushCapableBrowser('default', 'granted');
      fetchSpy.mockImplementation((url: string) =>
        String(url).includes('public-key')
          ? Promise.resolve(response(200, { data: { publicKey: 'BEl6-8mZ_g' } }))
          : Promise.resolve(response(204, null)),
      );

      await expect(subscribe()).resolves.toBe('granted');
      expect(postedTo('/me/push-subscriptions')).toBe(true);
    });

    it('reuses a subscription the browser already had', async () => {
      const existing = fakeSubscription();
      const manager = stubPushCapableBrowser('granted', 'granted', existing);
      fetchSpy.mockImplementation((url: string) =>
        String(url).includes('public-key')
          ? Promise.resolve(response(200, { data: { publicKey: 'BEl6-8mZ_g' } }))
          : Promise.resolve(response(204, null)),
      );

      await subscribe();

      // subscribe() with a different key throws, and a browser already
      // subscribed under the same one would otherwise churn the row on every
      // visit — which the server reads as a fresh device each time.
      expect(manager.subscribe).not.toHaveBeenCalled();
    });
  });

  // ── helpers ───────────────────────────────────────────────────────────────

  function response(status: number, body: unknown) {
    return {
      ok: status < 400,
      status,
      headers: new Headers({ 'Content-Type': 'application/json' }),
      json: async () => body,
      text: async () => JSON.stringify(body),
    } as unknown as Response;
  }

  function postedTo(path: string): boolean {
    return fetchSpy.mock.calls.some(
      ([url, init]) => String(url).includes(path) && (init as RequestInit)?.method === 'POST',
    );
  }

  function fakeSubscription() {
    return {
      endpoint: 'https://push.example/abc',
      getKey: (name: string) => new TextEncoder().encode(name === 'p256dh' ? 'pub' : 'auth').buffer,
      unsubscribe: vi.fn().mockResolvedValue(true),
    };
  }

  function stubPushCapableBrowser(
    permission: NotificationPermission,
    afterRequest: NotificationPermission = permission,
    existing: ReturnType<typeof fakeSubscription> | null = null,
  ) {
    const requestPermission = vi.fn().mockResolvedValue(afterRequest);
    vi.stubGlobal('Notification', { permission, requestPermission });
    vi.stubGlobal('PushManager', class {});

    const manager = {
      getSubscription: vi.fn().mockResolvedValue(existing),
      subscribe: vi.fn().mockResolvedValue(existing ?? fakeSubscription()),
    };
    vi.stubGlobal('navigator', {
      ...globalThis.navigator,
      serviceWorker: {
        register: vi.fn().mockResolvedValue({ pushManager: manager }),
        getRegistration: vi.fn().mockResolvedValue({ pushManager: manager }),
      },
    });
    return Object.assign(requestPermission, manager);
  }
});
