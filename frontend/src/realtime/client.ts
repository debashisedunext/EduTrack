import type { Client, IFrame, StompSubscription } from '@stomp/stompjs';
import { getAccessToken } from '../api/http';

/**
 * D-015 · the browser end of the realtime engine.
 *
 * One socket per tab, shared by every component that wants live updates. The
 * server side is D-012 (`/ws`, SockJS-enabled) and the destinations come from
 * `destinations.ts`, the mirror of D-014's Java map.
 *
 * **Delivery is best-effort by design.** Redis pub/sub is fire-and-forget, so a
 * disconnected browser misses whatever was published while it was away and there
 * is no replay. That is the right guarantee for live nudges — a ribbon advancing,
 * a badge incrementing — because the next read gets authoritative state from the
 * database. It is *not* the right guarantee for anything the user must not miss,
 * which is why notifications are persisted and replayed on next login (D-046)
 * rather than trusted to this.
 *
 * @see destinations.ts for the §9.3 room map
 */

/** A handler receives the already-parsed JSON payload, not the STOMP frame. */
export type RealtimeHandler = (payload: unknown) => void;

/** Called back as the connection comes and goes, for a UI "reconnecting" hint. */
export type RealtimeStatus = 'idle' | 'connecting' | 'connected' | 'reconnecting';

interface Room {
  handlers: Set<RealtimeHandler>;
  subscription?: StompSubscription;
}

export interface RealtimeClientOptions {
  /** Handshake endpoint. Matches `WebSocketConfig.STOMP_ENDPOINT`. */
  url?: string;
  /** Seam for tests, which must not open a socket. */
  createClient?: (options: RealtimeClientOptions) => Client;
  onStatusChange?: (status: RealtimeStatus) => void;
}

const DEFAULT_URL = import.meta.env.VITE_WS_URL ?? '/ws';

/** First retry is quick; a server that is properly down should not be hammered. */
const INITIAL_RECONNECT_MS = 1_000;
const MAX_RECONNECT_MS = 30_000;

/**
 * stompjs and SockJS are loaded on first use, not at module load.
 *
 * Together they are ~170 kB — as much again as the entire rest of the app — and
 * importing them eagerly puts that on the critical path of the login screen, a
 * page with no realtime on it at all. Loading them when something first
 * subscribes costs one round trip nobody is waiting on.
 */
async function defaultClient(options: RealtimeClientOptions): Promise<Client> {
  const [{ Client: StompClient }, { default: SockJS }] = await Promise.all([
    import('@stomp/stompjs'),
    import('sockjs-client'),
  ]);
  return new StompClient({
    // SockJS rather than a bare WebSocket: it is what replaces Socket.IO's
    // transport fallback (PLAN.md §2.2), and it is why the server registers
    // the endpoint `.withSockJS()`. Corporate proxies that break WebSocket
    // upgrades fall back to XHR streaming instead of realtime silently dying.
    webSocketFactory: () => new SockJS(options.url ?? DEFAULT_URL) as WebSocket,
    reconnectDelay: INITIAL_RECONNECT_MS,
  });
}

export class RealtimeClient {
  private readonly rooms = new Map<string, Room>();
  private readonly options: RealtimeClientOptions;
  private client: Client | null = null;
  private activating = false;
  private status: RealtimeStatus = 'idle';
  private reconnectDelay = INITIAL_RECONNECT_MS;

  constructor(options: RealtimeClientOptions = {}) {
    this.options = options;
  }

  /**
   * Listen on a §9.3 destination.
   *
   * Safe to call before the socket is up: the room is registered now and
   * subscribed the moment the connection is available.
   *
   * @returns an unsubscribe function — call it from a `useEffect` cleanup
   */
  subscribe(destination: string, handler: RealtimeHandler): () => void {
    let room = this.rooms.get(destination);
    if (!room) {
      room = { handlers: new Set() };
      this.rooms.set(destination, room);
    }
    room.handlers.add(handler);

    // Ref-counted: a ticket page with three components watching the same ticket
    // opens one STOMP subscription, not three. Without this, unmounting any one
    // of them would unsubscribe the destination out from under the others.
    if (!room.subscription) {
      this.openSubscription(destination, room);
    }

    this.activate();

    return () => this.removeHandler(destination, handler);
  }

