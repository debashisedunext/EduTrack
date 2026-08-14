/* eslint-disable no-undef */
/**
 * D-045 · the service worker that renders a browser push.
 *
 * Served from `public/` so it lands at the site root with no build step — a
 * service worker can only control pages at or below its own URL, and one
 * emitted into `assets/` with a hashed name could control nothing and would
 * change its registration on every deploy.
 *
 * Deliberately tiny and dependency-free. This file runs outside the app, in a
 * worker with no React, no router and no auth token, and it survives deploys
 * until the browser decides to update it — so anything clever in here is
 * something that can be stale for days with no way to see it. It renders a
 * notification and handles a click; everything else is the app's job.
 */

/**
 * A push arrived.
 *
 * The payload is what `PushDispatcher.payloadOf` sends: `{id, title, body,
 * link}` — the same title and body already in the bell entry, and nothing more.
 * The server keeps it to that on purpose: this is rendered by the operating
 * system, on a lock screen anybody nearby can read.
 */
self.addEventListener('push', (event) => {
  // `waitUntil` is not optional. Without it the worker may be killed before
  // showPromise settles and the notification silently never appears — the
  // failure is invisible, and only on slow devices.
  event.waitUntil(
    (async () => {
      let payload = {};
      try {
        payload = event.data ? event.data.json() : {};
      } catch {
        // A push with no payload, or one we cannot parse. Browsers require a
        // visible notification for every push received under a userVisibleOnly
        // subscription — showing nothing can cost the site its permission
        // entirely — so this falls back rather than returning.
        payload = {};
      }

      const title = payload.title || 'EduTrack';
      await self.registration.showNotification(title, {
        body: payload.body || '',
        icon: '/favicon.svg',
        badge: '/favicon.svg',
        // Coalesce per notification id: a retry, or the same event delivered
        // twice, replaces rather than stacks. Without a tag a flaky connection
        // shows the user the same alert three times.
        tag: payload.id ? `edutrack-${payload.id}` : undefined,
        data: { id: payload.id ?? null, link: payload.link || '/' },
        // Not `requireInteraction`: an alert that will not dismiss itself is
        // how people turn the whole channel off.
      });
    })(),
  );
});

/**
 * The user clicked it.
 *
 * Focus an existing tab if there is one rather than opening a second — landing
 * somebody on a duplicate of the app they already had open loses whatever they
 * were typing in the first.
 */
self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const link = (event.notification.data && event.notification.data.link) || '/';

  event.waitUntil(
    (async () => {
      const open = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
      for (const client of open) {
        if ('focus' in client) {
          await client.focus();
          // Told rather than navigated: the app owns its router, and a worker
          // driving `client.navigate` would reload the SPA and throw away
          // unsaved state. D-043's stream listens for this.
          client.postMessage({ type: 'edutrack:notification-click', link });
          return;
        }
      }
      await self.clients.openWindow(link);
    })(),
  );
});

/**
 * The push service rotated our subscription.
 *
 * Firefox and Chrome both fire this; the endpoint changes and the old one stops
 * working. The app re-subscribes on its next load — this worker has no auth
 * token and cannot POST the new subscription itself, so the honest thing is to
 * let the old row die (the server deletes it on the 410 it will now get) rather
 * than pretend to recover here.
 */
self.addEventListener('pushsubscriptionchange', () => {
  // Nothing to do without credentials. Left as a named handler rather than
  // absent, so the next person does not assume it was forgotten.
});

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()));
