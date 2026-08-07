# Stream D — Engines & Realtime · Task Backlog

**Milestones:** M0 (contract + mocks) · M5 (SLA, escalation, mail, notifications) · M7 (chat, realtime)
**Owner:** Debashis · `debashis@edunexttechnologies.com` · @debashisedunext
**Branch prefix:** `feat/engines/…`
**Owns:** `backend/worker/`, `backend/api/feature/{notifications,chat}/`, `backend/api/realtime/`, `frontend/src/features/{chat,notifications}/`

> **Your Sprint 0 output unblocks the entire frontend.** D-004 (MSW mock server) is what lets Stream C build the whole ticket detail page before a single ticket endpoint exists. Ship the contract first, features second.

> Cross-stream sequencing — who is waiting on you and what to do if you are blocked — is in [`../DEPENDENCIES.md`](../DEPENDENCIES.md).

---

## Sprint 0 — weeks 1–2

*Depends on nothing. Start day 1.*

- [ ] **D-001** 🔴 **OpenAPI contract for every endpoint in blueprint §13** — auth, users, projects, tickets, ribbon/journey/stages, clients + import, comments, attachments, dashboard, reports, notifications, chat, masters. Reviewed and agreed by all four streams before Sprint 0 closes.
- [ ] **D-002** Conventions baked into the spec: `/api/v1` prefix, `{ data, meta, error }` envelope, problem-details errors, idempotency key on POST create, ETag on detail reads, `?cursor=&limit=` pagination.
- [ ] **D-003** springdoc config + codegen pipeline (springdoc → `orval` → TypeScript client + Zod schemas). **Bean Validation annotations on Java DTOs are the single source of truth** — the frontend never hand-writes validation.
- [ ] **D-004** 🔴 **MSW mock server** returning realistic fixtures for every endpoint. C and B develop entirely against this until real endpoints land.
- [ ] **D-005** CI staleness check — **fails the build if the committed TS client is stale** against the spec. This is the only thing preventing contract drift between Java and TypeScript.

**Exit:** all four streams have signed off the contract; `npm run dev` works with no backend running.

---

## Infrastructure — weeks 3–5

- [x] **D-010** 🔴 **Outbox worker pattern** — claim rows with `SELECT … FOR UPDATE SKIP LOCKED`, send, stamp the result. `email_log` **is** the queue: `status ∈ {QUEUED, SENT, BOUNCED, FAILED}`, `retry_count`, plus a `next_attempt_at` column for backoff. Enqueue is atomic with the business transaction, so a rolled-back handoff cannot leave a phantom mail queued. *(Replaces BullMQ — PLAN.md §2.2)* — *`worker/outbox/` + `domain/outbox/OutboxEnqueuer`; claim leases via `next_attempt_at` rather than adding a fifth `status`; no ShedLock by design, `SKIP LOCKED` is what allows horizontal scaling. `OutboxWorkerIT` proves disjoint concurrent claims, backoff, retry exhaustion and rollback atomicity against MySQL 8.4. Transport defaults to `logging` until D-029 renders bodies.*
- [x] **D-011** `@Scheduled` + **ShedLock** (Redis or JDBC provider) so scanners don't double-fire across instances. — *`worker/scheduling/SchedulerLockConfig`. **Redis provider**, not JDBC: the JDBC one needs its own `shedlock` table and therefore a Stream A migration, for a lock holding no business data. `defaultLockAtMostFor` PT10M, overridable per scanner. The outbox is deliberately left unlocked — `SKIP LOCKED` is what lets it scale horizontally. `SchedulerLockIT` proves two instances exclude, release, and that a crashed instance's lock expires.*
- [x] **D-012** Spring WebSocket + STOMP config, Redis pub/sub relay for multi-instance fan-out. *(Replaces Socket.IO)* — *`api/realtime/`. Endpoint `/ws` with SockJS; broker prefixes `/topic`, `/queue`, app `/app`, user `/user`. `RealtimePublisher` always goes out via Redis — never straight to the local broker — so every instance delivers by one path and the originating instance cannot double-send. Delivery is best-effort by design; anything the user must not miss is persisted by D-046. `RealtimeRelayIT` uses a real STOMP client and proves same-instance delivery, cross-instance fan-out, destination isolation and that a malformed relay message doesn't kill the listener. Destination naming is D-014; subscription authorisation is D-013.*
- [ ] **D-013** 🔴 **Channel interceptor authorising subscriptions with the same scope rules as REST** — a Developer must not be able to subscribe to a ticket topic they could not `GET`. This is the socket-layer equivalent of the scope guard and is easy to forget.
- [x] **D-014** Destination map per blueprint §9.3 — `/user/{id}/queue/events`, `/topic/ticket.{id}`, `/topic/stage.{code}.{projectId}`, `/topic/project.{id}`, `/topic/manager.{id}`. — *`api/realtime/RealtimeDestinations`, plus a sealed `RealtimeDestination` and `parse()` — the inverse D-013 authorises against, so the interceptor never re-derives room identity with an ad-hoc substring. Stage codes are validated, not sanitised: a dot would re-shape the destination and could land a subscriber in another team's queue. Unknown destinations parse to empty and must be denied, never defaulted.*
- [ ] **D-015** Frontend STOMP client (`@stomp/stompjs` + SockJS fallback) with reconnect and subscription lifecycle.

