# EduTrack — Implementation Plan

**Source of truth:** `Ticketing-System-Blueprint.md` (v1.2, 1,749 lines)
**Stack decision:** Java backend · MySQL 8 · React frontend
**Status:** greenfield — no code, no git repository yet
**Plan revision:** 1.0 · 2026-08-04

---

## 0. How to read this document

The blueprint is the *product* specification: what the system does, every screen, every field, every rule. This document is the *build* specification: what stack we use, how the PostgreSQL design translates to MySQL, what order we build in, and what has to be decided before code is written.

Where the two disagree, the blueprint wins on **behaviour** and this document wins on **implementation**. Every deviation from the blueprint is listed explicitly in §4 — none are silent.

---

## 1. What we are building

A production ticketing platform, not an internal tool:

| Dimension | Count |
|---|---|
| Screens (S-01 … S-34) | 34 |
| System roles | 6 (Admin, PM, Support Desk, Developer, QA, Deployment) |
| Database tables | ~28 |
| Dashboard widgets | 20, all drill-down |
| Reports | 18, all exportable and schedulable |
| Notification events | 24 |
| Ribbon stages (default template) | 8 |

Three things are the architectural spine. Everything else hangs off them, and each one is expensive to retrofit:

**1. The append-only history model** (§4, §8.2 of the blueprint). `ticket_history`, `ticket_effort_logs` and `ticket_stage_transitions` are insert-only, hash-chained, and enforced at four independent layers — service, DB grants, DB triggers, and absent HTTP routes. If this is built late, every write path already in existence has to be re-audited.

**2. The Workflow Ribbon** (§4A). Eight configurable stages, `cycle_no` and `iteration_no` as two orthogonal counters, per-stage-per-resource effort attribution, and the active-vs-idle time split that makes it more than decoration. This is the product differentiator and the hardest UI component.

**3. The row-scope guard** (§2, §10.2). Every ticket query is rewritten server-side with a mandatory scope predicate. The blueprint calls it "the single most important security rule in the system," and that is not an overstatement — one leak here and the system fails its first audit.

---

## 2. Stack

### 2.1 Chosen stack

| Layer | Choice | Notes |
|---|---|---|
| Language / runtime | **Java 25 (LTS)** | Virtual threads for the IMAP poller and mail workers. Raised from 21 on 6 Aug 2026 — see §4 D-10 |
| Framework | **Spring Boot 3.5** | Web, Security, Data JPA, Validation, Actuator. 3.5 is what supports Java 25; 3.3 does not |
| Build | **Maven, multi-module** | `api`, `domain`, `worker`, `common` |
| Persistence | **Spring Data JPA + Hibernate 6** | Plus JdbcTemplate for reporting queries |
| Migrations | **Flyway** | Versioned SQL, with explicit delimiter handling for triggers |
| Database | **MySQL 8.4 (InnoDB, `utf8mb4`)** | See §3 for the translation from the blueprint's PostgreSQL DDL |
| Cache / sessions | **Redis 7** | Refresh-token registry, JWT blacklist, rate limits, dashboard cache |
| Scheduler | **Spring `@Scheduled` + ShedLock** | ShedLock (Redis or JDBC provider) prevents double-firing across instances |
| Async / queue | **Transactional outbox + worker module** | Replaces BullMQ — see §2.2 |
| Real-time | **Spring WebSocket + STOMP**, Redis relay | Replaces Socket.IO — see §2.2 |
| Object storage | **MinIO (dev) / S3 (prod)**, AWS SDK v2 | Attachments, avatars, import error reports |
| Excel | **Apache POI** — SXSSF write, SAX/event read | Replaces SheetJS — see §2.2 |
| PDF | **OpenPDF** — LGPL/MPL fork of iText 4 | A-064. The plan named no PDF library; see §2.2 |
| Mail | **Spring Mail (JavaMailSender)** → SES/SendGrid SMTP | Threading via custom `Message-ID` / `In-Reply-To` headers |
| Templating | **Thymeleaf** | Mail bodies; merge tags resolved from the template master |
| Password hashing | **Spring Security `Argon2PasswordEncoder`** + BouncyCastle | Argon2id, 64 MB memory, 3 iterations per §10.3 |
| JWT | **Nimbus JOSE + JWT** (via Spring Security OAuth2 Resource Server) | Access token 15 min; refresh is opaque, stored in Redis |
| API docs / contract | **springdoc-openapi** → generated TypeScript client | Replaces shared Zod schemas — see §2.2 |
| Mapping | **MapStruct** | Entity ⇄ DTO, compile-time |
| Testing | **JUnit 5 · Testcontainers (MySQL, Redis, MinIO) · MockMvc · ArchUnit** | ArchUnit enforces the scope-guard rule structurally |
| Frontend | **React 18 + TypeScript + Vite** | Unchanged from blueprint |
| UI kit | **Tailwind CSS + shadcn/ui** | Design tokens from §12.1 |
| Charts | **Recharts** | Click handlers on every segment for drill-down |
| Server state | **TanStack Query** | Plus **Zustand** for light client state |
| Forms | **React Hook Form + Zod** | Zod schemas generated from OpenAPI, not hand-written |
| WebSocket client | **@stomp/stompjs + SockJS fallback** | |
| Deploy | **Docker Compose → Kubernetes**, Nginx | Unchanged |
| Observability | **Micrometer + Prometheus/Grafana · Sentry · Logback JSON** | Replaces Winston/pino |
| CI/CD | **GitHub Actions** | Lint → test → build → Flyway migrate → deploy |
| Build | **Maven-managed Node** (`frontend-maven-plugin`) | Developers install only JDK 25; Maven downloads a pinned Node 22. `./mvnw verify` builds both halves |
| Packaging | **Single Spring Boot jar** | `frontend/dist` is copied to `classpath:/static/`; one artifact, no separate web server |

### 2.2 Node-specific choices that had to be replaced

The blueprint assumes a Node/NestJS backend. Five of its technology choices have no Java equivalent and needed a deliberate substitute. Each substitution preserves the *behaviour* the blueprint specifies.

