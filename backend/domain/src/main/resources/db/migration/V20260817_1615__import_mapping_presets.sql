-- =====================================================================
-- B-033 · S-34 step 3 — saved column mappings.
--
-- Blueprint §4B.3 step 3: "Mapping presets can be saved and reused for
-- the next import." A preset records how one particular export lines up
-- with our columns — a CRM's, a finance system's — so the next import
-- from the same source is a picker rather than twenty dropdowns.
--
-- A new table, touching nothing that exists. Not `tickets` and not one
-- of the three append-only tables, so CLAUDE.md asks for no Stream A
-- review; recorded here anyway because the file lives in their module.
-- =====================================================================
-- Why this is a table and not localStorage
-- ---------------------------------------------------------------------
-- The cheaper option was the browser's, and it was rejected on what the
-- feature is for. A preset is not a display preference: it is knowledge
-- about how another system's export is shaped, and the sentence in the
-- blueprint is "reused for the *next* import" — which is next month,
-- from a laptop that may have been reimaged, quite possibly by a
-- different Admin. A preset that cannot survive any of those three is a
-- preset that is not there the one time it was wanted.
--
-- Org-wide rather than per user, for the same reason: `created_by` is
-- recorded because knowing who saved a mapping is useful, but it is not
-- part of any key and nothing filters on it. Two Admins share one client
-- master; they should share the mapping onto it.
-- =====================================================================
-- Why the mapping is JSON
-- ---------------------------------------------------------------------
-- The same call `clients.tags` (V20260816_1030) and `users.skills`
-- (V20260811_1520) already made. The alternative is a child table of
-- (preset_id, target_field, source_column) rows, and nothing ever asks a
-- question across presets — no report groups by target field, no query
-- finds every preset touching `primaryEmail`. It is read whole, written
-- whole, and applied whole.
--
-- The CHECK constrains **shape, not vocabulary**: a JSON object, not an
-- array and not a scalar. Which target fields are legal is a property of
-- a Java class — `ImportSchemaDefinition.fields()` — and a database
-- CHECK listing them would be a second copy of a declaration that
-- changes when somebody edits a registration. The API refuses an unknown
-- field with a 422; this refuses a value that is not a mapping at all.
-- =====================================================================
-- Why `schema_key` is not a foreign key
-- ---------------------------------------------------------------------
-- There is no table of import schemas to point at. A schema is a Spring
-- `@Component` — that absence is B-030's whole design, and it is what
-- makes B-038 one file. So this column holds the registry key
-- (`clients`, `users`) and the API resolves it through
-- `ImportSchemaRegistry` before touching this table: a request for an
-- unregistered schema is a 404 from the registry and never reaches a
-- query. A preset left behind by a registration somebody deletes is
-- unreachable rather than dangling.
--
-- Deliberately the registry key and **not** `import_batches.entity`
-- (`CLIENT`/`RESOURCE`), which is the discriminator for stored rows.
-- `ImportSchemaDefinition` keeps those two separate on purpose — the URL
-- segment is public and the entity code is stored — and a preset is
-- keyed by the URL the screen was on.
-- =====================================================================

CREATE TABLE import_mapping_presets (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  -- The `ImportSchema` enum in the contract: `clients` | `users`.
  schema_key  VARCHAR(30)  NOT NULL,
  name        VARCHAR(80)  NOT NULL,
  -- Target field -> source column, the shape /validate and /commit take.
  mapping     JSON         NOT NULL,
  created_by  BIGINT       NULL,
  created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                                     ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  -- Save is an upsert on this key, which is the constraint that makes it
  -- one. Without it, correcting one column and pressing Save leaves two
  -- presets called "CRM export" and no way to tell which is current —
  -- and the picker would show both.
  --
  -- Case-insensitive through the table collation, so "CRM export" and
  -- "CRM Export" are one preset. Two entries a user cannot tell apart in
  -- a dropdown are worse than a replaced one.
  UNIQUE KEY uq_import_mapping_presets_name (schema_key, name),
  CONSTRAINT ck_import_mapping_presets_mapping
    CHECK (JSON_TYPE(mapping) = 'OBJECT'),
  -- ON DELETE SET NULL, not CASCADE: a deactivated Admin leaving takes
  -- their attribution with them, not the mapping the team still imports
  -- with every month.
  CONSTRAINT fk_import_mapping_presets_user
    FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL
) ENGINE = InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
