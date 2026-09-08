package com.edunext.edutrack.api.feature.onboarding.moduleaccess;

import com.edunext.edutrack.common.pagination.PageMeta;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

/**
 * A-117 · the wire shapes for OB-08, exactly as {@code ObModuleAccessGrant},
 * {@code ObModuleAccessListResponse}, {@code ObModuleAccessResponse} and
 * {@code ObModuleAccessGrantRequest} declare them.
 */
final class ObModuleAccessDtos {

    private ObModuleAccessDtos() {
    }

    /**
     * The contract's {@code UserRef}, cut to the three fields this screen has
     * a value for.
     *
     * <p>{@code avatarUrl}, {@code role} and {@code handle} are all optional in
     * the schema and none is on {@code users} in a form this query reads, so
     * they are omitted rather than serialised as nulls — {@code NON_NULL}
     * below. A generated client sees a field it declared optional as absent,
     * which is what optional means.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record UserRef(long id, String displayName) {
    }

    /**
     * One row of {@code user_module_access}, live or revoked.
     *
     * @param isLive         mirrors the generated {@code live_key}. Named
     *                       {@code isLive} because the contract's property is
     *                       {@code isLive}; a record component called
     *                       {@code live} would serialise as {@code live} and
     *                       silently break the generated client.
     * @param tokenLagSeconds the access-token lifetime. On every grant rather
     *                       than only on the revoke response, because the
     *                       contract puts it on the shared schema and OB-08
     *                       needs the same sentence after a grant — a new
     *                       grant is no more instant than a withdrawn one.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Grant(long id,
                 UserRef user,
                 String module,
                 String moduleRole,
                 UserRef grantedBy,
                 Instant grantedAt,
                 boolean isLive,
                 UserRef revokedBy,
                 Instant revokedAt,
                 int tokenLagSeconds) {
    }

    record GrantResponse(Grant data) {
    }

    record GrantListResponse(List<Grant> data, PageMeta meta) {
    }

    /**
     * {@code grantedBy} and {@code grantedAt} are deliberately absent: the
     * contract stamps both from the token. A body that could name its own
     * granter would make the audit trail worth nothing, since the one fact it
     * records is who did this.
     */
    record GrantRequest(@NotNull Long userId,
                        @NotBlank String module,
                        @NotBlank String moduleRole) {
    }
}
