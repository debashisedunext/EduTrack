package com.edunext.edutrack.api.security.pan;

import com.edunext.edutrack.api.feature.audit.PanRevealAudit;
import com.edunext.edutrack.api.security.CallerIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * A-113 · the audit guarantee.
 *
 * <p>The assertions that matter here are about <b>which</b> operations audit
 * and which do not. Both errors leave a working system: auditing masked reads
 * buries the deliberate reveals under list traffic, and not auditing reveals
 * leaves §11 unmet with nothing visibly wrong.
 */
class PanServiceTest {

    private static final String PAN = "AAAPL1234C";
    private static final long CLIENT_ID = 77L;
    private static final CallerIdentity CALLER =
            new CallerIdentity(9L, "OB_ADMIN", List.of());

    private final PanKeySource keys = new ConfiguredPanKeySource(new PanProperties(null, null));
    private final PanCipher cipher = new PanCipher(keys);
    private final PanRevealAudit audit = mock(PanRevealAudit.class);
    private final PanService service =
            new PanService(cipher, new PanBlindIndex(keys), audit);

    @Test
    @DisplayName("seal returns both column values together")
    void sealProducesBoth() {
        // One call, because writing one without the other is always a bug:
        // ciphertext without an index is invisible to the duplicate guard, an
        // index without ciphertext is a PAN that can be matched but never read.
        PanService.SealedPan sealed = service.seal(PAN);

        assertThat(sealed.ciphertext()).isNotEmpty();
        assertThat(sealed.blindIndex()).hasSize(32);
    }

    @Test
    @DisplayName("seal normalises, so an untidily typed PAN indexes identically")
    void sealNormalises() {
        assertThat(service.seal("  aaapl1234c ").blindIndex())
                .isEqualTo(service.seal(PAN).blindIndex());
    }

    @Test
    @DisplayName("seal refuses a value that is not a PAN")
    void sealValidates() {
        // Otherwise a typo becomes a permanent UNIQUE row that the corrected
        // entry then collides with.
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.seal("NOPE"))
                .withMessageContaining("Not a valid PAN");
    }

    @Test
    @DisplayName("seal writes no audit row — nothing was read")
    void sealDoesNotAudit() {
        service.seal(PAN);

        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("masked returns the last four only")
    void maskedShowsLastFour() {
        byte[] stored = service.seal(PAN).ciphertext();

        assertThat(service.masked(stored)).isEqualTo("••••••234C");
    }

    @Test
    @DisplayName("masked writes no audit row, however often it is called")
    void maskedDoesNotAudit() {
        byte[] stored = service.seal(PAN).ciphertext();

        service.masked(stored);
        service.masked(stored);
        service.masked(stored);

        // A PAN is decrypted in memory here, but no unmasked value leaves the
        // method — so there is nothing to disclose. Auditing it would file a
        // row for every client list ever rendered, and a log where routine
        // traffic outnumbers deliberate reveals by four orders of magnitude is
        // one nobody reads.
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("masked on a client with no PAN is absent, not bullets")
    void maskedTolerAtesNull() {
        assertThat(service.masked(null)).isNull();
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("reveal returns the PAN")
    void revealReturnsThePan() {
        byte[] stored = service.seal(PAN).ciphertext();

        assertThat(service.reveal(CLIENT_ID, CALLER, stored)).isEqualTo(PAN);
    }

    @Test
    @DisplayName("reveal writes exactly one audit row, against the client and the caller")
    void revealAudits() {
        byte[] stored = service.seal(PAN).ciphertext();

        service.reveal(CLIENT_ID, CALLER, stored);

        verify(audit).revealed(CLIENT_ID, CALLER);
    }

    @Test
    @DisplayName("a failed audit yields no PAN — the disclosure is the return, not the decryption")
    void aFailedAuditDisclosesNothing() {
        // Holds under either ordering, and that is the point: this is the
        // property the ordering was originally justified by, and it is actually
        // carried by the return being last rather than by the audit being
        // first. Kept because it is the guarantee callers rely on.
        PanRevealAudit failing = mock(PanRevealAudit.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("audit down"))
                .when(failing).revealed(anyLong(), any());
        PanService guarded = new PanService(cipher, new PanBlindIndex(keys), failing);
        byte[] stored = service.seal(PAN).ciphertext();

        assertThat(org.assertj.core.api.Assertions
                .catchThrowable(() -> guarded.reveal(CLIENT_ID, CALLER, stored)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit down");
    }

    @Test
    @DisplayName("a failed decryption records nothing — no PAN_REVEALED for a reveal that never happened")
    void aFailedDecryptionRecordsNothing() {
        // THIS is the assertion that discriminates. Audit-first passes every
        // other test in this class and fails only here: it would file a
        // PAN_REVEALED against a client whose PAN was never read, and a false
        // row in this particular log is indistinguishable from a real one to
        // the investigation that reads it, and undisprovable by the person it
        // names. Found by mutation, not by inspection.
        byte[] tampered = service.seal(PAN).ciphertext();
        tampered[tampered.length - 1] ^= 0x01;

        assertThat(org.assertj.core.api.Assertions
                .catchThrowable(() -> service.reveal(CLIENT_ID, CALLER, tampered)))
                .isInstanceOf(IllegalStateException.class);

        verify(audit, never()).revealed(anyLong(), any());
    }

    @Test
    @DisplayName("reveal on a client holding no PAN discloses nothing and records nothing")
    void revealOfAbsentPanIsNotAnEvent() {
        // A PAN_REVEALED row for a client with no PAN is a false positive in
        // the one report that must not have any.
        assertThat(service.reveal(CLIENT_ID, CALLER, null)).isNull();

        verify(audit, never()).revealed(anyLong(), any());
    }

    @Test
    @DisplayName("the reveal path is the only one that audits")
    void onlyRevealAudits() {
        byte[] stored = service.seal(PAN).ciphertext();
        service.masked(stored);
        service.blindIndexOf(PAN);
        service.reveal(CLIENT_ID, CALLER, stored);

        InOrder order = inOrder(audit);
        order.verify(audit).revealed(CLIENT_ID, CALLER);
        order.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("blindIndexOf matches what seal stored, so a lookup finds the row")
    void lookupMatchesStorage() {
        assertThat(service.blindIndexOf("aaapl1234c "))
                .isEqualTo(service.seal(PAN).blindIndex());
    }
}