---

## M5 — SLA, escalation & mail · weeks 6–11

### Scanners
- [ ] **D-020** 🔴 **SLA scanner, every 15 minutes** — `pcd_open < now` → level `CRITICAL`, `is_delayed = true`, `delayed_since` stamped, alert to Reporting Manager + PM + assignee. Uses A's generated-column index so the scan is O(breaches), not O(all tickets).
- [ ] **D-021** 80%-of-SLA pre-breach warning to the assignee.
- [ ] **D-022** Stale-task nudge — no update for 3 working days, to assignee cc RM.
- [ ] **D-023** 🔴 **Stage-SLA scanner, separate from ticket SLA** — a ticket can be well inside its PCD while rotting four days in the Deployment queue. Per-stage SLAs are what make the ribbon actionable rather than decorative. *(§16 item 3b)*
- [ ] **D-024** Escalation matrix per project — L1 at breach, L2 after 48 h beyond PCD to the RM's manager.
- [ ] **D-025** Ping-pong flag at `iteration_no ≥ 3` → PM dashboard.
- [ ] **D-026** Unassigned ticket > 2 h → triage alert to PM and Support Desk.
- [ ] **D-027** 🔴 **Every calculation routes through Stream B's working-hours service.** A Friday-18:00 ticket with a 4-hour SLA must not breach on Saturday morning. Do not write your own date maths.
- [ ] **D-028** `original_level` preserved so "born critical vs became critical" stays reportable. Auto-escalation writes history with `actor_type = 'SYSTEM'`.

