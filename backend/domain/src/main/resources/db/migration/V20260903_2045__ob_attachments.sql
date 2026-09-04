-- =====================================================================
-- A-102 · Client Onboarding — attachments
--
-- Tables: ob_attachments
--
-- Source:  docs/Onboarding-Module-Plan.md §4 ("`ob_attachments`
--          (polymorphic; `uploaded_by_type STAFF·CLIENT`, `kind
--          REFERENCE·SUBMISSION`)"), §1.1 item 7 (the per-step document
--          checklist), §11 (the attachment pipeline), blueprint §4B.4
--
-- POLYMORPHIC WITHIN THE MODULE, AND ONLY WITHIN IT.
--
-- §4 asks for one attachment table serving several owners: a client's own
-- documents, a prerequisite's reference docs and its submissions, a
-- service's deliverables, a sign-off's evidence. Rather than an
-- `owner_type`/`owner_id` pair — which no foreign key can constrain, so
-- the first typo points a row at nothing and nothing notices — this uses
-- **one nullable FK per owner kind plus a CHECK that exactly one is set**.
--
-- The cost is a column per owner and a wider CHECK. What it buys is that
-- every row's owner is a real row of a real table, enforced, and that
-- deleting an owner is refused rather than silently orphaning its files.
-- The ticketing side reached for the same shape in `ob_step_communications`
-- and `ob_notification_outbox`'s two-column author/recipient pairs; this is
-- that pattern with four arms instead of two.
--
-- **It is not polymorphic across modules.** Nothing here can point at a
-- `ticket_attachments` row or a ticketing client, and `ticket_attachments`
-- cannot point here. Plan §2's separability requirement, and the reason
-- this is `ob_`-prefixed rather than an extension of the existing table.
--
-- WHY NOT REUSE `ticket_attachments`
--
-- It carries `ticket_id NOT NULL` (A-006), so an onboarding file is
-- unrepresentable in it without making that column nullable — which would
-- weaken the ticketing schema to serve a module that plan §1.2 says must
-- not couple to it. The upload pipeline itself IS shared: MinIO keys, MIME
-- sniffing, the AV scan and EXIF stripping are C-024/C-025's and are not
-- rebuilt here. This is a second table on the same pipeline, not a second
-- pipeline.
--
-- WHO IS WAITING (docs/DEPENDENCIES.md):
--     B-107 client attachments · B-125 per-client prerequisite instances ·
--     C-104 step lifecycle (the document checklist gate) · C-121 CP-04's
--     upload · B-116 the acceptance PDF
-- =====================================================================