  /** Open the socket. Called automatically by the first `subscribe`. */
  activate(): void {
    // `activating` matters because the default path is async: without it, three
    // components mounting together each start a load and we end up with three
    // sockets, three subscriptions per room, and every event delivered thrice.
    if (this.client || this.activating) return;
    this.activating = true;
    this.setStatus('connecting');

    const create = this.options.createClient;
    if (create) {
      this.install(create(this.options));
      return;
    }
    void defaultClient(this.options).then(
      (client) => this.install(client),
      (error) => {
        this.activating = false;
        this.setStatus('idle');
        console.error('[realtime] could not load the STOMP transport', error);
      },
    );
  }

  private install(client: Client): void {
    this.client = client;
    this.activating = false;

    client.onConnect = () => {
      this.reconnectDelay = INITIAL_RECONNECT_MS;
      client.reconnectDelay = INITIAL_RECONNECT_MS;
      this.setStatus('connected');
      // **The whole reason this class exists.** stompjs restores the socket on
      // its own but not the subscriptions — the connection comes back, the app
      // looks healthy, and nothing ever arrives again. Every known room is
      // re-subscribed on every connect, not just the first.
      for (const [destination, room] of this.rooms) {
        room.subscription = undefined;
        this.openSubscription(destination, room);
      }
    };

    client.onWebSocketClose = () => {
      if (this.status === 'idle') return;
      // Subscriptions from the dead socket are gone; drop the handles so the
      // next connect re-creates them rather than trusting stale ones.
      for (const room of this.rooms.values()) {
        room.subscription = undefined;
      }
      this.setStatus('reconnecting');
      this.reconnectDelay = Math.min(this.reconnectDelay * 2, MAX_RECONNECT_MS);
      client.reconnectDelay = this.reconnectDelay;
    };

    client.onStompError = (frame: IFrame) => {
      // A STOMP-level ERROR frame is the server refusing us — a rejected
      // subscription once D-013 lands, or an expired token. Worth surfacing:
      // it is not a transport blip and retrying unchanged will not fix it.
      console.error(
        '[realtime] broker error:',
        frame.headers?.message ?? '(no message)',
        frame.body,
      );
    };

    // Read at activate time, not at module load: the token does not exist yet
    // when this module is first imported, and it is replaced on refresh.
    const token = getAccessToken();
    client.connectHeaders = token ? { Authorization: `Bearer ${token}` } : {};

    client.activate();
  }

  /** Close the socket and forget every room. */
  async deactivate(): Promise<void> {
    const client = this.client;
    this.client = null;
    this.activating = false;
    this.rooms.clear();
    this.setStatus('idle');
    await client?.deactivate();
  }

  /** Exposed for the connection indicator in the top bar. */
  currentStatus(): RealtimeStatus {
    return this.status;
  }

  private openSubscription(destination: string, room: Room): void {
    if (!this.client?.connected) return;
    room.subscription = this.client.subscribe(destination, (message) =>
      this.deliver(destination, message.body),
    );
  }

  private deliver(destination: string, body: string): void {
    let payload: unknown;
    try {
      payload = body ? JSON.parse(body) : undefined;
    } catch {
      // Mirrors the server relay: one malformed message must not take realtime
      // down for the whole tab. A rolling deploy mid-format-change is the
      // realistic cause.
      console.warn(`[realtime] unparseable message on ${destination}, dropped`);
      return;
    }

    const room = this.rooms.get(destination);
    if (!room) return;

    // Copy before iterating: a handler is allowed to unsubscribe itself, which
    // would otherwise mutate the set mid-iteration.
    for (const handler of [...room.handlers]) {
      try {
        handler(payload);
      } catch (error) {
        // One component throwing must not rob the others of the event.
        console.error(`[realtime] handler for ${destination} threw`, error);
      }
    }
  }

  private removeHandler(destination: string, handler: RealtimeHandler): void {
    const room = this.rooms.get(destination);
    if (!room) return;
    room.handlers.delete(handler);
    if (room.handlers.size > 0) return;

    room.subscription?.unsubscribe();
    this.rooms.delete(destination);
  }

  private setStatus(status: RealtimeStatus): void {
    if (this.status === status) return;
    this.status = status;
    this.options.onStatusChange?.(status);
  }
}

/** The one client the app shares. Features should use `useRealtime`. */
export const realtime = new RealtimeClient();
