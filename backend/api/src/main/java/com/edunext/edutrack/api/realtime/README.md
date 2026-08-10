# api/realtime

**Owner: Stream D · Debashis**

STOMP config, the Redis relay, and the subscription guard.

## Subscription authorisation (D-013)

**Until this landed, chat's entire authorisation model could be walked around by
opening a socket.** The REST side is careful — a thread you are not in is
indistinguishable from one that does not exist — and then every message posted
to it was broadcast to `/topic/ticket.{id}`, which anybody could subscribe to.

`SubscriptionAuthorisation` sits on the **client inbound channel**, so a
SUBSCRIBE is judged before the broker ever registers it. Filtering outbound
instead would leave the subscription in place and re-run the check on every
delivered message — the wrong cost, and the client would believe it was
subscribed.

| Destination | Who may subscribe |
|---|---|
| `/user/queue/events` | Anyone authenticated. Spring scopes it to their own session; there is nothing to widen |
| `/topic/ticket.{id}` | Anyone a `SubscriptionScope` grants — today, someone with a chat thread on that ticket |
| `/topic/project.{id}` | Same, for a project channel |
| `/topic/stage.{code}.{projectId}` | **Nobody, until A-034** |
| `/topic/manager.{id}` | **Nobody, until A-034** |
| `/user/{id}/queue/events` | **Nobody** — that is the *send* form, see below |
| Anything else | **Nobody** |

**Deny by default.** A destination this class does not understand is refused,
not passed through. `RealtimeDestinations.parse` returns empty for anything
outside §9.3, and a room added later without a rule here must fail loudly rather
than be readable by everyone.

**Rejections throw rather than drop.** Returning `null` would swallow the
SUBSCRIBE and the client would sit there believing it was subscribed and
receiving nothing — the same silent failure the destination map exists to
prevent. An exception becomes a STOMP ERROR frame the client can report.

The refusal message names the destination and nothing else. A probe learns no
more than it already knew, which is the same reason REST answers 404 rather than
403 for an out-of-scope row.

### `SubscriptionScope` is an interface on purpose

The answer to "may this user watch this room" belongs to whichever feature owns
the data. Chat knows `chat_participants`; A-034's `ScopeResolver` will know which
tickets a role can see. Realtime must learn neither, or the socket grows a second
copy of every scoping rule and the two drift.

**Implementations are combined as a union.** One room can be reachable for more
than one reason: `/topic/ticket.4471` carries chat messages today and will carry
ribbon advances after D-058, and those have different audiences. Any
implementation granting access is enough. Adding one opens a door; nothing opens
one by accident.

### Subscribe to `/user/queue/events`, never `/user/{id}/queue/events`

The id-bearing form is what the **server** publishes to — Spring resolves it to
the session queue of the user it names. A client that subscribes to it instead
gets a literal destination nothing ever publishes to: accepted, silent, and
receiving nothing forever. It is refused here so the mistake surfaces where it
is made. `frontend/src/realtime/destinations.ts` exposes `ownQueue()` for the
subscribe side and takes no id, because there is nothing to choose.

## Not done yet

- **A-034 widens two rows of that table.** Stage and manager topics are shut,
  and the ticket topic is narrower than it will be — "anyone who could `GET` this
  ticket" is a strictly larger set than "anyone with a chat thread on it". Both
  arrive as a second `SubscriptionScope`, so nothing here changes.
- **SEND is not guarded here.** `/app/**` messages are checked by their own
  `@MessageMapping` handler — chat's typing endpoint verifies membership itself.
  Judging them in the interceptor would mean this class understanding every
  route. Worth revisiting if a second `/app` consumer appears.
- **CONNECT is not rejected for an anonymous socket**, only SUBSCRIBE is. An
  unauthenticated client can open a session and do nothing with it. Tightening
  that belongs with A-032's real chain, which decides what authenticates a
  handshake in the first place.
