package com.edunext.edutrack.api.feature.onboarding.moduleaccess;

import com.edunext.edutrack.api.security.CallerIdentity;
import com.edunext.edutrack.api.security.jwt.JwtProperties;
import com.edunext.edutrack.common.pagination.Cursor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.edunext.edutrack.api.feature.onboarding.moduleaccess.ObModuleAccessExceptions.AlreadyRevokedException;
import static com.edunext.edutrack.api.feature.onboarding.moduleaccess.ObModuleAccessExceptions.DuplicateGrantException;
import static com.edunext.edutrack.api.feature.onboarding.moduleaccess.ObModuleAccessExceptions.GrantNotFoundException;
import static com.edunext.edutrack.api.feature.onboarding.moduleaccess.ObModuleAccessExceptions.GrantValidationException;
import static com.edunext.edutrack.api.feature.onboarding.moduleaccess.ObModuleAccessExceptions.LastAdminGrantException;
import static com.edunext.edutrack.api.feature.onboarding.moduleaccess.ObModuleAccessExceptions.NotAnOnboardingAdminException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A-117 · OB-08's rules.
 *
 * <p>The assertions that carry weight are the ones a working-looking
 * implementation can fail: that the Admin check runs on the <em>read</em> as
 * well as the writes, that the last-admin refusal happens <b>before</b> the
 * update rather than after, that the concurrent revoke answers the same 422 the
 * sequential one does, and that a revoked grant is still returned. Each is a
 * property no happy-path test would notice missing.
 */
class ObModuleAccessServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-08T09:15:30.500000Z");
    private static final long ADMIN_ID = 7L;

    private final ObModuleAccessRepository repository = mock(ObModuleAccessRepository.class);
    private final JwtProperties jwt =
            new JwtProperties("secret-that-is-long-enough-for-hmac-sha256!!", "edutrack", Duration.ofMinutes(15));
    private final ObModuleAccessService service =
            new ObModuleAccessService(repository, jwt, Clock.fixed(NOW, ZoneOffset.UTC));

    // --- callers ------------------------------------------------------------

    private static CallerIdentity caller(String moduleRole) {
        return new CallerIdentity(ADMIN_ID, "ADMIN", List.of(), List.of("ONBOARDING"),
                moduleRole == null ? Map.of() : Map.of("ONBOARDING", moduleRole));
    }

    private static CallerIdentity admin() {
        return caller("OB_ADMIN");
    }

    private static ObModuleAccessRepository.Row row(long id, String moduleRole, Instant revokedAt) {
        return new ObModuleAccessRepository.Row(id, 42L, "Priya Nair", "ONBOARDING", moduleRole,
                ADMIN_ID, "Ravi Kumar", NOW.minusSeconds(3600), revokedAt,
                revokedAt == null ? null : ADMIN_ID, revokedAt == null ? null : "Ravi Kumar");
    }

    private static ObModuleAccessDtos.GrantRequest request(String module, String moduleRole) {
        return new ObModuleAccessDtos.GrantRequest(42L, module, moduleRole);
    }

    // --- the Admin check ----------------------------------------------------

    @Test
    @DisplayName("a non-Admin is refused the LIST, not only the writes")
    void listIsAdminOnly() {
        // The one most likely to be left out. §3 makes Viewer "everything,
        // read-only", so a reader who copied that rule here would leave the
        // list open — handing every Viewer the roster of administrators, which
        // is the list worth attacking. The contract says Admin-only on all
        // three operations.
        for (String role : new String[]{"OB_MANAGER", "OB_VIEWER", "OB_SALES", "OB_STEP_OWNER", null}) {
            assertThatExceptionOfType(NotAnOnboardingAdminException.class)
                    .as("module role " + role)
                    .isThrownBy(() -> service.list(caller(role), null, null, null, false, null, null));
        }
        verify(repository, never()).search(any(), any(), any(), anyBoolean(), any(), anyInt());
    }

    @Test
    @DisplayName("a non-Admin is refused the grant and the revoke, and nothing is written")
    void writesAreAdminOnly() {
        assertThatExceptionOfType(NotAnOnboardingAdminException.class)
                .isThrownBy(() -> service.grant(caller("OB_MANAGER"), request("ONBOARDING", "OB_VIEWER")));
        assertThatExceptionOfType(NotAnOnboardingAdminException.class)
                .isThrownBy(() -> service.revoke(caller("OB_MANAGER"), 1L));

        verify(repository, never()).insert(anyLong(), anyString(), anyString(), anyLong(), any());
        verify(repository, never()).revoke(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("an ONBOARDING grant does not make a TICKETING_MEMBER an admin")
    void theRoleMustBeAdminExactly() {
        assertThatExceptionOfType(NotAnOnboardingAdminException.class)
                .isThrownBy(() -> service.list(caller("TICKETING_MEMBER"), null, null, null, false, null, null));
    }

    // --- the grant ----------------------------------------------------------

    @Test
    @DisplayName("a grant stamps the caller and the clock, never the body")
    void grantStampsTheCallerAndTheClock() {
        // grantedBy is the audit, and a body that could set it would make the
        // audit worth nothing. GrantRequest has no field for it; this asserts
        // what reaches the insert.
        when(repository.isActiveUser(42L)).thenReturn(true);
        when(repository.findLiveGrant(42L, "ONBOARDING")).thenReturn(Optional.empty());
        when(repository.insert(anyLong(), anyString(), anyString(), anyLong(), any())).thenReturn(99L);
        when(repository.findById(99L)).thenReturn(Optional.of(row(99L, "OB_VIEWER", null)));

        service.grant(admin(), request("ONBOARDING", "OB_VIEWER"));

        verify(repository).insert(42L, "ONBOARDING", "OB_VIEWER", ADMIN_ID, NOW);
    }

    @Test
    @DisplayName("a second live grant for the same module is refused BEFORE the insert")
    void duplicateIsRefusedBeforeInserting() {
        // uq_user_module_access_live would refuse it anyway. What this buys is
        // the message: without the check the caller gets a 500 naming a MySQL
        // index, which is the failure A-125 had to correct on the client
        // master. So the assertion is that nothing is inserted, not merely that
        // something is thrown.
        when(repository.isActiveUser(42L)).thenReturn(true);
        when(repository.findLiveGrant(42L, "ONBOARDING"))
                .thenReturn(Optional.of(row(5L, "OB_MANAGER", null)));

        assertThatExceptionOfType(DuplicateGrantException.class)
                .isThrownBy(() -> service.grant(admin(), request("ONBOARDING", "OB_VIEWER")))
                .withMessageContaining("revoke the existing grant first");

        verify(repository, never()).insert(anyLong(), anyString(), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("a revoked grant for the same module does not block a new one")
    void aRevokedGrantDoesNotBlockRegranting() {
        // The counterweight, and the one that would fail if findLiveGrant lost
        // its `revoked_at IS NULL`. Somebody whose access was withdrawn in
        // March must be grantable again in September — which is exactly what
        // the generated live_key column exists to allow.
        when(repository.isActiveUser(42L)).thenReturn(true);
        when(repository.findLiveGrant(42L, "ONBOARDING")).thenReturn(Optional.empty());
        when(repository.insert(anyLong(), anyString(), anyString(), anyLong(), any())).thenReturn(101L);
        when(repository.findById(101L)).thenReturn(Optional.of(row(101L, "OB_SALES", null)));

        assertThatNoException()
                .isThrownBy(() -> service.grant(admin(), request("ONBOARDING", "OB_SALES")));
    }

    @Test
    @DisplayName("an unknown module or role is a 400, and the user is never looked up")
    void closedVocabulariesAreChecked() {
        assertThatExceptionOfType(GrantValidationException.class)
                .isThrownBy(() -> service.grant(admin(), request("PAYROLL", "OB_VIEWER")));
        assertThatExceptionOfType(GrantValidationException.class)
                .isThrownBy(() -> service.grant(admin(), request("ONBOARDING", "SUPERUSER")));

        // ck_user_module_access_module_role would refuse both at the database.
        // Checked here so the answer is a field error the screen can render
        // rather than a constraint name.
        verify(repository, never()).isActiveUser(anyLong());
    }

    @Test
    @DisplayName("a lower-case module is normalised rather than silently matching nothing")
    void moduleIsCaseInsensitive() {
        when(repository.isActiveUser(42L)).thenReturn(true);
        when(repository.findLiveGrant(42L, "ONBOARDING")).thenReturn(Optional.empty());
        when(repository.insert(anyLong(), anyString(), anyString(), anyLong(), any())).thenReturn(7L);
        when(repository.findById(7L)).thenReturn(Optional.of(row(7L, "OB_VIEWER", null)));

        service.grant(admin(), request("onboarding", "ob_viewer"));

        verify(repository).insert(42L, "ONBOARDING", "OB_VIEWER", ADMIN_ID, NOW);
    }

    @Test
    @DisplayName("granting to an inactive or unknown user is refused")
    void theUserMustExist() {
        when(repository.isActiveUser(42L)).thenReturn(false);

        assertThatExceptionOfType(GrantValidationException.class)
                .isThrownBy(() -> service.grant(admin(), request("ONBOARDING", "OB_VIEWER")))
                .withMessageContaining("42");

        verify(repository, never()).insert(anyLong(), anyString(), anyString(), anyLong(), any());
    }

    // --- the revoke ---------------------------------------------------------

    @Test
    @DisplayName("revoking an unknown grant is 404")
    void revokingAnUnknownGrantIs404() {
        when(repository.findById(404L)).thenReturn(Optional.empty());

        assertThatExceptionOfType(GrantNotFoundException.class)
                .isThrownBy(() -> service.revoke(admin(), 404L));
    }

    @Test
    @DisplayName("revoking an already-revoked grant is 422 and does not rewrite the revoker")
    void revokingTwiceIsRefused() {
        // The second revoker's name must not overwrite the first's — that is
        // the fact the access audit is for.
        when(repository.findById(5L)).thenReturn(Optional.of(row(5L, "OB_VIEWER", NOW.minusSeconds(60))));

        assertThatExceptionOfType(AlreadyRevokedException.class)
                .isThrownBy(() -> service.revoke(admin(), 5L));

        verify(repository, never()).revoke(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("a concurrent revoke answers the same 422, not a silent success")
    void aLostRaceAnswersTheSame() {
        // The read said live, the update matched nothing because somebody else
        // revoked it in between. Without this the caller gets a 200 and a row
        // naming a different revoker than the one who just clicked.
        when(repository.findById(5L)).thenReturn(Optional.of(row(5L, "OB_VIEWER", null)));
        when(repository.revoke(eq(5L), anyLong(), any())).thenReturn(0);

        assertThatExceptionOfType(AlreadyRevokedException.class)
                .isThrownBy(() -> service.revoke(admin(), 5L));
    }

    @Test
    @DisplayName("the last live OB_ADMIN grant cannot be revoked")
    void theLastAdminIsProtected() {
        when(repository.findById(1L)).thenReturn(Optional.of(row(1L, "OB_ADMIN", null)));
        when(repository.countLiveAdminGrants()).thenReturn(1L);

        assertThatExceptionOfType(LastAdminGrantException.class)
                .isThrownBy(() -> service.revoke(admin(), 1L))
                .withMessageContaining("Grant OB_ADMIN to somebody else");
    }

    @Test
    @DisplayName("the last-admin check runs BEFORE the update, not after")
    void theLastAdminCheckPrecedesTheWrite() {
        // THE ORDERING ASSERTION. Checking afterwards would leave the module
        // with no administrator for the width of the transaction and force the
        // refusal to undo a write — and a compensating write over an audit
        // table is what A-109's whole design refuses to rely on. A rollback
        // makes it *nearly* equivalent, which is why this is asserted on the
        // call order rather than on the final state.
        when(repository.findById(1L)).thenReturn(Optional.of(row(1L, "OB_ADMIN", null)));
        when(repository.countLiveAdminGrants()).thenReturn(1L);

        assertThatExceptionOfType(LastAdminGrantException.class)
                .isThrownBy(() -> service.revoke(admin(), 1L));

        InOrder order = inOrder(repository);
        order.verify(repository).countLiveAdminGrants();
        verify(repository, never()).revoke(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("the second-to-last admin CAN be revoked")
    void theProtectionIsNotBlanket() {
        // The discriminating counterweight: a guard written as "OB_ADMIN grants
        // may never be revoked" passes every test above and is wrong. Two live
        // admins means this one may go.
        when(repository.findById(1L)).thenReturn(Optional.of(row(1L, "OB_ADMIN", null)));
        when(repository.countLiveAdminGrants()).thenReturn(2L);
        when(repository.revoke(eq(1L), anyLong(), any())).thenReturn(1);
        when(repository.findById(1L))
                .thenReturn(Optional.of(row(1L, "OB_ADMIN", null)), Optional.of(row(1L, "OB_ADMIN", NOW)));

        assertThatNoException().isThrownBy(() -> service.revoke(admin(), 1L));
        verify(repository).revoke(1L, ADMIN_ID, NOW);
    }

    @Test
    @DisplayName("the last-admin rule does not fire on a non-admin grant")
    void aViewerGrantIsNotAnAdminGrant() {
        // The count is only consulted for an OB_ADMIN row. Consulting it for
        // every revoke would make the last Viewer unrevokable the moment the
        // organisation was down to one administrator — a rule nobody wrote.
        when(repository.findById(9L)).thenReturn(
                Optional.of(row(9L, "OB_VIEWER", null)), Optional.of(row(9L, "OB_VIEWER", NOW)));
        when(repository.revoke(eq(9L), anyLong(), any())).thenReturn(1);

        assertThatNoException().isThrownBy(() -> service.revoke(admin(), 9L));
        verify(repository, never()).countLiveAdminGrants();
    }

    @Test
    @DisplayName("a revoke answers the revoked row, carrying revokedBy and the token lag")
    void theRevokeResponseCarriesTheAudit() {
        when(repository.findById(9L)).thenReturn(
                Optional.of(row(9L, "OB_VIEWER", null)), Optional.of(row(9L, "OB_VIEWER", NOW)));
        when(repository.revoke(eq(9L), anyLong(), any())).thenReturn(1);

        ObModuleAccessDtos.Grant grant = service.revoke(admin(), 9L);

        assertThat(grant.isLive()).isFalse();
        assertThat(grant.revokedAt()).isEqualTo(NOW);
        assertThat(grant.revokedBy()).isNotNull();
        // 15 minutes. On the wire so OB-08 can say the withdrawal is not
        // instant and offer to end the user's sessions instead, rather than
        // leave an admin to conclude the revoke failed.
        assertThat(grant.tokenLagSeconds()).isEqualTo(900);
    }

    // --- the list -----------------------------------------------------------

    @Test
    @DisplayName("the page reads one row more than asked and does not return it")
    void hasMoreComesFromTheOverRead() {
        when(repository.search(any(), any(), any(), anyBoolean(), any(), eq(3)))
                .thenReturn(rows(3));

        ObModuleAccessDtos.GrantListResponse page =
                service.list(admin(), null, null, null, false, null, 2);

        assertThat(page.data()).hasSize(2);
        assertThat(page.meta().hasMore()).isTrue();
        assertThat(page.meta().nextCursor()).isNotNull();
    }

    @Test
    @DisplayName("the last page has no cursor at all")
    void theLastPageIsTerminal() {
        when(repository.search(any(), any(), any(), anyBoolean(), any(), eq(3)))
                .thenReturn(rows(2));

        ObModuleAccessDtos.GrantListResponse page =
                service.list(admin(), null, null, null, false, null, 2);

        assertThat(page.data()).hasSize(2);
        assertThat(page.meta().hasMore()).isFalse();
        assertThat(page.meta().nextCursor()).isNull();
    }

    @Test
    @DisplayName("the cursor carries the last row's grantedAt AND its id")
    void theCursorIsATotalOrder() {
        // Without the id a page boundary landing between two grants sharing a
        // microsecond repeats or skips a row — and two grants made by one admin
        // clicking twice do share one.
        when(repository.search(any(), any(), any(), anyBoolean(), any(), eq(2)))
                .thenReturn(rows(2));

        ObModuleAccessDtos.GrantListResponse page =
                service.list(admin(), null, null, null, false, null, 1);

        Cursor decoded = Cursor.decode(page.meta().nextCursor());
        assertThat(decoded).isNotNull();
        assertThat(decoded.id()).isEqualTo(1L);
        assertThat(Instant.parse(decoded.sortKey())).isEqualTo(NOW.minusSeconds(3600));
    }

    @Test
    @DisplayName("limit is clamped, and absent means 25 rather than everything")
    void limitIsClamped() {
        when(repository.search(any(), any(), any(), anyBoolean(), any(), anyInt())).thenReturn(List.of());

        service.list(admin(), null, null, null, false, null, null);
        service.list(admin(), null, null, null, false, null, 5000);
        service.list(admin(), null, null, null, false, null, 0);

        // 26, 101, 26 — each one over the effective page size.
        verify(repository).search(any(), any(), any(), anyBoolean(), any(), eq(101));
        verify(repository, org.mockito.Mockito.times(2))
                .search(any(), any(), any(), anyBoolean(), any(), eq(26));
    }

    @Test
    @DisplayName("includeRevoked is passed through untouched, both ways")
    void includeRevokedReachesTheQuery() {
        // The audit switch. Defaulted off at the controller and never inverted
        // here — an off-by-one negation would turn the everyday screen into the
        // audit one and hide every live grant.
        when(repository.search(any(), any(), any(), anyBoolean(), any(), anyInt())).thenReturn(List.of());

        service.list(admin(), null, null, null, true, null, 10);
        verify(repository).search(null, null, null, true, null, 11);

        service.list(admin(), null, null, null, false, null, 10);
        verify(repository).search(null, null, null, false, null, 11);
    }

    private static List<ObModuleAccessRepository.Row> rows(int count) {
        List<ObModuleAccessRepository.Row> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(row(i + 1L, "OB_VIEWER", null));
        }
        return rows;
    }
}