| Blueprint says | We use | Why, and what changes |
|---|---|---|
| **BullMQ** (Redis queue) for SLA scanning, mail, digests, report generation | **Transactional outbox table + `@Scheduled` worker + ShedLock** | The blueprint already specifies an `email_log` table with `status ∈ {QUEUED, SENT, BOUNCED, FAILED}` and a `retry_count` — that *is* an outbox. Rather than bolting a second queue alongside it, the `email_log` row is the queue entry: the worker claims rows with `SELECT … FOR UPDATE SKIP LOCKED`, sends, and stamps the result. Retries with exponential backoff become a `next_attempt_at` column. This keeps enqueue atomic with the business transaction (a handoff that rolls back cannot leave a phantom mail queued), which BullMQ could not have guaranteed anyway. RabbitMQ remains an option if volume outgrows it; the worker interface is written so the transport can be swapped. |
| **Socket.IO** | **Spring WebSocket + STOMP**, Redis pub/sub relay for multi-instance fan-out | Room semantics from §9.3 map to STOMP destinations: `user:{id}` → `/user/{id}/queue/events`, `ticket:{id}` → `/topic/ticket.{id}`, `stage:{code}:{projectId}` → `/topic/stage.{code}.{projectId}`, and so on. Subscription is authorised in a `ChannelInterceptor` using the same scope rules as the REST layer — a Developer cannot subscribe to a ticket topic they could not GET. Client side uses `@stomp/stompjs`; Socket.IO's auto-reconnect and fallback are covered by SockJS. |
| **SheetJS** for Excel import | **Apache POI** — SXSSF for template/error-report generation, event-driven SAX reader for uploads | POI's default DOM reader would load a 5,000-row workbook fully into memory per concurrent import; the streaming reader is required, not optional. The four-step wizard (§4B.3) is unchanged in behaviour. |
| *(nothing — the plan named no PDF library)* | **OpenPDF 2.0.3** (`com.github.librepdf:openpdf`) | A-064 needed one for §7.8's PDF export and neither this plan nor the blueprint named a library, so this row records a choice rather than a substitution. **Not iText**: iText 5 is AGPL and iText 7 is commercially licensed, and neither is a licence to acquire by accident in a product an organisation will sell. OpenPDF is the LGPL/MPL fork of iText 4, the last release under that licence. **Not PDFBox**, despite being Apache 2.0 and already a sibling of POI: it positions text by coordinate and has no table primitive, so every report would mean hand-rolling column widths, wrapping and page breaks — which is the whole job. OpenPDF's `PdfPTable` does it, including repeating the header row on every page. The PDF is a *document*, not a data file: heading, the applied scope, a chart drawn server-side from the rows, and the first 100 of them **with the truncation stated on the page** — a silently shortened table looks complete, and a reader quoting a total would be quoting a prefix. Every row belongs in xlsx or CSV. |
| **Prisma / TypeORM** | **Spring Data JPA + Flyway** | Flyway migrations are hand-written SQL, which we want anyway — the immutability triggers and the hash-chain constraints are not expressible through an ORM migration generator. |
| **Zod schemas shared between frontend and backend** | **springdoc-openapi → `orval` (or `openapi-typescript`) codegen** | This is the one genuine loss from leaving TypeScript on the backend: validation rules can no longer be authored once and executed on both sides. Mitigation: Bean Validation annotations on the Java DTOs are the single source of truth; springdoc emits them into the OpenAPI schema (`minLength`, `pattern`, `required`); codegen turns that into Zod schemas for React Hook Form. The generation step runs in CI and **fails the build if the committed client is stale**, so drift is caught at merge, not at runtime. |

### 2.3 Repository layout

```
edunext-edutrack/
├── docker-compose.yml              # mysql, redis, minio, mailpit
├── .github/workflows/ci.yml
├── backend/
│   ├── pom.xml                     # parent, dependency management
│   ├── common/                     # DTOs, enums, exceptions, OpenAPI config
│   ├── domain/                     # entities, repositories, domain services
│   │   └── src/main/resources/db/migration/    # Flyway: V1__…V n__
│   ├── api/                        # controllers, security, guards, WebSocket
│   └── worker/                     # schedulers: SLA scan, mail, digests, hash verify
├── frontend/
│   ├── package.json
│   └── src/
│       ├── api/generated/          # OpenAPI codegen output — never hand-edited
│       ├── components/             # design system + Ribbon, Journey grid, ImportWizard
│       ├── features/               # auth, tickets, masters, dashboard, reports, chat
│       ├── hooks/
│       └── styles/tokens.css       # §12.1 colour tokens
└── docs/
    ├── Ticketing-System-Blueprint.md
    └── PLAN.md
```

`api` depends on `domain`; `worker` depends on `domain`; neither depends on the other. `worker` can be deployed as a separate process or, in small installations, run inside `api` behind a profile flag.

**That profile flag does not exist yet — deploy both jars.** `worker` owns the 5-minute `@Scheduled` pass (A-051) that fills `daily_ticket_stats` and `resource_daily_stats`; the dashboard's Today's Progress and Weekly Progress tabs read only those tables (never live `COUNT(*)`) and stay at zero forever — not just stale — in any environment running the `api` jar without the `worker` jar alongside it. CI's `package` job now uploads both (`edutrack-api-jar`, `edutrack-worker-jar`) as of the fix for exactly this on nonprod; embedding `worker` inside `api` for real would mean moving `worker/stats/DailyStatsRepository` into `domain` first, which is its own cross-stream task — see the ⚠️ notes on A-051 in `STREAM-A-PLATFORM.md`.

---

## 3. PostgreSQL → MySQL translation

The blueprint's DDL (§4A.5, §4B.7, §8.2) is PostgreSQL. Eight constructs do not exist in MySQL 8 and need a defined replacement. **This section is normative** — the Flyway migrations must follow it.

### 3.1 Type and construct mapping

| Blueprint (PostgreSQL) | MySQL 8 equivalent | Notes |
|---|---|---|
| `BIGSERIAL PRIMARY KEY` | `BIGINT AUTO_INCREMENT PRIMARY KEY` | |
| `TIMESTAMPTZ` | `DATETIME(6)` | **Store UTC always.** MySQL's `TIMESTAMP` carries a 2038 limit and silent session-timezone conversion; `DATETIME` does neither. Set `spring.jackson.time-zone=UTC` and `connectionTimeZone=UTC` on the JDBC URL. Per-user display timezone (`users.timezone`, `clients.timezone`) is applied in the presentation layer only. |
| `VARCHAR(20)[]` (`workflow_stages.can_return_to`) | `JSON` | Validated against the stage-code enum in the service layer. Queried with `JSON_CONTAINS`. |
| `BIGINT[]` (`ticket_comments.mentioned_user_ids`) | `JSON` | Same. A `ticket_comment_mentions` child table is the alternative if we ever need to query "all comments mentioning me" efficiently — see §3.4. |
| Partial index `… WHERE actual_close_date IS NULL` | Stored generated column + plain index | MySQL has no partial indexes. See §3.3. |
| `UPDATE … RETURNING` (ticket ID generation) | `LAST_INSERT_ID()` idiom | See §3.2. |
| `CREATE FUNCTION … RAISE EXCEPTION` | `SIGNAL SQLSTATE '45000'` inside the trigger | MySQL has no shared trigger function; the body is repeated per trigger. See §3.5. |
| `CHAR(64)` for hashes | `CHAR(64)` with `COLLATE ascii_bin` | Hex SHA-256. `ascii_bin` avoids `utf8mb4` overhead and makes comparison exact. |
| Boolean | `TINYINT(1)` | Hibernate maps `boolean` to this natively. |
| `NUMERIC(p,s)` | `DECIMAL(p,s)` | Same semantics. |

