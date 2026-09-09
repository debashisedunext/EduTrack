package com.edunext.edutrack.api.feature.onboarding.clients;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * B-103 · the SPOC rules, without a database.
 *
 * <p>What is worth a container is in {@code ObContactsIT}: the two unique
 * indexes, the CHECK that ties the consent triple together, and the triggers
 * that refuse to let a consent event be rewritten. Everything below is a
 * decision made in Java before any of that is reached — {@code
 * ObClientWriteServiceTest}'s own split.
 */
class ObContactServiceTest {

    private static final long CLIENT = 42L;
    private static final long CONTACT = 7L;
    private static final long CALLER = 3L;

    private static final Instant MARCH = Instant.parse("2026-03-04T09:00:00Z");
    private static final Instant NOVEMBER = Instant.parse("2026-11-18T15:30:00Z");

    private static final ObClientScope ADMIN = new ObClientScope(ObClientScope.OB_ADMIN, CALLER);
    private static final ObClientScope VIEWER = new ObClientScope(ObClientScope.OB_VIEWER, CALLER);

    private ObClientService details;
    private ObClientReadRepository reads;
    private ObContactWriteRepository contacts;
    private ObContactService service;

    @BeforeEach
    void setUp() {
        details = mock(ObClientService.class);
        reads = mock(ObClientReadRepository.class);
        contacts = mock(ObContactWriteRepository.class);
        service = new ObContactService(details, reads, contacts,
                Clock.fixed(NOVEMBER, ZoneOffset.UTC));

        when(details.findDetail(any(), anyLong())).thenReturn(Optional.of(detailStub()));
        when(reads.contactByEmail(anyLong(), any())).thenReturn(Optional.empty());
        when(contacts.insert(anyLong(), any(), any(), anyBoolean())).thenReturn(99L);
    }

    // ── who may write ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("standing")
    class Standing {

        /**
         * 404 before 403 and never the reverse. A client this caller cannot see
         * must be indistinguishable from one that does not exist, whatever
         * their role — otherwise the SPOC routes become a way to test client ids
         * that the client routes deny.
         */
        @Test
        void anOutOfScopeClientIs404EvenForAWriter() {
            when(details.findDetail(any(), eq(CLIENT))).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.add(ADMIN, CALLER, CLIENT, request()))
                    .isInstanceOf(ObClientNotFoundException.class);
            verifyNoInteractions(contacts);
        }

        /** A Viewer has been shown this client by OB-05, so 403 concedes nothing. */
        @Test
        void aViewerIs403OnTheAdd() {
            assertThatThrownBy(() -> service.add(VIEWER, CALLER, CLIENT, request()))
                    .isInstanceOf(ObClientReadOnlyException.class);
            verifyNoInteractions(contacts);
        }

        @Test
        void aViewerIs403OnTheRemove() {
            assertThatThrownBy(() -> service.remove(VIEWER, CLIENT, CONTACT))
                    .isInstanceOf(ObClientReadOnlyException.class);
            verifyNoInteractions(contacts);
        }

        /**
         * The role is decided before the contact id is resolved, so a Viewer
         * cannot use the 403/404 difference to learn which contact ids exist
         * under a client they can read.
         */
        @Test
        void aViewerLearnsNothingAboutWhichContactIdsExist() {
            assertThatThrownBy(() -> service.update(VIEWER, CALLER, CLIENT, 999L, request()))
                    .isInstanceOf(ObClientReadOnlyException.class);
            verifyNoInteractions(reads);
        }

