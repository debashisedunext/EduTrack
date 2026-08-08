# src/realtime

**Owner: Stream D · Debashis**

The browser end of the realtime engine. Server side is `backend/api/realtime/`.

| File | What it is |
|---|---|
| `client.ts` | One shared STOMP-over-SockJS connection, with reconnect and subscription lifecycle |
| `destinations.ts` | The §9.3 room map — the mirror of `RealtimeDestinations.java` |
| `useRealtime.ts` | The hook features should use |

## Using it

```ts
useRealtime(ticketTopic(ticketId), () => {
  queryClient.invalidateQueries({ queryKey: ['ticket', ticketId] });
});
```

Prefer invalidating a query to writing the payload straight into state. The
socket is best-effort, so it should be a *hint that something changed*, not the
source of truth — otherwise a missed frame leaves the UI permanently wrong.

## Two rules

**Never hand-write a destination.** Build it from `destinations.ts`. A mistyped
destination is accepted by the broker and delivered to nobody, so it fails
silently and shows up days later as "realtime is broken for some users".

**Add a room to both maps in the same PR.** A destination that exists on only
one side is a subscription nobody publishes to, or a publish nobody hears.

## Not done yet

**Subscriptions are not authorised** — D-013 needs Stream A's A-034 and is due
Mon 31 Aug. Until it lands, any connected client can subscribe to any ticket
topic. This is the socket-layer twin of the row-scope hole; it must not reach an
environment with real data without D-013 in front of it.
