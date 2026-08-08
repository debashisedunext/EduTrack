# db/migration

**Owner: Stream A · Shivendra**

Flyway migrations. Timestamp versioning: V20260812_1430__description.sql. NEVER edit an applied migration — Flyway checksums them.

Every migration in this directory is registered in [`SEED-MANIFEST.md`](SEED-MANIFEST.md) — the load order, in one reviewable place (B-008). Adding a migration means adding a row there; `SeedManifestTest` fails the build otherwise.