        @Test
        void aContactUnderAnotherClientIs404() {
            when(reads.contactOf(CLIENT, CONTACT)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(ADMIN, CALLER, CLIENT, CONTACT, request()))
                    .isInstanceOf(ObContactNotFoundException.class);
        }
    }

    // ── consent ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("consent")
    class Consent {

        /**
         * The whole reason B-103 exists ahead of the feature that reads it. A
         * bare {@code true} is what V20260903_1210 already stored and what
         * cannot be repaired afterwards.
         */
        @Test
        void optingInWithNoBasisIsRefused() {
            assertThatThrownBy(() -> service.add(ADMIN, CALLER, CLIENT,
                    contact("spoc@example.com", true, null, true)))
                    .isInstanceOfSatisfying(ObClientValidationException.class,
                            e -> assertThat(e.errors()).containsKey("whatsappOptInSource"));
            verify(contacts, never()).insert(anyLong(), any(), any(), anyBoolean());
        }

        /** {@code UNRECORDED} is the backfill's value and no caller may claim it. */
        @Test
        void unrecordedIsNotAConsentBasisACallerMaySend() {
            assertThatThrownBy(() -> service.add(ADMIN, CALLER, CLIENT,
                    contact("spoc@example.com", true, "UNRECORDED", true)))
                    .isInstanceOf(ObClientValidationException.class);
        }

        /**
         * Refused rather than ignored: a form sending a basis beside a false has
         * come apart, and accepting both while storing neither is how somebody
         * later concludes consent was recorded when it was not.
         */
        @Test
        void aBasisWithNoConsentIsRefused() {
            assertThatThrownBy(() -> service.add(ADMIN, CALLER, CLIENT,
                    contact("spoc@example.com", false, "VERBAL", true)))
                    .isInstanceOf(ObClientValidationException.class);
        }

        @Test
        void addingWithConsentStampsItAndOpensTheJournal() {
            service.add(ADMIN, CALLER, CLIENT,
                    contact("spoc@example.com", true, "EMAIL", true));

            ArgumentCaptor<ObContactWriteRepository.Consent> consent =
                    ArgumentCaptor.forClass(ObContactWriteRepository.Consent.class);
            verify(contacts).insert(eq(CLIENT), any(), consent.capture(), eq(true));

            assertThat(consent.getValue().optedIn()).isTrue();
            assertThat(consent.getValue().source()).isEqualTo(ObConsentSource.EMAIL);
            assertThat(consent.getValue().at()).isEqualTo(NOVEMBER);
            assertThat(consent.getValue().by()).isEqualTo(CALLER);

            verify(contacts).recordConsent(99L, true, ObConsentSource.EMAIL, CALLER, NOVEMBER);
        }

        /**
         * A journal row per SPOC who never consented would bury the rows that
         * matter under the rows that are only the default.
         */
        @Test
        void addingWithoutConsentWritesNoJournalRow() {
            service.add(ADMIN, CALLER, CLIENT, request());

            verify(contacts, never()).recordConsent(anyLong(), anyBoolean(), any(), any(), any());
        }

        /**
         * <b>The assertion this task turns on.</b> Correcting a phone number in
         * November must not re-date a consent given in March — that destroys the
         * evidence that consent covered the messages sent in between, silently
         * and by a routine edit.
         */
        @Test
        void anUnrelatedEditDoesNotRestampTheConsent() {
            when(reads.contactOf(CLIENT, CONTACT)).thenReturn(Optional.of(
                    row("spoc@example.com", true, "VERBAL", MARCH, 11L, false, true)));

            service.update(ADMIN, CALLER, CLIENT, CONTACT,
                    contact("spoc@example.com", true, "VERBAL", false));

            ArgumentCaptor<ObContactWriteRepository.Consent> consent =
                    ArgumentCaptor.forClass(ObContactWriteRepository.Consent.class);
            verify(contacts).update(eq(CONTACT), any(), consent.capture(), eq(true));

            assertThat(consent.getValue().at()).isEqualTo(MARCH);
            // And the original attributor, which a wholesale UPDATE would
            // otherwise blank — the same loss arrived at by omission.
            assertThat(consent.getValue().by()).isEqualTo(11L);
            verify(contacts, never()).recordConsent(anyLong(), anyBoolean(), any(), any(), any());
        }

        /** A verbal consent upgraded to a written one is a change worth dating. */
        @Test
        void changingTheBasisIsANewConsentEvent() {
            when(reads.contactOf(CLIENT, CONTACT)).thenReturn(Optional.of(
                    row("spoc@example.com", true, "VERBAL", MARCH, 11L, false, true)));

            service.update(ADMIN, CALLER, CLIENT, CONTACT,
                    contact("spoc@example.com", true, "WRITTEN", false));

            verify(contacts).recordConsent(CONTACT, true, ObConsentSource.WRITTEN, CALLER, NOVEMBER);
        }

        /**
         * Withdrawal clears the row and keeps the journal. The row says where
         * consent stands; the journal is what shows it once stood.
         */
        @Test
        void withdrawingClearsTheStampAndRecordsTheWithdrawal() {
            when(reads.contactOf(CLIENT, CONTACT)).thenReturn(Optional.of(
                    row("spoc@example.com", true, "EMAIL", MARCH, 11L, false, true)));

            service.update(ADMIN, CALLER, CLIENT, CONTACT,
                    contact("spoc@example.com", false, null, false));

            ArgumentCaptor<ObContactWriteRepository.Consent> consent =
                    ArgumentCaptor.forClass(ObContactWriteRepository.Consent.class);
            verify(contacts).update(eq(CONTACT), any(), consent.capture(), eq(true));

            assertThat(consent.getValue().optedIn()).isFalse();
            assertThat(consent.getValue().at()).isNull();
            assertThat(consent.getValue().source()).isNull();
            verify(contacts).recordConsent(CONTACT, false, null, CALLER, NOVEMBER);
        }

        /**
         * A pre-capture row reads as a change the moment consent is recorded
         * properly, because no request can carry {@code UNRECORDED}. That is the
         * behaviour wanted: re-approaching those SPOCs and recording the answer
         * is the entire point of leaving the value visible.
         */
        @Test
        void recordingAProperBasisOverUnrecordedIsAConsentEvent() {
            when(reads.contactOf(CLIENT, CONTACT)).thenReturn(Optional.of(
                    row("spoc@example.com", true, "UNRECORDED", MARCH, null, false, true)));

            service.update(ADMIN, CALLER, CLIENT, CONTACT,
                    contact("spoc@example.com", true, "VERBAL", false));

            verify(contacts).recordConsent(CONTACT, true, ObConsentSource.VERBAL, CALLER, NOVEMBER);
        }
    }

    // ── the primary slot ────────────────────────────────────────────────────

    @Nested
    @DisplayName("the primary SPOC")
    class Primary {

        /** Demote the incumbent first, or {@code uq_ob_client_contacts_primary} refuses the write. */
        @Test
        void addingAPrimaryDemotesTheIncumbentFirst() {
            service.add(ADMIN, CALLER, CLIENT, contact("new@example.com", false, null, true));

            verify(contacts).demoteOtherPrimaries(CLIENT, null);
        }

        @Test
        void addingANonPrimaryDemotesNobody() {
            service.add(ADMIN, CALLER, CLIENT, request());

            verify(contacts, never()).demoteOtherPrimaries(anyLong(), any());
        }

        /**
         * Promoting excludes the row being promoted, so an idempotent re-save of
         * the existing primary does not demote and then re-promote itself.
         */
        @Test
        void promotingExcludesTheRowBeingPromoted() {
            when(reads.contactOf(CLIENT, CONTACT)).thenReturn(Optional.of(
                    row("spoc@example.com", false, null, null, null, false, true)));

            service.update(ADMIN, CALLER, CLIENT, CONTACT,
                    contact("spoc@example.com", false, null, true));

            verify(contacts).demoteOtherPrimaries(CLIENT, CONTACT);
        }

        /**
         * Where the ticketing master allows a client to have no primary, this
         * one refuses. See {@link LastPrimaryContactException} for why the
         * argument that holds over there does not hold here.
         */
        @Test
        void demotingTheOnlyPrimaryIsRefused() {
            when(reads.contactOf(CLIENT, CONTACT)).thenReturn(Optional.of(
                    row("spoc@example.com", false, null, null, null, true, true)));

            assertThatThrownBy(() -> service.update(ADMIN, CALLER, CLIENT, CONTACT,
                    contact("spoc@example.com", false, null, false)))
                    .isInstanceOf(LastPrimaryContactException.class);
            verify(contacts, never()).update(anyLong(), any(), any(), anyBoolean());
        }

        @Test
        void deactivatingThePrimaryIsRefused() {
            when(reads.contactOf(CLIENT, CONTACT)).thenReturn(Optional.of(
                    row("spoc@example.com", false, null, null, null, true, true)));

            assertThatThrownBy(() -> service.remove(ADMIN, CLIENT, CONTACT))
                    .isInstanceOf(LastPrimaryContactException.class);
            verify(contacts, never()).setActive(anyLong(), anyBoolean());
        }

        /**
         * The refusal is not a dead end, which is what lets it be this strict:
         * one request installs the replacement, and the old row is then an
         * ordinary contact.
         */
        @Test
        void thePrimaryCanBeRemovedOnceSomebodyElseHoldsTheSlot() {
            when(reads.contactOf(CLIENT, CONTACT)).thenReturn(Optional.of(
                    row("leaver@example.com", false, null, null, null, false, true)));

            service.remove(ADMIN, CLIENT, CONTACT);

            verify(contacts).setActive(CONTACT, false);
        }

        /**
         * The database would permit it — {@code is_primary_key} goes NULL for an
         * inactive row — and it is still a contradiction: the primary is who the
         * module sends to, and a removed contact is who it must not.
         */
        @Test
        void aRemovedContactCannotBeThePrimary() {
            when(reads.contactOf(CLIENT, CONTACT)).thenReturn(Optional.of(
                    row("spoc@example.com", false, null, null, null, false, true)));

            assertThatThrownBy(() -> service.update(ADMIN, CALLER, CLIENT, CONTACT,
                    contactInactive("spoc@example.com", true)))
                    .isInstanceOfSatisfying(ObClientValidationException.class,
                            e -> assertThat(e.errors()).containsKey("isPrimary"));
        }
    }

    // ── removal ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("removal")
    class Removal {

        /**
         * B-014's {@code UNCHANGED} argument: the second half of a double-click
         * must not be an error about something that did happen.
         */
        @Test
        void removingAnAlreadyRemovedContactIsNotAnError() {
            when(reads.contactOf(CLIENT, CONTACT)).thenReturn(Optional.of(
                    row("gone@example.com", false, null, null, null, false, false)));

            service.remove(ADMIN, CLIENT, CONTACT);

            verify(contacts, never()).setActive(anyLong(), anyBoolean());
        }
    }

    // ── the email guard ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("duplicate email")
    class Emails {

        @Test
        void aSecondContactWithTheSameEmailIsRefused() {
            when(reads.contactByEmail(CLIENT, "spoc@example.com")).thenReturn(Optional.of(
                    row("spoc@example.com", false, null, null, null, false, true)));

            assertThatThrownBy(() -> service.add(ADMIN, CALLER, CLIENT, request()))
                    .isInstanceOf(DuplicateContactEmailException.class);
        }

        /**
         * An inactive holder still holds the address, and the message says to
         * reactivate rather than add: a second id would split what they have
         * already signed off across two people.
         */
        @Test
        void anInactiveHolderStillHoldsTheAddress() {
            when(reads.contactByEmail(CLIENT, "spoc@example.com")).thenReturn(Optional.of(
                    row("spoc@example.com", false, null, null, null, false, false)));

            assertThatThrownBy(() -> service.add(ADMIN, CALLER, CLIENT, request()))
                    .isInstanceOf(DuplicateContactEmailException.class)
                    .hasMessageContaining("Reactivate");
        }

        /** A contact keeping its own address is not a duplicate of itself. */
        @Test
        void aContactMayKeepItsOwnEmail() {
            when(reads.contactOf(CLIENT, CONTACT)).thenReturn(Optional.of(
                    row("spoc@example.com", false, null, null, null, false, true)));
            when(reads.contactByEmail(CLIENT, "spoc@example.com")).thenReturn(Optional.of(
                    row("spoc@example.com", false, null, null, null, false, true)));

            service.update(ADMIN, CALLER, CLIENT, CONTACT,
                    contact("spoc@example.com", false, null, false));

            verify(contacts).update(eq(CONTACT), any(), any(), eq(true));
        }
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static ObContactDtos.ObContactUpsertRequest request() {
        return contact("spoc@example.com", false, null, false);
    }

    private static ObContactDtos.ObContactUpsertRequest contact(
            String email, boolean optIn, String source, boolean primary) {

        return new ObContactDtos.ObContactUpsertRequest(
                "SPOC", null, email, null, optIn, source, primary, null);
    }

    private static ObContactDtos.ObContactUpsertRequest contactInactive(String email, boolean primary) {
        return new ObContactDtos.ObContactUpsertRequest(
                "SPOC", null, email, null, false, null, primary, false);
    }

    /** {@code CONTACT} is the id, so a lookup by id and a lookup by email agree. */
    private static ObClientReadRepository.ContactRow row(String email, boolean optIn, String source,
                                                          Instant at, Long by, boolean primary,
                                                          boolean active) {
        return new ObClientReadRepository.ContactRow(
                CLIENT, CONTACT, "SPOC", null, email, null, optIn, at, source, by, primary, active);
    }

    private static ObClientDtos.ObClientDetail detailStub() {
        return new ObClientDtos.ObClientDetail(
                CLIENT, "Acme", LocalDate.of(2026, 9, 7), "ONBOARDING", null, "LOCKED", 1, 0,
                null, List.of(), null, null, null, false,
                null, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), null, null, null);
    }
}
