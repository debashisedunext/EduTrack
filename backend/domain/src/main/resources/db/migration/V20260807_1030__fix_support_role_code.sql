-- =====================================================================
-- Correction to B-001 · align the Support role code with the contract
--
-- V20260806_0900 seeded the Support role as 'SUPPORT_DESK'. Everything
-- else in the system calls it 'SUPPORT':
--
--   * contracts/openapi.yaml — RoleCode enum (D-001, agreed by all four
--     streams) is [ADMIN, PM, DEVELOPER, QA, DEPLOYMENT, SUPPORT]
--   * frontend/src/api/generated/model/roleCode.ts — generated from it
--   * frontend/src/mocks/db.ts and the MSW handlers
--   * blueprint §10.2's own guard samples: @Roles('ADMIN', 'PM',
--     'SUPPORT', …) and case 'SUPPORT': in the DataScopeGuard switch
--
-- Left as-is this breaks three things once real endpoints land:
--
--   1. A-022's JWT `role` claim is sourced from roles.code, so it would
--      emit 'SUPPORT_DESK' and fail the generated Zod enum client-side.
--   2. B-004's workflow stages carry an owner_role; the ribbon's golden
--      rule (§2) matches the current stage owner against the caller's
--      role code. The INTAKE stage is owned by SUPPORT — with the codes
--      diverged, no Support user would ever own their own intake stage.
--   3. A-033's @PreAuthorize role checks would silently deny.
--
-- The seed migration is NOT edited — Flyway has checksummed it and
-- other developers have applied it. This is the compensating migration,
-- per CLAUDE.md.
--
-- `roles` is ordinary master data, not one of the three append-only
-- tables, so UPDATE is legal here. role_permissions joins on roles.id,
-- which does not change, so no grant is disturbed.
--
-- `name` stays 'Support Desk' — that is the display label from the §2
-- matrix header. Only the machine-readable code moves.
-- =====================================================================

UPDATE roles
   SET code = 'SUPPORT'
 WHERE code = 'SUPPORT_DESK';