CREATE TABLE ob_attachments (
  id                  BIGINT        NOT NULL AUTO_INCREMENT,

  -- ── exactly one owner, enforced below ───────────────────────────────
  ob_client_id        BIGINT        NULL,
  step_id             BIGINT        NULL,
  -- The prerequisite tables are B-124/B-125's (ob_client_prereq_tasks),
  -- and they do not exist yet. The column is deliberately absent rather
  -- than declared without a foreign key: a nullable BIGINT pointing at
  -- nothing is exactly the unconstrained owner_id this design rejects.
  -- B-125 adds the column, its FK and its arm of the CHECK together.
  signoff_id          BIGINT        NULL,

  -- ── what the file is FOR, which is not the same as who owns it ──────
  -- §4's own vocabulary. REFERENCE is a document staff attach for the
  -- client to read — the admin's reference docs on a prerequisite, a
  -- template's blank form. SUBMISSION is what the client sends back.
  -- The distinction drives the portal: a client may replace their own
  -- SUBMISSION and may never touch a REFERENCE.
  kind                VARCHAR(12)   NOT NULL DEFAULT 'SUBMISSION',
                      -- REFERENCE|SUBMISSION|DELIVERABLE|EVIDENCE

  -- ── who uploaded it, and from which side of the portal ──────────────
  -- §4's `uploaded_by_type`. Two columns for the same reason
  -- ob_step_communications has two author columns: a client contact and a
  -- staff user live in different tables.
  uploaded_by_type    VARCHAR(10)   NOT NULL,   -- STAFF|CLIENT
  uploaded_by_user    BIGINT        NULL,
  uploaded_by_contact BIGINT        NULL,

  -- ── the file ────────────────────────────────────────────────────────
  file_name           VARCHAR(255)  NOT NULL,
  content_type        VARCHAR(120)  NOT NULL,
  size_bytes          BIGINT        NOT NULL,
  -- Object-storage key, never the bytes. Same shape as
  -- ticket_attachments.storage_key (A-006) because it is the same MinIO
  -- bucket and the same pipeline.
  storage_key         VARCHAR(400)  NOT NULL,
  -- SHA-256 of the content. Lets a re-upload of the same file be
  -- recognised rather than stored twice, and is what an integrity check
  -- would compare against.
  content_sha256      CHAR(64)      CHARACTER SET ascii COLLATE ascii_bin NULL,

  -- ── the AV pipeline (§11, blueprint §4B.4) ──────────────────────────
  -- PENDING until the scanner has looked at it. **Nothing may be served
  -- to anyone while this is PENDING or INFECTED** — that rule lives in
  -- the service, but the column is here so there is something for it to
  -- read, and so a file cannot be silently assumed clean.
  scan_status         VARCHAR(10)   NOT NULL DEFAULT 'PENDING',
                      -- PENDING|CLEAN|INFECTED|FAILED
  scanned_at          DATETIME(6)   NULL,

  -- Removed by deactivation, never by DELETE — a file referenced by a
  -- sign-off or a completed step is evidence, and the row has to keep
  -- resolving. Mirrors ticket_attachments' own tombstone (C-028).
  deleted_at          DATETIME(6)   NULL,
  deleted_by          BIGINT        NULL,

  created_at          DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at          DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                        ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  -- The four owner reads. Each excludes tombstoned rows at the service
  -- layer, so deleted_at trails the owner in each index.
  KEY ix_ob_attachments_client (ob_client_id, deleted_at),
  KEY ix_ob_attachments_step (step_id, kind, deleted_at),
  KEY ix_ob_attachments_signoff (signoff_id, deleted_at),
  KEY ix_ob_attachments_uploader_user (uploaded_by_user),
  KEY ix_ob_attachments_uploader_contact (uploaded_by_contact),
  KEY ix_ob_attachments_deleted_by (deleted_by),
  -- The AV worker's sweep: what has not been scanned yet.
  KEY ix_ob_attachments_scan (scan_status, created_at),
  -- Re-upload detection, per owner rather than globally: the same file
  -- legitimately appears under two clients.
  KEY ix_ob_attachments_sha (content_sha256),
  CONSTRAINT fk_ob_attachments_client
    FOREIGN KEY (ob_client_id) REFERENCES ob_clients (id),
  CONSTRAINT fk_ob_attachments_step
    FOREIGN KEY (step_id) REFERENCES ob_journey_steps (id),
  CONSTRAINT fk_ob_attachments_signoff
    FOREIGN KEY (signoff_id) REFERENCES ob_signoffs (id),
  CONSTRAINT fk_ob_attachments_uploader_user
    FOREIGN KEY (uploaded_by_user) REFERENCES users (id),
  CONSTRAINT fk_ob_attachments_uploader_contact
    FOREIGN KEY (uploaded_by_contact) REFERENCES ob_client_contacts (id),
  CONSTRAINT fk_ob_attachments_deleted_by
    FOREIGN KEY (deleted_by) REFERENCES users (id),
  CONSTRAINT ck_ob_attachments_kind
    CHECK (kind IN ('REFERENCE', 'SUBMISSION', 'DELIVERABLE', 'EVIDENCE')),
  CONSTRAINT ck_ob_attachments_scan_status
    CHECK (scan_status IN ('PENDING', 'CLEAN', 'INFECTED', 'FAILED')),
  CONSTRAINT ck_ob_attachments_size
    CHECK (size_bytes > 0),
  -- Exactly one owner. B-125 widens this to include its prerequisite-task
  -- column; until then a file belongs to a client, a service, or a
  -- sign-off, and never to two of them.
  CONSTRAINT ck_ob_attachments_one_owner
    CHECK (( ob_client_id IS NOT NULL AND step_id IS     NULL AND signoff_id IS     NULL)
        OR ( ob_client_id IS     NULL AND step_id IS NOT NULL AND signoff_id IS     NULL)
        OR ( ob_client_id IS     NULL AND step_id IS     NULL AND signoff_id IS NOT NULL)),
  -- Exactly one uploader, matching the side of the portal they came from.
  CONSTRAINT ck_ob_attachments_uploader_type
    CHECK (uploaded_by_type IN ('STAFF', 'CLIENT')),
  CONSTRAINT ck_ob_attachments_uploader
    CHECK ((uploaded_by_type = 'STAFF'  AND uploaded_by_user IS NOT NULL
                                        AND uploaded_by_contact IS NULL)
        OR (uploaded_by_type = 'CLIENT' AND uploaded_by_contact IS NOT NULL
                                        AND uploaded_by_user IS NULL)),
  -- A tombstone says who made it.
  CONSTRAINT ck_ob_attachments_deleted
    CHECK ((deleted_at IS NULL AND deleted_by IS NULL)
        OR (deleted_at IS NOT NULL AND deleted_by IS NOT NULL))
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