Charset for every table: `CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci`, except hash columns as noted.

### 3.2 Ticket ID generation

The blueprint's §8.2 snippet uses `UPDATE … RETURNING`, which MySQL lacks. The correct MySQL idiom is a single atomic statement using the connection-local `LAST_INSERT_ID(expr)` form — it needs no explicit row lock and no read-then-write race:

```sql
-- statement 1: atomically increments and stashes the new value on this connection
UPDATE projects SET ticket_seq = LAST_INSERT_ID(ticket_seq + 1) WHERE id = ?;

-- statement 2: reads back this connection's value — unaffected by other sessions
SELECT LAST_INSERT_ID();
```

The service then formats `{project_code}-{YY}-{seq:05d}` → `CRM-26-00347`. Both statements run inside the ticket-creation transaction. **`COUNT(*)` must never be used** — the blueprint calls this out and it is worth repeating, because it breaks silently and only under concurrency.

Two edge cases the blueprint does not address, which we resolve here:

- **Year rollover.** `ticket_seq` is a per-project counter and does not reset in January, so `CRM-26-00347` is followed by `CRM-27-00348`, not `CRM-27-00001`. Sequence numbers stay globally unique per project and the year is descriptive only. This is the safer reading — resetting requires a composite counter and creates a collision window at midnight on 1 January.
- **Project code changes.** `projects.project_code` is immutable after the first ticket is created; the UI disables the field and the service rejects the change. Otherwise historical ticket codes stop resolving.

### 3.3 Partial indexes

The blueprint's hot index is:

```sql
CREATE INDEX ix_tickets_pcd_open ON tickets(planned_close_date) WHERE actual_close_date IS NULL;
```

This makes the 15-minute SLA scan `O(breaches)` rather than `O(all tickets)`. MySQL cannot express it. Replacement — a stored generated column that is `NULL` for closed tickets, indexed:

```sql
ALTER TABLE tickets
  ADD COLUMN pcd_open DATETIME(6)
    GENERATED ALWAYS AS (IF(actual_close_date IS NULL, planned_close_date, NULL)) STORED,
  ADD INDEX ix_tickets_pcd_open (pcd_open);
```

The scanner then queries `WHERE pcd_open < ? ` and the optimiser uses a range scan. InnoDB does index NULLs, so closed tickets still occupy index entries — but they cluster at one end and are never in the scanned range, which is the property we needed.

The same treatment applies to `ix_stage_current` (§4A.5, `WHERE is_current = TRUE`):

```sql
ALTER TABLE ticket_stage_transitions
  ADD COLUMN current_ticket_id BIGINT
    GENERATED ALWAYS AS (IF(is_current = 1, ticket_id, NULL)) STORED,
  ADD INDEX ix_stage_current (current_ticket_id);
```

### 3.4 `ONLY_FULL_GROUP_BY`

MySQL 8 enables `ONLY_FULL_GROUP_BY` by default, and **the blueprint's effort roll-up query in §4A.5 will not run under it** — it selects `t.iteration_no`, `t.to_stage`, `t.entered_at`, `t.exited_at`, `t.duration_mins` while grouping only by `t.id, u.full_name, u.role_code`. PostgreSQL permits this because `t.id` is a primary key and functionally determines the rest; MySQL's detection of functional dependency does not extend across the join here.

We do **not** disable the mode — it catches real bugs. The query is rewritten with every non-aggregated column in the `GROUP BY`:

```sql
SELECT t.iteration_no, t.to_stage AS stage, u.full_name, u.role_code,
       t.entered_at, t.exited_at, t.duration_mins, t.seq_no,
       COALESCE(SUM(e.hours), 0)                                   AS effort_hours,
       t.duration_mins / 60.0 - COALESCE(SUM(e.hours), 0)          AS idle_hours
FROM ticket_stage_transitions t
JOIN users u ON u.id = t.to_user_id
LEFT JOIN ticket_effort_logs e
       ON e.ticket_id    = t.ticket_id
      AND e.stage_code   = t.to_stage
      AND e.iteration_no = t.iteration_no
WHERE t.ticket_id = ? AND t.cycle_no = ?
GROUP BY t.id, t.iteration_no, t.to_stage, t.entered_at, t.exited_at,
         t.duration_mins, t.seq_no, u.full_name, u.role_code
ORDER BY t.seq_no;
```

A note on correctness that the original query also has: the `LEFT JOIN` to effort logs joins on `(ticket_id, stage_code, iteration_no)` but **not** on `cycle_no`. If a ticket is reopened and re-enters the same stage at the same iteration number in cycle 2, cycle 1's effort will be double-counted into cycle 2's row. We add `AND e.cycle_no = t.cycle_no` to the join condition. This is a real defect in the blueprint's query, not a MySQL artefact.

### 3.5 Immutability triggers

PostgreSQL's shared `block_mutation()` function has no MySQL analogue; each trigger carries its own body.

**Fully immutable tables** — `ticket_history`, `ticket_effort_logs`:

```sql
DELIMITER $$
CREATE TRIGGER trg_hist_no_update BEFORE UPDATE ON ticket_history
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'Immutable table: ticket_history rows cannot be updated';
END$$

CREATE TRIGGER trg_hist_no_delete BEFORE DELETE ON ticket_history
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000'
    SET MESSAGE_TEXT = 'Immutable table: ticket_history rows cannot be deleted';
END$$
DELIMITER ;
```

MySQL needs **two** triggers where PostgreSQL allowed `BEFORE UPDATE OR DELETE` in one. The same pair is created for `ticket_effort_logs`.

**Three limits of trigger-based protection, and how we close them:**

