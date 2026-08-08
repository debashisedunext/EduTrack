import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import type { Client } from '@stomp/stompjs';
import { RealtimeClient } from './client';

/**
 * These tests exercise the behaviour that a real socket would make slow and
 * flaky to prove: what happens across a reconnect, when two components watch
 * the same room, and when a message or a handler misbehaves.
 *
 * The seam is `createClient`. Faking the transport rather than the network
 * means "did we re-subscribe?" is a direct assertion instead of an inference
 * from timing.
 */

interface FakeSubscription {
  destination: string;
  unsubscribed: boolean;
}

/**
 * A stand-in for stompjs's `Client`, faithful on the one point that matters:
 * `connected` is false until the connection is announced, and subscriptions do
 * **not** survive a disconnect.
 */
class FakeStomp {
  connected = false;
  activated = false;
  reconnectDelay = 0;
  connectHeaders: Record<string, string> = {};
  onConnect: () => void = () => {};
  onWebSocketClose: () => void = () => {};
  onStompError: (frame: unknown) => void = () => {};

  /** Every subscribe ever made, including across reconnects. */
  readonly subscriptions: FakeSubscription[] = [];

  private readonly callbacks = new Map<string, (message: { body: string }) => void>();

  activate(): void {
    this.activated = true;
  }

  async deactivate(): Promise<void> {
    this.connected = false;
  }

  subscribe(destination: string, callback: (message: { body: string }) => void) {
    const record: FakeSubscription = { destination, unsubscribed: false };
    this.subscriptions.push(record);
    this.callbacks.set(destination, callback);
    return {
      unsubscribe: () => {
        record.unsubscribed = true;
        this.callbacks.delete(destination);
      },
    };
  }

  // ---- test controls -------------------------------------------------

  /** The server accepted the connection. */
  connect(): void {
    this.connected = true;
    this.onConnect();
  }

  /** The socket dropped — subscriptions die with it, as they really do. */
  drop(): void {
    this.connected = false;
    this.callbacks.clear();
    this.onWebSocketClose();
  }

  /** Deliver a frame the server would have sent. */
  emit(destination: string, body: string): void {
    this.callbacks.get(destination)?.({ body });
  }

  /** Live destinations, i.e. what the broker currently believes. */
  active(): string[] {
    return [...this.callbacks.keys()];
  }
}

function build() {
  const stomp = new FakeStomp();
  const client = new RealtimeClient({
    createClient: () => stomp as unknown as Client,
  });
  return { stomp, client };
}

const TICKET = '/topic/ticket.4471';
const PROJECT = '/topic/project.7';