### Mail engine — §4B.6
- [ ] **D-029** Thymeleaf templates driven by Stream B's notification template master, with merge tags `{{ticket_id}}`, `{{assignee}}`, `{{stage}}`, `{{client}}`, `{{planned_close}}`.
- [ ] **D-030** Mail body — level chip, project, client, current stage, PCD, who acted and what they said, a primary **Open ticket** button, and a reply hint.
- [ ] **D-031** Subject pattern with the **ticket ID first** so it threads and searches cleanly: `[CRM-26-00347] Handed to you at QA by Ravi Kumar`.
- [ ] **D-032** 🔴 **Threading** — `Message-ID` and `In-Reply-To` headers keyed on the ticket, so a whole ticket's mail collapses into one Outlook/Gmail thread.
- [x] **D-033** Every send logged in `email_log` with status, provider message ID and retry count. Three retries, exponential backoff, then an in-app failure notification — **nobody should assume a mail arrived that never did**. — *Logging, retry count and backoff came with D-010; this adds the last clause. `worker/outbox/MailFailureNotifier` + `domain/notifications/NotificationWriter`. Notifies the intended recipient; falls back to active Admins when the recipient is a client contact with no login. Only fires on terminal failure — crying wolf on attempt one trains people to ignore it. Notification failure never propagates, or the worker would retry a message the transport has already abandoned. A test pins "three retries" to the shipped `max-attempts` rather than to a value the tests invent.*
- [ ] **D-034** Bounce and complaint webhooks — mark the address invalid, alert Admin.
- [ ] **D-035** Rate limit — no more than one mail per recipient per ticket per minute, so a burst of updates doesn't spam the assignee.
- [ ] **D-036** 🔴 **"Critical mails cannot be disabled"** — assignment, handoff, escalation and breach ignore user preferences. Everything else respects them.
- [ ] **D-037** All 15 mail events from §4B.6 wired.
- [ ] **D-038** Daily digest 08:30 and weekly manager summary.
- [ ] **D-039** Inbound webhook — reply-to-comment parsing with quoted text stripped. *(Email-to-ticket itself is phase 6.)*

### Notifications
- [ ] **D-040** All 24 events from blueprint §11 across in-app / bell / email channels.
- [ ] **D-041** Notification centre — bell dropdown (last 10) + full page with tabs: All / Mentions / Assignments / Escalations / Status requests. **S-26**
- [ ] **D-042** Per-user preference matrix — which events, which channel.
- [ ] **D-043** In-app toast via WebSocket, appearing within ~1 second, with Open / Snooze / Dismiss.
- [ ] **D-044** Persistent bell badge with unread count.
- [ ] **D-045** Browser push via the Web Push API for users who opt in.
- [ ] **D-046** 🔴 **Offline queueing** — if the user is offline when the event fires, the notification is stored and pops the moment they log in. Nothing is lost.

**Exit:** walkthrough A step 11 fires correctly — the scanner escalates at 00:15, RM and PM receive mail, `email_log` proves delivery, the ticket banner turns red live over WebSocket.

---

## M7 — Chat & realtime · weeks 12–16

- [ ] **D-050** Chat engine, three surfaces one engine: ticket thread, direct message, project channel. **S-25**
- [ ] **D-051** Typing indicator, read receipts, unread counts.
- [ ] **D-052** `@mentions` firing notifications.
- [ ] **D-053** File and image share, emoji, message search.
- [ ] **D-054** `TKT-xxxx` link preview rendering as a rich ticket card.
- [ ] **D-055** 🔴 **Ask Status** — Reporting Manager/PM clicks it, a structured message lands in the ticket thread with `[Reply with update]` and `[Open Quick Update]` actions.
- [ ] **D-056** **Manager response time recorded as a reportable metric**; status requests appear as a distinct badge on the ticket and in the manager's "Awaiting response" list.
- [ ] **D-057** 🔴 **Chat immutable after a 5-minute edit window; deletions leave tombstones** — this is what keeps chat admissible as project evidence.
- [ ] **D-058** Live ribbon advance — push `stage.changed` to `/topic/ticket.{id}` so anyone viewing sees the handoff land. *Coordinate with C-045.*
- [ ] **D-059** Team inbox live updates — `stage.arrived` / `stage.left` on the stage topic, so QA and Deployment queues update without refresh.

---

## Decisions you own

Answer during M5:

- Does auto-escalation to Critical stay Critical after closure, or revert for reporting? *(Recommended: keep `original_level` and report both — coordinate with Stream A on G-4.)*
- Notification fatigue policy: which events default on vs off in the preference matrix? *(Blueprint §17 flags fatigue as a real risk — escalations should be reserved for genuine breaches.)*
- Data retention for closed tickets and chat. *(Recommended: 3 years live, then archive.)*
