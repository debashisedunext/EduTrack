package com.edunext.edutrack.api.feature.masters.roles;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * B-015 · S-09 wire types, matching {@code contracts/openapi.yaml}.
 *
 * <p>Bean Validation on the request records is the single source of truth for
 * the field rules — the contract's {@code minLength}/{@code pattern} and these
 * annotations describe the same constraint, and only one of them can be the one
 * that actually runs.
 */
final class RoleDtos {

    private RoleDtos() {
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /**
     * One row of the matrix's capability axis.
     *
     * <p>{@code category} is the module grouping the screen renders sections
     * from — {@code admin}, {@code ticket}, {@code history}, {@code reports},
     * {@code master}, {@code audit}. <b>It is not the same "module" as
     * {@code /masters/modules}</b>, which is the product area a ticket is raised
     * against; blueprint §7 overloads the word and joining the two would be
     * wrong in a way that reads as correct.
     */
    record Permission(
            int id,
            String code,
            String name,
            String description,
            String category,
            boolean isGrantable) {
    }

    record PermissionListResponse(List<Permission> data) {
    }

    /**
     * A role as the S-09 grid renders it.
     *
     * @param userCount resources currently holding the role — the number the
     *                  delete refusal quotes, shown up front so an admin does
     *                  not discover it only by being refused
     */
    record Role(
            int id,
            String code,
            String name,
            String description,
            boolean isSystem,
            boolean isActive,
            long userCount,
            int permissionCount) {
    }

    record RoleListResponse(List<Role> data) {
    }

    /**
     * The matrix's read: the role, plus every capability it holds.
     *
     * <p>Flattened rather than nested because the contract declares it as
     * {@code allOf: [Role, {permissionCodes}]}, which generates a flat object —
     * a nested {@code role} field here would serialise to a shape the generated
     * client does not have a type for.
     */
    record RoleDetail(
            int id,
            String code,
            String name,
            String description,
            boolean isSystem,
            boolean isActive,
            long userCount,
            int permissionCount,
            List<String> permissionCodes) {
    }

    record RoleDetailResponse(RoleDetail data) {
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /**
     * {@code isSystem} is absent, and that is the guarantee rather than an
     * omission: nothing created through this API can claim to be one of the six
     * seeded roles that scope resolution and the §2 matrix are built on.
     */
    record RoleWrite(
            @NotBlank(message = "code is required")
            @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]{1,29}$",
                    message = "code must start with a letter and use only letters, digits and underscores")
            @Size(min = 2, max = 30, message = "code must be 2-30 characters")
            String code,

            @NotBlank(message = "name is required")
            @Size(max = 80, message = "name must be at most 80 characters")
            String name,

            @Size(max = 255, message = "description must be at most 255 characters")
            String description,

            Boolean isActive) {
    }

    /**
     * Every field optional; an omitted one keeps its stored value.
     *
     * <p><b>{@code code} is here only so that sending one can be refused.</b>
     * The alternative — leaving it off the record entirely — means Jackson
     * discards it silently and a caller who believed they renamed the role is
     * told the save succeeded. See {@code RoleService#update} for why a rename
     * cannot be honoured.
     */
    record RolePatch(
            String code,

            @Size(min = 1, max = 80, message = "name must be 1-80 characters")
            String name,

            @Size(max = 255, message = "description must be at most 255 characters")
            String description,

            Boolean isActive) {
    }

    /**
     * Replace-all. {@code permissionCodes} is the complete set the role should
     * hold afterwards, not a delta.
     *
     * <p>An empty list is valid and revokes everything. {@code @NotNull} rather
     * than {@code @NotEmpty} for exactly that reason: "revoke the lot" is a
     * legitimate save, and only a *missing* array is a malformed request.
     */
    record RolePermissionsWrite(
            @NotNull(message = "permissionCodes is required; send an empty array to revoke everything")
            List<String> permissionCodes) {
    }
}