beforeEach(() => {
  vi.spyOn(console, 'warn').mockImplementation(() => {});
  vi.spyOn(console, 'error').mockImplementation(() => {});
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('RealtimeClient', () => {
  it('delivers a parsed payload to the subscriber', () => {
    const { stomp, client } = build();
    const received: unknown[] = [];
    client.subscribe(TICKET, (payload) => received.push(payload));
    stomp.connect();

    stomp.emit(TICKET, JSON.stringify({ event: 'STAGE_ADVANCED', stage: 'QA' }));

    expect(received).toEqual([{ event: 'STAGE_ADVANCED', stage: 'QA' }]);
  });

  it('subscribes to a room registered before the connection was up', () => {
    const { stomp, client } = build();
    client.subscribe(TICKET, () => {});

    // Nothing can be subscribed yet — there is no connection to subscribe on.
    expect(stomp.active()).toEqual([]);

    stomp.connect();
    expect(stomp.active()).toEqual([TICKET]);
  });

  it('re-subscribes every room after a reconnect', () => {
    const { stomp, client } = build();
    const received: unknown[] = [];
    client.subscribe(TICKET, (payload) => received.push(payload));
    client.subscribe(PROJECT, () => {});
    stomp.connect();
    expect(stomp.active()).toEqual([TICKET, PROJECT]);

    stomp.drop();
    expect(stomp.active()).toEqual([]);

    stomp.connect();

    // The regression this class exists to prevent: stompjs restores the socket
    // but not the subscriptions, so without re-subscribing the app reconnects
    // successfully and then silently never receives anything again.
    expect(stomp.active()).toEqual([TICKET, PROJECT]);
    stomp.emit(TICKET, JSON.stringify({ event: 'BACK' }));
    expect(received).toEqual([{ event: 'BACK' }]);
  });

  it('opens one broker subscription for several subscribers of the same room', () => {
    const { stomp, client } = build();
    const first: unknown[] = [];
    const second: unknown[] = [];
    client.subscribe(TICKET, (p) => first.push(p));
    client.subscribe(TICKET, (p) => second.push(p));
    stomp.connect();

    expect(stomp.subscriptions.filter((s) => s.destination === TICKET)).toHaveLength(1);

    stomp.emit(TICKET, JSON.stringify({ n: 1 }));
    expect(first).toEqual([{ n: 1 }]);
    expect(second).toEqual([{ n: 1 }]);
  });

  it('keeps the room alive while any subscriber remains', () => {
    const { stomp, client } = build();
    const survivor: unknown[] = [];
    const leaving = client.subscribe(TICKET, () => {});
    client.subscribe(TICKET, (p) => survivor.push(p));
    stomp.connect();

    leaving();

    expect(stomp.active()).toEqual([TICKET]);
    stomp.emit(TICKET, JSON.stringify({ n: 2 }));
    expect(survivor).toEqual([{ n: 2 }]);
  });

  it('unsubscribes from the broker once the last subscriber leaves', () => {
    const { stomp, client } = build();
    const off = client.subscribe(TICKET, () => {});
    stomp.connect();

    off();

    expect(stomp.active()).toEqual([]);
    expect(stomp.subscriptions[0].unsubscribed).toBe(true);
  });

  it('does not resurrect a room that was fully unsubscribed', () => {
    const { stomp, client } = build();
    const off = client.subscribe(TICKET, () => {});
    stomp.connect();
    off();

    stomp.drop();
    stomp.connect();

    expect(stomp.active()).toEqual([]);
  });

  it('drops an unparseable message without disturbing the subscription', () => {
    const { stomp, client } = build();
    const received: unknown[] = [];
    client.subscribe(TICKET, (payload) => received.push(payload));
    stomp.connect();

    stomp.emit(TICKET, 'not json{');
    stomp.emit(TICKET, JSON.stringify({ n: 3 }));

    // The bad frame is gone; the good one that followed still arrives.
    expect(received).toEqual([{ n: 3 }]);
  });

  it('isolates a handler that throws from the others', () => {
    const { stomp, client } = build();
    const received: unknown[] = [];
    client.subscribe(TICKET, () => {
      throw new Error('render blew up');
    });
    client.subscribe(TICKET, (payload) => received.push(payload));
    stomp.connect();

    stomp.emit(TICKET, JSON.stringify({ n: 4 }));

    expect(received).toEqual([{ n: 4 }]);
  });

  it('lets a handler unsubscribe itself while the event is being delivered', () => {
    const { stomp, client } = build();
    const received: unknown[] = [];
    const off = client.subscribe(TICKET, (payload) => {
      received.push(payload);
      off();
    });
    stomp.connect();

    expect(() => stomp.emit(TICKET, JSON.stringify({ n: 5 }))).not.toThrow();
    expect(received).toHaveLength(1);
    expect(stomp.active()).toEqual([]);
  });

  it('backs off on repeated drops and resets once connected', () => {
    const { stomp, client } = build();
    client.subscribe(TICKET, () => {});
    stomp.connect();

    stomp.drop();
    const afterOne = stomp.reconnectDelay;
    stomp.drop();
    const afterTwo = stomp.reconnectDelay;

    expect(afterTwo).toBeGreaterThan(afterOne);

    stomp.connect();
    // A server that came back must not inherit the backoff from the outage —
    // the next blip would otherwise start at half a minute.
    expect(stomp.reconnectDelay).toBe(afterOne / 2);
  });

  it('opens exactly one socket however many components subscribe', () => {
    const stomp = new FakeStomp();
    const created = vi.fn(() => stomp as unknown as Client);
    const client = new RealtimeClient({ createClient: created });

    client.subscribe(TICKET, () => {});
    client.subscribe(PROJECT, () => {});
    client.activate();

    // The transport is loaded lazily, so several components mounting together
    // could otherwise each start their own — three sockets, and every event
    // delivered three times.
    expect(created).toHaveBeenCalledTimes(1);
  });

  it('reports status transitions for the connection indicator', () => {
    const seen: string[] = [];
    const stomp = new FakeStomp();
    const client = new RealtimeClient({
      createClient: () => stomp as unknown as Client,
      onStatusChange: (status) => seen.push(status),
    });

    client.subscribe(TICKET, () => {});
    stomp.connect();
    stomp.drop();

    expect(seen).toEqual(['connecting', 'connected', 'reconnecting']);
  });
});