1. Triggers do not fire on `TRUNCATE TABLE`. → The application DB user is granted no `DROP` privilege (which `TRUNCATE` requires).
2. Triggers do not survive a `DROP TABLE`/recreate. → No DDL privileges for the application user; Flyway migrations run under a separate migration user in a deploy step.
3. `SUPER`/`TRIGGER`-privileged users can drop the triggers. → The hash chain (§3.6) is the backstop: tampering that bypasses triggers still breaks the chain and is detected by the nightly verifier.

### 3.6 The `exited_at` exception — deviation from the blueprint

`ticket_stage_transitions` is the one table with a legitimate single mutation: `exited_at` goes from `NULL` to a timestamp when the stage closes, and `duration_mins` is computed at the same moment.

The blueprint (§4A.5) creates only a `BEFORE DELETE` trigger on this table and says a `CHECK`/rule prevents changing a non-`NULL` `exited_at` — but does not specify it. That leaves every other column on the row updatable by any stray query or ORM dirty-check. Since stage duration is the exact number the whole ribbon exists to report, we tighten it: a `BEFORE UPDATE` trigger that permits the transition **only** when the row is still open and **only** those two columns changed.

```sql
DELIMITER $$
CREATE TRIGGER trg_stage_seal_only BEFORE UPDATE ON ticket_stage_transitions
FOR EACH ROW
BEGIN
  IF OLD.exited_at IS NOT NULL THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Stage transition already sealed; it cannot be modified';
  END IF;

  IF NOT (NEW.ticket_id    <=> OLD.ticket_id    AND NEW.cycle_no     <=> OLD.cycle_no
      AND NEW.iteration_no <=> OLD.iteration_no AND NEW.seq_no       <=> OLD.seq_no
      AND NEW.from_stage   <=> OLD.from_stage   AND NEW.to_stage     <=> OLD.to_stage
      AND NEW.from_user_id <=> OLD.from_user_id AND NEW.to_user_id   <=> OLD.to_user_id
      AND NEW.action_code  <=> OLD.action_code  AND NEW.handoff_note <=> OLD.handoff_note
      AND NEW.reason       <=> OLD.reason       AND NEW.entered_at   <=> OLD.entered_at
      AND NEW.prev_hash    <=> OLD.prev_hash    AND NEW.row_hash     <=> OLD.row_hash) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Only exited_at, duration_mins and is_current may be set on seal';
  END IF;
END$$
DELIMITER ;
```

(`<=>` is MySQL's NULL-safe equality — plain `=` would return `NULL` for two NULLs and silently pass the check.)

On the JPA side the entity is mapped `@Immutable` with the seal performed by an explicit JPQL update, so Hibernate never dirty-checks the row into an accidental full-column `UPDATE`.

### 3.7 Hash chaining under concurrency — deviation from the blueprint

The blueprint specifies `prev_hash` + `row_hash` forming a SHA-256 chain, verified nightly, but does not say how the chain stays linear when two writes to the same ticket race. Two concurrent inserts both reading the same "latest" row produce a **fork**: two rows with the same `prev_hash`, and the nightly verifier reports a break that is our own bug rather than tampering.

Resolution: **the chain is per-ticket, not global**, and every append takes a pessimistic lock on the parent ticket row first.

```
BEGIN
  SELECT id FROM tickets WHERE id = ? FOR UPDATE;        -- serialises appends for this ticket
  SELECT row_hash FROM ticket_history
    WHERE ticket_id = ? ORDER BY id DESC LIMIT 1;        -- prev_hash
  row_hash = SHA256(prev_hash ‖ canonical_json(payload))
  INSERT INTO ticket_history (...);
COMMIT
```

Per-ticket chaining also means the nightly verifier parallelises cleanly and a break is localised to one ticket rather than invalidating the whole table. `canonical_json` must be a fixed key order and a fixed timestamp format, or the verifier will not reproduce the hash — this is written once in `common` and covered by a golden-file test.

The same lock-and-append pattern is used by `ticket_effort_logs` and `ticket_stage_transitions`. Because all three chain off the ticket row lock, a single handoff writing to all three tables holds one lock, not three, and cannot deadlock against itself.

### 3.8 Full-text search

The blueprint's phase-1 plan is PostgreSQL full-text. MySQL's InnoDB `FULLTEXT` is weaker (no stemming beyond basic, no `tsvector` ranking control) but adequate for the specified use — global search over ticket code, title and description:

```sql
ALTER TABLE tickets ADD FULLTEXT INDEX ftx_tickets (title, description);
```

Ticket-code lookup (`CRM-26-00347`) goes through the unique index, not full text — that is the dominant search and must be exact and instant. If relevance quality becomes a complaint, OpenSearch is the phase-4 escape hatch the blueprint already anticipates.

`steps_to_generate` (§3.9) is deliberately **not** in this index. It is stored as HTML, and a full-text index over markup matches tag and attribute names as readily as prose — a search for `li` or `href` would return every ticket with a numbered list in it. If steps ever need searching, the index goes over a plain-text projection of the column, not the column.

### 3.9 Rich text — storage and sanitisation

Blueprint §7.5 asks for two rich-text fields on the ticket (Task Description, **Steps to Generate**) and §4B.5 a third in the comment box. **This section is normative for all three.**

| Decision | Rule |
|---|---|
| Storage | Sanitised **HTML**, not Markdown and not a JSON document model. It renders without a runtime, survives export to mail and PDF, and is what an editor already produces |
| Column type | `MEDIUMTEXT`. `TEXT` is 64 KB, which a few pasted screenshots as data URIs will exceed, and the failure mode is a silent truncation to invalid markup |
| Length | 20 000 characters, enforced by Bean Validation on the DTO so springdoc emits it into the contract and the frontend gets the same bound (D-4) |
| Sanitisation | **On the server, on write, always** — an allow-list sanitiser over tags and attributes. The client sanitises too, for what it renders, but that copy is advice: the only sanitiser an attacker cannot skip is the one on the write path |
| Allowed markup | `p, br, strong, em, u, s, ol, ul, li, code, pre, blockquote, a[href], img[src], h3, h4`. `href` and `src` restricted to `http`, `https` and `data:image/*`. Everything else — `script`, `style`, `iframe`, every `on*` handler, `javascript:` URLs — is stripped, not escaped |
| Rendering | Never `dangerouslySetInnerHTML` over unsanitised input, and never over input sanitised only at write time by an *older* version of the allow-list — render through the same sanitiser client-side, so tightening the list retroactively protects rows already stored |

The reason to be this specific: these fields are written by support desks quoting client email and pasting screenshots, and rendered to a manager who has every reason to trust the page. That is the exact shape of a stored-XSS vulnerability, and it is cheap to prevent on the way in and expensive to find later.

The `product_modules` master behind the Module field needs no translation beyond §3.1 (`INT GENERATED BY DEFAULT AS IDENTITY` → `INT AUTO_INCREMENT`). Its eight rows are **seed data, not enum constants** — per TEAM-PLAN §7.3 they belong in the stream's own seed file, and nothing in Java may hard-code the list.

---

## 4. Deviations from the blueprint

Every intentional difference, in one place.

| # | Blueprint | What we do instead | Why |
|---|---|---|---|
| D-1 | NestJS / Node backend | Spring Boot / Java | Directed by the team |
| D-2 | PostgreSQL 16 | MySQL 8.4 | Directed by the team; translation in §3 |
| D-3 | BullMQ, Socket.IO, SheetJS, Prisma | Outbox+ShedLock, STOMP, POI, JPA+Flyway | No Java equivalents; §2.2 |
| D-4 | Zod schemas shared across the stack | OpenAPI contract + generated TS client, CI staleness check | Cannot share runtime validation across languages; §2.2 |
| D-5 | `ticket_stage_transitions` protected by a DELETE trigger plus an unspecified rule | Explicit `BEFORE UPDATE` trigger permitting only the seal | The blueprint leaves every other column updatable; §3.6 |
| D-6 | Hash chain, concurrency unspecified | Per-ticket chain with `SELECT … FOR UPDATE` on the ticket | Concurrent appends would fork the chain and produce false tamper alerts; §3.7 |
| D-7 | Effort roll-up query as written in §4A.5 | Rewritten for `ONLY_FULL_GROUP_BY`, **plus `e.cycle_no = t.cycle_no` added to the join** | The original double-counts cycle-1 effort into cycle 2 after a reopen; §3.4 |
| D-8 | Ticket ID year behaviour unstated | Sequence does not reset at year rollover; `project_code` immutable after first ticket | Resetting creates a midnight collision window; §3.2 |
| D-9 | Phases 0–6 by calendar | Milestones M0–M7 by dependency | Each milestone is independently demoable and testable |
| D-12 | Errors wrapped in the `{ data, meta, error }` envelope | **RFC 9457 problem documents, `application/problem+json`** | Wrapping a problem document in `{ error: … }` while calling it `problem+json` contradicts the RFC, which defines the body *as* the problem object. Spring Boot 3 emits this shape natively from `ProblemDetail`, so conforming is free while the envelope version means an exception handler fighting the framework. Success responses keep `{ data, meta }` unchanged; see `contracts/CONVENTIONS.md` §3 |
| D-11 | `/clients/import/*` and `POST /users/bulk-import` — two URL shapes for one import engine | **`/imports/{schema}/{template,upload,validate,commit}` + `/import-batches/{batchId}`** | `/clients/import/{batchId}` is ambiguous with `/clients/{clientId}/…`: any three-segment path matches both. The parameterised form also states the design — §4B.3 builds the engine once and registers two schemas, which the blueprint's own URLs contradict |
| D-10 | — (this plan originally specified Java 21 / Spring Boot 3.3) | **Java 25 / Spring Boot 3.5** | Raised 6 Aug 2026 in `0518564`. Both move together: Spring Boot 3.3 supports Java 17–22, so 25 requires 3.5. `maven.compiler.release` is 25, making it the hard minimum for every developer; CI pins 25 and is the gate. Verified building and testing green on JDK 25 and 26 |
| D-14 | §4B.5: a comment is "editable for 5 minutes; after that the comment is locked" | **No time limit — the author may edit for as long as the comment exists.** Everything else in that row is unchanged: author-only, an `edited` marker, the first wording preserved in `original_body`, and a tombstone on deletion. `edited_at` is now sent to the client as well | Raised 17 Aug 2026 during C-033, by Divyansh, after driving the feature by hand. The five minutes lose to the case the edit is actually *for*: a developer posts a root-cause note, remembers the missing half thirty minutes later, and can only add a second card reading "correction to the above" — leaving the thread with two fragments to reconcile instead of one accurate note, which is worse for the reader than the edit would have been. **What §4B.5 protects is not the window**; its guarantee is the sentence beside it — "no role, including Admin, can silently rewrite a comment" — and every part of that still holds. The clock only ever decided *when* the author lost the ability, never whether the change was recorded. The limit remains implemented and tested behind `edutrack.comments.edit-window`, so setting it to `PT5M` restores the blueprint with no code change, no migration and no release. **Two costs, stated:** `original_body` holds only the *first* wording, so a comment revised repeatedly over months preserves the current and original text and loses everything between — a revision table is the fuller answer and is not built; and `Comment.editedAt` had to go on the wire, because with no window "edited" no longer implies "moments later" and a reader could not otherwise tell a typo fix from a rewrite three months on |
| D-15 | §12's heading is literally "UI / UX Design System — **Light Theme Only**", and CLAUDE.md repeats it | **A dark theme, opt-in.** A second definition of all 45 tokens under a `.dark` class on `<html>`, `darkMode: 'class'` in `tailwind.config.ts`, and a switch in the avatar menu. **Light is untouched** — every `:root` value is still the one C-002 measured, character for character, so no screen that works today can change; the dark values apply only under a class that is absent unless somebody opts in. **Manual only, no `prefers-color-scheme`**: following the OS *as well* would give one piece of state two inputs, and which won would depend on CSS rule order rather than on anything the user did | Requested 17 Aug 2026 by Shivendra. Not a correction of §12 — a product decision to add something §12 excluded, and recorded here because the blueprint is the authority on behaviour and this contradicts it in writing. **The dark values are derived, not invented**: each is the Tailwind shade one or two steps lighter than its light counterpart, which is where the light palette itself came from (`--success` #10B981 is emerald-500, so its dark form is emerald-400 and its `-text` form emerald-300), which keeps hue relationships — and so the chart series order and the level ramp — identical between themes. D-13's rule survives inverted: `-text` tokens remain the ones safe for small coloured text, but on a dark ground that means *lighter* than the base rather than darker; measured ratios are stated per group in `tokens.css`. **Two costs, stated:** `frontend/src/styles/tokens.css` and `tailwind.config.ts` are Stream C's files and their header calls them frozen after Sprint 0, so this **needs Divyansh's sign-off before it merges**; and **three screens across Streams B and D are not converted**, because they hardcode Tailwind's slate/white palette instead of tokens — `masters/calendar/WorkingCalendarPage.tsx` (26 occurrences), `notifications/PushOptIn.tsx` (8, including `text-slate-900`, which is near-invisible on a dark ground) and `masters/calendar/WeeklyOffPicker.tsx` (2). They will render light panels, or unreadable text, in a dark page until their owners replace the literals with tokens. That is a pre-existing departure from "never introduce a colour that isn't a token" which the dark theme makes *visible* rather than causes; it is left with its owners rather than fixed across three streams' paths in one PR |
| D-13 | §12.1's literal `success`/`warning`/`danger`/`info` and level-chip hex values, used directly as text colour | Added darkened `--success-text`/`--warning-text`/`--danger-text`/`--info-text` and `--level-*-text` tokens (`#047857`/`#B45309`/`#B91C1C`/`#1D4ED8`) for chip/badge labels and any small coloured text; the blueprint's literal hex are kept for icons, borders, chart series and large text | Raised 6 Aug 2026 during C-002. Measured WCAG contrast ratios: the blueprint's hex clear only the 3:1 large-text/UI-component threshold on white (e.g. `--success` 2.54:1, `--warning` 2.15:1, `--info` 3.68:1), not the 4.5:1 normal-text threshold that a 12–14px chip label needs. The darkened variants pass 4.5:1+ against both white and the corresponding `-soft` chip background; see `frontend/src/styles/tokens.css` |
| D-16 | §7.5's S-19 field table does not mark **Assigned To** mandatory, and §14 and §15 both build on that: an *Unassigned — new ticket unassigned > 2 h* escalation, a *New unassigned ticket* notification to PM and Support Desk, and C-015's Unassigned saved view | **The Create Ticket screen requires an assignee** on Save & Assign and Save & Create Another. Save as Draft waives it, the same way it already waives the description, the estimate and §4B.2's client rule. **The wire is unchanged** — `TicketCreateRequest.assigneeId` stays optional and `toCreateRequest` still sends `null` — so unassigned tickets remain a real state and the escalation, the notification and the Unassigned view all keep their jobs | Requested 24 Aug 2026 by Divyansh. The argument is §7.5's own argument for making Module mandatory on bug types — a ticket nobody owns is a ticket nobody routes — and the *> 2 h* escalation is the system paying for that omission two hours after the one moment somebody knew the answer and was looking at the field. Asking costs one dropdown that already shows each person's open load precisely so the assigner can see who is free. **Two costs, stated:** this is the *screen's* rule and not the server's, so D-036's email-to-ticket, B-053's Excel import and any direct API call still create unassigned tickets — which is deliberate, since nobody is standing in front of a form on those paths, but it does mean S-19 is not a guarantee about the table. A project that wants the guarantee ticks **ASSIGNEE** in B-019's Settings tab, which `ProjectSettingsGate` already enforces on every path *including* drafts. And Save as Draft is a one-click waiver, unchanged in kind from the three rules it already waives |

---

## 5. Decisions to lock before M2

§16 of the blueprint lists 13 governance questions. Nine can wait; **four change the schema or the core service contracts** and must be answered before the immutability core is built. The blueprint's own recommendation is proposed as the default in each case.

| # | Question | Proposed default | What it touches |
|---|---|---|---|
| G-1 | Is effort mandatory at handoff — blocking, or warn-only? | **Blocking**, with a per-project config flag defaulting to on | Handoff DTO validation, `S-29` dialog, "who skips effort" report |
| G-2 | Does a rework loop reset the Planned Close Date? | **No** — the original date stands; rework is what `iteration_no` measures | SLA scanner, breach reporting, `S-29` |
| G-3 | Can a Developer close a ticket, or only mark Resolved? | **Resolved only** — closure belongs to the Sign-off stage owner | `workflow_transitions` seed data, permission matrix, `S-23` |
| G-4 | Does an auto-escalated level revert after closure? | **No** — `original_level` is preserved and both are reported | Already in schema; needs the "born critical vs became critical" report |

The remaining nine (QA skipping, unassigned-stage ownership, comment default visibility, PCD edit rights, effort backdating window, timer vs self-report, client-contact inline creation, import upsert semantics, retention period) are configuration or UI decisions and are scheduled against M3–M6. All nine of the blueprint's recommendations are accepted as defaults unless overridden.

**Open question for the team, not answered in the blueprint:** is there an existing employee directory / SSO (Azure AD, Google Workspace) that the Resource Master should sync from, or is EduTrack the system of record for users? The blueprint puts SSO in phase 3, but if a directory already exists, `users` should carry an external ID from M1 rather than gaining one later.

---

## 6. Milestones

Sequenced by dependency, not calendar. Each milestone ends in something demoable and has explicit exit criteria.

### M0 — Foundation

Scaffold and schema. Nothing is demoable except a running stack, but everything after depends on this being right.

- Maven multi-module skeleton (`common`, `domain`, `api`, `worker`); Vite + React + TS + Tailwind frontend
- `docker-compose.yml`: MySQL 8.4, Redis 7, MinIO, Mailpit (SMTP capture for dev)
- **Complete Flyway migration set** for the whole model in one pass — blueprint §8.2 + §4A.5 + §4B.7, translated per §3. Building the schema piecemeal per feature is the main way an append-only model gets compromised.
- Immutability triggers (§3.5), generated columns and indexes (§3.3), full-text index (§3.8)
- Two DB users: `edutrack_app` (`SELECT, INSERT, UPDATE, DELETE`, no DDL, and **`INSERT, SELECT` only** on the three append-only tables) and `edutrack_migrate` (DDL, used only by the deploy step)
- Seed data: 6 roles + permission matrix, 11 task types, 4 priorities, statuses and the transition matrix, 3 workflow templates (Standard Dev Flow, Support Fast-Track, Infra Flow) with their stages
- Design tokens from §12.1 into `tokens.css` and `tailwind.config.ts`; Inter/Plus Jakarta Sans; the 4px spacing scale; radius and shadow scales
- CI: build → test → Testcontainers integration tests → OpenAPI client staleness check
- `CLAUDE.md` recording the conventions in §3 so they are not re-litigated per PR

**Exit:** `docker compose up` yields a migrated database with seed data; `mvn verify` is green; the React app renders a token-styled shell.

### M1 — Authentication and the scope guard

The security spine. Built before any business feature, because retrofitting a scope guard means re-auditing every query written before it.

- Login (§10.1) with Argon2id, generic error messages, `failed_attempts`, 15-minute lockout at 5, admin alert on lockout
- JWT access token (15 min, claims per §10.1: `sub`, `role`, `permissions[]`, `projects[]`, `reportees[]`, `jti`); opaque refresh token, 7 days, HttpOnly+Secure+SameSite=Strict cookie, `jti` in Redis, device-bound
- **Refresh rotation with family revocation** — reuse of a consumed refresh token revokes the entire family and forces re-login
- Logout: refresh `jti` deleted, access `jti` blacklisted in Redis until natural expiry. Idle timeout 30 min, absolute 12 h
- Forced password change on first login; forgot/reset with single-use 30-minute hashed tokens; password policy per §10.3 (no reuse of last 3)
- Screens **S-01 – S-04**
- **The three-layer guard chain**, in order:
  1. Spring Security filter chain — token valid and unrevoked
  2. `@PreAuthorize` method security — does the role hold `ticket.read`, `ticket.assign`, `master.write`
  3. **`DataScopeGuard` equivalent** — a `ScopeResolver` producing a JPA `Specification` per role (Admin → unrestricted; PM/Support → `project_id IN projects`; Developer/QA/Deployment → `assigned_to = me`), composed into every ticket query in a single central place
- `/tickets/{id}` returns **404, not 403**, for out-of-scope IDs — no existence leak
- **The permission test matrix ships with the guard, not after it**: a parameterised suite covering every role × every route, asserting allow/deny/404. §17 names scope leakage as the top risk; this suite is the mitigation
- **ArchUnit rule** forbidding direct `TicketRepository` calls from controllers, so scope cannot be bypassed by a future developer taking a shortcut

**Exit:** all six roles can log in and land on their role's home; the permission matrix suite passes; an out-of-scope ticket ID returns 404 for a Developer.

### M2 — Immutability core

- Append-only services for `ticket_history`, `ticket_effort_logs`, `ticket_stage_transitions` — **only `insert()` is exposed**; no update or delete method exists anywhere in the codebase
- Canonical JSON serialiser + SHA-256 chain, per-ticket, with the ticket-row lock (§3.7), covered by a golden-file test
- Compensating-entry pattern (`is_correction`, `corrects_entry_id`) — a correction is a new row, exactly as an accounting reversal
- Nightly chain verifier in `worker`, with an admin alert on break
- **Negative tests that attempt `UPDATE` and `DELETE` on each protected table and assert the SQL exception** — the triggers are the last line of defence and must be proven, not assumed
- Concurrency test: N parallel appends to one ticket produce a single unbroken chain

**Exit:** the chain verifier passes on a seeded dataset; every mutation attempt is rejected at the database; the concurrency test shows no fork.

### M3 — Master data

Screens **S-07 – S-16**, **S-32 – S-34**.

- Resource Master (S-07/S-08) with reporting-manager **cycle detection** (A→B→A blocked at any depth), unique emp code/username/email, per-project role assignment
- Role & Permission Master (S-09); system roles non-deletable
- Project Master (S-10) with Team, SLA and Settings tabs
- Task Type (S-11) and Priority (S-12) masters
- **Client Master (S-32/S-33)** with the four field groups and the `client_contacts` child grid; at least one primary contact required before the client is selectable on a ticket
- **Excel Import Wizard (S-34)** — built **once** as a schema-registered component and registered twice (clients, resources). Five steps: template download → upload → column mapping with saveable presets → **dry-run validation preview** → commit as a background job. Upsert on client code; `import_batch` row per run; downloadable error report with an appended Reason column
- Working Calendar & Holiday Master (S-14) — weekends, org holidays, per-resource leave. This feeds every SLA and utilisation calculation and is, as the blueprint notes, the most commonly missed requirement
- Notification Template Master (S-15) with merge tags
- Audit Log Viewer (S-16) — export only, never editable
- **Status, Stage & Workflow Template Master (S-13)** with the three tabs and the live ribbon preview; stages in use may be **deprecated, never deleted**

**Exit:** an Admin can stand up a complete tenant — resources, projects, clients (bulk-imported), calendar, workflow templates — without touching the database.

### M4 — Tickets and the Workflow Ribbon

The core product. Screens **S-17 – S-24**, **S-29 – S-31**.

- Ticket CRUD (S-19 create with every field group; S-17 list; S-20 detail) with the ID generation of §3.2
- **Where it happened** (§7.5, blueprint revision 1.3): the `product_modules` master and its eight seeded rows, plus `module_id`, `screen_name`, `feature` and `steps_to_generate` on `tickets`. Module mandatory on the form for bug-type task types only; all four columns nullable; `FIELD_CHANGED` history on every edit; module filter and optional column on S-17; rich text per §3.9
- Priority dropdown (§4B.1) with SLA resolution, planned-close-date preview before commit, mandatory reason once assigned, `LEVEL_CHANGED` history rows, `original_level` never overwritten
- Client and client-contact dependent dropdowns with inline contact creation (§4B.2)
- Attachments (§4B.4): drag-drop, picker, **and clipboard paste** — the blueprint is right that paste is what decides adoption; MIME sniffing plus extension allow-list, AV scan before visibility, EXIF stripping, S3/MinIO keys, signed URLs, 15-minute delete window then tombstone, `is_client_visible` flag
- Comment box (§4B.5): rich text, `@mentions`, attachments, internal/client-visible toggle **defaulting to internal**, 5-minute edit window then locked with an "edited" marker, stamped with stage and iteration, interleaved into the History tab as one chronological stream
- Cycles and the reopen transaction (§4.1) — seal cycle N, open N+1, `reopen_count++`, `actual_close_date` cleared, all in one transaction. Screen S-22
- Effort logging, append-only, auto-stamped with current stage and iteration
- Quick Update panel (S-21) — the daily driver; two clicks, no reload, optimistic UI
- **The ribbon** (§4A): transition service; handoff dialog (S-29) sealing the current row and inserting the next; backward moves requiring a reason and incrementing `iteration_no`; skip requiring PM/Admin plus reason; force-move logged as `OVERRIDE`
- **Golden rule enforcement:** only the current stage owner (plus PM and Admin) may advance a ticket. A Developer cannot push a ticket sitting with QA
- Ribbon component: 6 segment states, click-to-filter, hover tooltips, inline contextual action button, **compact dot variant for the list view**, keyboard navigation, ARIA labels, horizontal scroll with the current segment auto-centred (the §17 readability mitigation)
- Journey tab — the §4A.4 roll-up grid with per-resource and grand totals, and the **active vs idle** split
- Stage Queue / Team Inbox (S-31), sorted by time-in-stage descending — the landing page for QA and Deployment
- Bulk Reassignment Wizard (S-24), each move writing its own history entry
- Workflow Template Designer (S-30)

**Exit:** blueprint §14 walkthrough A runs end to end — a ticket through all 8 stages with a QA rework, a reopen into cycle 2, and a Journey grid reconciling to 38.0 h across 5 resources and 3 iterations.

### M5 — SLA, escalation and the mail engine

- SLA scanner every 15 minutes via `@Scheduled` + ShedLock: breach → level `CRITICAL`, `is_delayed`, alerts to Reporting Manager + PM + assignee; 80% pre-breach warning; stale-task nudge at 3 working days
- **Stage-SLA scanner** — separate from ticket SLA, per §16 item 3b. A ticket can be inside its PCD while rotting four days in the Deployment queue
- Escalation matrix per project (`sla_policies`), L1 → L2 after 48 h beyond PCD
- **All calculations on the working calendar** — a Friday-18:00 ticket with a 4-hour SLA must not breach on Saturday morning
- Mail engine (§4B.6): outbox worker, Thymeleaf templates from the S-15 master, merge tags, `Message-ID`/`In-Reply-To` threading so a ticket collapses into one Outlook/Gmail thread, `email_log` for every send, three retries with exponential backoff, bounce and complaint webhooks, per-recipient rate limit of one mail per ticket per minute
- **"Critical mails cannot be disabled"** — assignment, handoff, escalation and breach ignore user preferences; everything else respects them
- Notification Centre (S-26), preference matrix, WebSocket popups, bell badge, browser push; **notifications queued for offline users and delivered on next login**
- All 24 events of §11 wired

**Exit:** walkthrough A step 11 fires correctly — the scanner escalates at 00:15, the RM and PM receive mail, `email_log` proves delivery, and the ticket banner turns red live over WebSocket.

### M6 — Dashboard and reports

- **Pre-aggregated summary tables** (`daily_ticket_stats`, `resource_daily_stats`) refreshed by a 5-minute worker. Dashboard reads never issue live `COUNT(*)` over `tickets`
- Dashboard (S-05), role-aware, all 20 widgets, **every card and chart segment deep-linking to a pre-filtered list**
- Drill-down modal (S-06) with CSV export
- Developer dashboard variant — widgets 1–6, 9, 12 scoped to `assignee = me`
- Reports Hub (S-27), 18 reports, filters, chart + table, Excel/CSV/PDF export, email scheduling
- Resource 360° Profile (S-28)
- Cursor pagination, virtualised grids beyond 200 rows, `/tickets/{id}/full` as one aggregated endpoint

**Exit:** dashboard first paint under 1.5 s on a seeded 50,000-ticket dataset; every widget drill-down lands on a correctly filtered list.

### M7 — Chat, then hardening

- Chat (S-25): ticket thread, DM, project channel over STOMP; typing indicators, read receipts, mentions, file share, `TKT-xxxx` link preview as a rich ticket card
- **Ask Status** — structured message into the ticket thread, response time recorded as a reportable metric, "Awaiting response" list for managers
- Chat immutable after a 5-minute edit window; deletions leave tombstones, keeping chat admissible as project evidence
- Hardening: CSRF on cookie routes, Helmet-equivalent headers and strict CSP, dependency and container scanning, load test, index review against real query plans, penetration test, UAT, training, go-live runbook

**Exit:** security review passes; UAT signed off.

### Post-go-live (blueprint phase 6)

Client portal · email-to-ticket (IMAP poller + inbound webhook — the blueprint calls it the single biggest adoption lever for a support desk) · knowledge base · mobile PWA · SSO · Teams/Slack integration.

---

## 7. Screen → milestone map

| Milestone | Screens |
|---|---|
| M1 | S-01, S-02, S-03, S-04 |
| M3 | S-07 – S-16, S-32, S-33, S-34 |
| M4 | S-17 – S-24, S-29, S-30, S-31 |
| M5 | S-26 |
| M6 | S-05, S-06, S-27, S-28 |
| M7 | S-25 |

All 34 screens accounted for.

---

## 8. Testing strategy

Weighted toward the three risks §17 identifies as most expensive:

| Layer | Tooling | What it must cover |
|---|---|---|
| Unit | JUnit 5 + AssertJ | SLA maths on the working calendar, effort roll-ups, iteration/cycle counters, hash canonicalisation |
| Integration | Testcontainers (MySQL, Redis, MinIO) | Every migration applied against real MySQL; triggers proven by negative tests; the reopen and handoff transactions |
| **Permission matrix** | Parameterised MockMvc suite | **Every role × every route.** Ships with M1, grows with every new endpoint. A new route without a matrix entry fails the build |
| Architecture | ArchUnit | No controller reaches a repository directly; no update/delete method exists on append-only services |
| Concurrency | Custom harness | Parallel ticket creation (no duplicate codes); parallel history appends (no chain fork); parallel handoffs (no lost transitions) |
| Contract | springdoc + codegen diff | CI fails if the committed TS client is stale against the OpenAPI spec |
| E2E | Playwright | Walkthroughs A, B and C from §14, run against a seeded database |
| Performance | k6 | Dashboard and ticket-list p95 on a 50,000-ticket dataset |

---

## 9. Risks carried from the blueprint

§17's risk register applies unchanged, with four additions specific to this stack:

| Risk | Mitigation |
|---|---|
| MySQL trigger protection bypassed by a privileged user | Hash chain is the backstop; nightly verification; app user holds no DDL privilege |
| Generated-column indexes underperform PostgreSQL's partial indexes as data grows | Benchmark the SLA scan at M5 against a 500,000-row dataset; partition `tickets` by year if needed |
| OpenAPI client drift between Java and TypeScript | CI staleness check fails the build; Bean Validation is the single source of truth |
| Outbox worker falls behind under mail burst | `SELECT … FOR UPDATE SKIP LOCKED` allows horizontal worker scaling; `email_log` depth is a Prometheus alert |

---

## 10. What happens next

Immediate next step is **M0** — repo scaffold, Docker Compose, the complete Flyway migration set translated per §3, seed data, and the design tokens. That produces a running, migrated stack to inspect before any feature work begins.

Blocking on nothing. The four governance decisions in §5 are needed before **M2**, not before M0, and the SSO/directory question in §5 is needed before **M1**.
