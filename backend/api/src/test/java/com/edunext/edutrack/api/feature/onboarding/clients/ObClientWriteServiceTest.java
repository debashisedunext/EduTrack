package com.edunext.edutrack.api.feature.onboarding.clients;

import com.edunext.edutrack.domain.onboarding.ObClient;
import com.edunext.edutrack.domain.onboarding.ObClientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The rules that decide whether a client is added, edited or removed, without a
 * database.
 *
 * <p>What is worth a container is in {@code ObClientsIT} — the UNIQUE index
 * behind the code guard, the scope predicate in SQL, and the deletion guard's
 * four queries. Everything below is a decision made in Java before any of that
 * is reached, and a decision is cheaper to pin here than to seed a schema for.
 *
 * <h2>What left this file with the wizard</h2>
 *
 * <p>The SPOC, purchase and licence validations, the PAN guard, the
 * published-template check, the journey instantiation and the prerequisite
 * snapshot. Every one described an <em>engagement</em>, and engagements are
 * projects now — {@code ObProjectWriteServiceTest} is where they are pinned.
 * The name guard stayed, because a four-field add dialog is precisely the
 * screen on which somebody adds "Horizon Schools Trust" for the second time.
 */
class ObClientWriteServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 14);
    private static final Clock FIXED = Clock.fixed(
            TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
    private static final long CALLER = 7L;

    private static final ObClientScope ADMIN = new ObClientScope(ObClientScope.OB_ADMIN, CALLER);
    private static final ObClientScope SALES = new ObClientScope(ObClientScope.OB_SALES, CALLER);
    private static final ObClientScope VIEWER = new ObClientScope(ObClientScope.OB_VIEWER, CALLER);
    private static final ObClientScope OUTSIDER = new ObClientScope("TICKETING_MEMBER", CALLER);

    private ObClientRepository clients;
    private ObClientReadRepository reads;
    private ObClientDeletionGuard deletionGuard;
    private ObClientChildWriteRepository children;
    private ObClientService details;
    private ObContactService contacts;
    private ObClientPortalLoginIssuer portalLogins;
    private ObClientWriteService service;

    @BeforeEach
    void setUp() {
        clients = mock(ObClientRepository.class);
        reads = mock(ObClientReadRepository.class);
        deletionGuard = mock(ObClientDeletionGuard.class);
        children = mock(ObClientChildWriteRepository.class);
        details = mock(ObClientService.class);
        contacts = mock(ObContactService.class);
        portalLogins = mock(ObClientPortalLoginIssuer.class);
        service = new ObClientWriteService(clients, reads, deletionGuard, children, details,
                contacts, portalLogins, FIXED);

        when(children.isActiveUser(anyLong())).thenReturn(true);
        when(reads.namesContaining(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());
        when(clients.findByClientCode(anyString())).thenReturn(Optional.empty());
        when(deletionGuard.blockersFor(anyLong())).thenReturn(List.of());
        // The id is the database's, so a save has to hand one back — the read
        // that follows it is keyed by it.
        when(clients.saveAndFlush(any())).thenAnswer(i -> saved(i.getArgument(0), 42L));
        when(details.findDetail(any(), anyLong())).thenReturn(Optional.of(detailStub()));
    }

    // ── who may add a client ────────────────────────────────────────────────

    @Nested
    @DisplayName("standing")
    class Standing {

        /**
         * 404 rather than 403, and the assertion is on the exception type
         * because the type is what the handler turns into a status. See
         * {@link NotAnOnboardingClientWriterException} for the leak this
         * prevents.
         */
        @Test
        @DisplayName("a caller with no onboarding standing cannot add a client, and is told nothing")
        void noStanding() {
            assertThatThrownBy(() -> service.create(OUTSIDER, CALLER, request()))
                    .isInstanceOf(NotAnOnboardingClientWriterException.class);
            verifyNoInteractions(clients);
        }

        @Test
        @DisplayName("a Viewer may read every client and add none")
        void viewerCannotAdd() {
            assertThatThrownBy(() -> service.create(VIEWER, CALLER, request()))
                    .isInstanceOf(NotAnOnboardingClientWriterException.class);
        }

        @Test
        void salesMayAdd() {
            service.create(SALES, CALLER, request());
            verify(clients).saveAndFlush(any());
        }
    }

    // ── what the create stores, and what it no longer touches ───────────────

    @Nested
    @DisplayName("adding a client")
    class Adding {

        @Test
        @DisplayName("stores the four fields, trimmed")
        void storesTheFourFields() {
            service.create(ADMIN, CALLER, new ObClientDtos.ObClientCreateRequest(
                    "  Acme Schools  ", " ACM-001 ", "  11 Ridge Rd  ", "  Pune  ", false));

            org.mockito.ArgumentCaptor<ObClient> captor =
                    org.mockito.ArgumentCaptor.forClass(ObClient.class);
            verify(clients).saveAndFlush(captor.capture());

            ObClient stored = captor.getValue();
            assertThat(stored.getName()).isEqualTo("Acme Schools");
            assertThat(stored.getClientCode()).isEqualTo("ACM-001");
            assertThat(stored.getAddress()).isEqualTo("11 Ridge Rd");
            assertThat(stored.getCity()).isEqualTo("Pune");
        }

        /**
         * The wizard asked for this separately and routinely got a date a month
         * away from {@code created_at}. A company is boarded the day somebody
         * records it, which is what makes stamping it correct rather than merely
         * convenient.
         */
        @Test
        @DisplayName("stamps the boarding date rather than accepting one")
        void stampsTheBoardingDate() {
            service.create(ADMIN, CALLER, request());

            org.mockito.ArgumentCaptor<ObClient> captor =
                    org.mockito.ArgumentCaptor.forClass(ObClient.class);
            verify(clients).saveAndFlush(captor.capture());
            assertThat(captor.getValue().getOnboardingDate()).isEqualTo(TODAY);
        }

        @Test
        @DisplayName("a blank address or city is stored as null, not as an empty string")
        void blankOptionalsBecomeNull() {
            service.create(ADMIN, CALLER, new ObClientDtos.ObClientCreateRequest(
                    "Acme", "ACM-001", "   ", "", false));

            org.mockito.ArgumentCaptor<ObClient> captor =
                    org.mockito.ArgumentCaptor.forClass(ObClient.class);
            verify(clients).saveAndFlush(captor.capture());
            assertThat(captor.getValue().getAddress()).isNull();
            assertThat(captor.getValue().getCity()).isNull();
        }
    }

    // ── the two duplicate guards, and why they differ ───────────────────────

    @Nested
    @DisplayName("the code guard is exact and final")
    class CodeGuard {

        @Test
        @DisplayName("a second client with the same code is refused, on the code's own field")
        void refused() {
            when(clients.findByClientCode("ACM-001"))
                    .thenReturn(Optional.of(existing("Somebody Else", 999L)));

            assertThatThrownBy(() -> service.create(ADMIN, CALLER, request()))
                    .isInstanceOf(ObClientValidationException.class)
                    .satisfies(e -> assertThat(((ObClientValidationException) e).errors())
                            .containsKey("clientCode"));
            verify(clients, never()).saveAndFlush(any());
        }

        /**
         * Unlike the PAN guard it replaced, which named its match when the
         * caller's scope could already see it. The code is a value the caller
         * has just typed, so confirming it is taken discloses nothing — while
         * naming the holder would disclose a row outside their scope.
         */
        @Test
        @DisplayName("the client already holding the code is never named")
        void doesNotNameTheHolder() {
            when(clients.findByClientCode("ACM-001"))
                    .thenReturn(Optional.of(existing("Confidential Trust", 999L)));

            assertThatThrownBy(() -> service.create(ADMIN, CALLER, request()))
                    .isInstanceOf(ObClientValidationException.class)
                    .satisfies(e -> assertThat(((ObClientValidationException) e).errors()
                            .get("clientCode")).doesNotContain("Confidential Trust"));
        }

        @Test
        @DisplayName("a missing code is refused before anything is written")
        void codeIsRequired() {
            assertThatThrownBy(() -> service.create(ADMIN, CALLER,
                    new ObClientDtos.ObClientCreateRequest("Acme", "   ", null, null, false)))
                    .isInstanceOf(ObClientValidationException.class)
                    .satisfies(e -> assertThat(((ObClientValidationException) e).errors())
                            .containsKey("clientCode"));
            verify(clients, never()).saveAndFlush(any());
        }
    }

    @Nested
    @DisplayName("the name guard is fuzzy and forceable")
    class NameGuard {

        @Test
        @DisplayName("a similar existing name stops the create the first time")
        void warns() {
            when(reads.namesContaining(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(List.of(new ObClientReadRepository.NameRow(4L, "Acme Private Limited", CALLER)));

            assertThatThrownBy(() -> service.create(ADMIN, CALLER, named("Acme Pvt Ltd")))
                    .isInstanceOf(SimilarClientNameException.class)
                    .satisfies(e -> assertThat(((SimilarClientNameException) e).candidates())
                            .extracting(SimilarClientNameException.Candidate::name)
                            .containsExactly("Acme Private Limited"));
        }

        @Test
        @DisplayName("acknowledging it proceeds — the decision belongs to the person who can tell them apart")
        void forceable() {
            when(reads.namesContaining(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(List.of(new ObClientReadRepository.NameRow(4L, "Acme Private Limited", CALLER)));

            service.create(ADMIN, CALLER, new ObClientDtos.ObClientCreateRequest(
                    "Acme Pvt Ltd", "ACM-001", null, null, true));

            verify(clients).saveAndFlush(any());
        }

        @Test
        @DisplayName("a match the caller cannot see is counted, not named")
        void hiddenMatchesAreCounted() {
            when(reads.namesContaining(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(List.of(new ObClientReadRepository.NameRow(4L, "Acme Private Limited", 999L)));

            assertThatThrownBy(() -> service.create(SALES, CALLER, named("Acme Pvt Ltd")))
                    .isInstanceOf(SimilarClientNameException.class)
                    .satisfies(e -> {
                        assertThat(((SimilarClientNameException) e).candidates()).isEmpty();
                        assertThat(((SimilarClientNameException) e).hidden()).isEqualTo(1);
                    });
        }

        @Test
        @DisplayName("an unrelated name in the candidate set is not a warning")
        void unrelatedCandidatesAreScoredOut() {
            when(reads.namesContaining(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(List.of(new ObClientReadRepository.NameRow(4L, "Bluebell Schools", CALLER)));

            service.create(ADMIN, CALLER, named("Acme Pvt Ltd"));
            verify(clients).saveAndFlush(any());
        }

        /**
         * The code guard runs first, and that ordering is deliberate: a
         * duplicate code is final, so warning about a similar name and then
         * refusing the code anyway would cost the user two round trips to be
         * told the one thing that was always going to stop them.
         */
        @Test
        @DisplayName("a duplicate code is reported even when the name is also similar")
        void codeBeatsName() {
            when(clients.findByClientCode("ACM-001"))
                    .thenReturn(Optional.of(existing("Acme Private Limited", CALLER)));
            when(reads.namesContaining(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                    .thenReturn(List.of(new ObClientReadRepository.NameRow(4L, "Acme Private Limited", CALLER)));

            assertThatThrownBy(() -> service.create(ADMIN, CALLER, named("Acme Pvt Ltd")))
                    .isInstanceOf(ObClientValidationException.class);
        }
    }

    // ── editing ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("editing")
    class Updating {

        @Test
        @DisplayName("a client out of scope is 404 before anything about permission is considered")
        void outOfScopeIs404() {
            when(details.findDetail(any(), anyLong())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(SALES, 5L, new ObClientUpdateRequest()))
                    .isInstanceOf(ObClientNotFoundException.class);
        }

        @Test
        @DisplayName("a Viewer who can see the client is refused with 403, not 404")
        void viewerIsForbiddenNotHidden() {
            assertThatThrownBy(() -> service.update(VIEWER, 5L, new ObClientUpdateRequest()))
                    .isInstanceOf(ObClientReadOnlyException.class);
        }

        @Test
        @DisplayName("LIVE cannot be set — it is earned when every journey completes")
        void liveIsNotSettable() {
            when(clients.findById(5L)).thenReturn(Optional.of(existing("Acme", CALLER)));
            ObClientUpdateRequest request = new ObClientUpdateRequest();
            request.setStatus("LIVE");

            assertThatThrownBy(() -> service.update(ADMIN, 5L, request))
                    .isInstanceOf(LiveStatusNotEarnedException.class);
        }

        @Test
        @DisplayName("ON_HOLD without a reason is refused — nobody can explain it later")
        void holdNeedsAReason() {
            when(clients.findById(5L)).thenReturn(Optional.of(existing("Acme", CALLER)));
            ObClientUpdateRequest request = new ObClientUpdateRequest();
            request.setStatus("ON_HOLD");

            assertThatThrownBy(() -> service.update(ADMIN, 5L, request))
                    .isInstanceOf(ObClientValidationException.class)
                    .satisfies(e -> assertThat(((ObClientValidationException) e).errors())
                            .containsKey("statusReason"));
        }

        @Test
        @DisplayName("a status outside the enum is a 400, not a silently ignored field")
        void unknownStatus() {
            when(clients.findById(5L)).thenReturn(Optional.of(existing("Acme", CALLER)));
            ObClientUpdateRequest request = new ObClientUpdateRequest();
            request.setStatus("PAUSED");

            assertThatThrownBy(() -> service.update(ADMIN, 5L, request))
                    .isInstanceOf(ObClientValidationException.class)
                    .satisfies(e -> assertThat(((ObClientValidationException) e).errors())
                            .containsKey("status"));
        }

        /**
         * The reason a partial PATCH exists at all: an omitted field is not an
         * instruction to clear one.
         */
        @Test
        @DisplayName("a field the body did not mention is left alone")
        void absentFieldsAreNotCleared() {
            ObClient client = existing("Acme", CALLER);
            client.setAddress("12 Old Road");
            client.setCity("Pune");
            when(clients.findById(5L)).thenReturn(Optional.of(client));

            ObClientUpdateRequest onlyName = new ObClientUpdateRequest();
            onlyName.setName("Acme Renamed");
            service.update(ADMIN, 5L, onlyName);

            assertThat(client.getAddress()).isEqualTo("12 Old Road");
            assertThat(client.getCity()).isEqualTo("Pune");
            assertThat(client.getName()).isEqualTo("Acme Renamed");
        }

        @Test
        @DisplayName("an explicit null does clear one — which is the whole difference")
        void explicitNullClears() {
            ObClient client = existing("Acme", CALLER);
            client.setAddress("12 Old Road");
            when(clients.findById(5L)).thenReturn(Optional.of(client));

            ObClientUpdateRequest clearing = new ObClientUpdateRequest();
            clearing.setAddress(null);
            service.update(ADMIN, 5L, clearing);

            assertThat(client.getAddress()).isNull();
        }

        @Test
        @DisplayName("the code is correctable, unlike the PAN it replaced")
        void codeIsEditable() {
            ObClient client = existing("Acme", CALLER);
            client.setClientCode("ACM-001");
            when(clients.findById(5L)).thenReturn(Optional.of(client));

            ObClientUpdateRequest request = new ObClientUpdateRequest();
            request.setClientCode("ACM-002");
            service.update(ADMIN, 5L, request);

            assertThat(client.getClientCode()).isEqualTo("ACM-002");
        }

        /**
         * A client that has a code must not be able to lose it by clearing a
         * field — the whole point of the column is that every client added from
         * here on has one.
         */
        @Test
        @DisplayName("the code cannot be cleared")
        void codeIsNotClearable() {
            when(clients.findById(5L)).thenReturn(Optional.of(existing("Acme", CALLER)));

            ObClientUpdateRequest request = new ObClientUpdateRequest();
            request.setClientCode(null);

            assertThatThrownBy(() -> service.update(ADMIN, 5L, request))
                    .isInstanceOf(ObClientValidationException.class)
                    .satisfies(e -> assertThat(((ObClientValidationException) e).errors())
                            .containsKey("clientCode"));
        }

        /**
         * Re-saving an unchanged form must not be a conflict with the client's
         * own row, which is what the identity filter on the uniqueness check
         * buys.
         */
        @Test
        @DisplayName("re-saving the same code is not a conflict with itself")
        void ownCodeIsNotADuplicate() {
            ObClient client = saved(existing("Acme", CALLER), 5L);
            client.setClientCode("ACM-001");
            when(clients.findById(5L)).thenReturn(Optional.of(client));
            when(clients.findByClientCode("ACM-001")).thenReturn(Optional.of(client));

            ObClientUpdateRequest request = new ObClientUpdateRequest();
            request.setClientCode("ACM-001");
            service.update(ADMIN, 5L, request);

            assertThat(client.getClientCode()).isEqualTo("ACM-001");
        }

        @Test
        @DisplayName("another client's code is refused")
        void othersCodeIsADuplicate() {
            ObClient client = saved(existing("Acme", CALLER), 5L);
            when(clients.findById(5L)).thenReturn(Optional.of(client));
            when(clients.findByClientCode("OTH-001"))
                    .thenReturn(Optional.of(saved(existing("Other", CALLER), 9L)));

            ObClientUpdateRequest request = new ObClientUpdateRequest();
            request.setClientCode("OTH-001");

            assertThatThrownBy(() -> service.update(ADMIN, 5L, request))
                    .isInstanceOf(ObClientValidationException.class)
                    .satisfies(e -> assertThat(((ObClientValidationException) e).errors())
                            .containsKey("clientCode"));
        }
    }

    // ── deleting ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("deleting")
    class Deleting {

        @Test
        @DisplayName("a client nothing depends on is deleted")
        void deletesWhenNothingDependsOnIt() {
            service.delete(ADMIN, 5L);
            verify(clients).deleteById(5L);
        }

        /**
         * The refusal is what this method mostly exists to produce. Sixteen
         * tables carry {@code ob_client_id} and two of them are hash-chained,
         * so a delete that got past the guard would destroy an audit trail and
         * report nothing.
         */
        @Test
        @DisplayName("a client with anything depending on it is refused, and told what")
        void refusesWhenSomethingDependsOnIt() {
            when(deletionGuard.blockersFor(5L)).thenReturn(List.of("projects", "a client portal login"));

            assertThatThrownBy(() -> service.delete(ADMIN, 5L))
                    .isInstanceOf(ObClientInUseException.class)
                    .satisfies(e -> assertThat(((ObClientInUseException) e).blockers())
                            .containsExactly("projects", "a client portal login"));
            verify(clients, never()).deleteById(anyLong());
        }

        /**
         * 404 first, and the guard is never consulted — a caller who cannot see
         * this client must not learn from the refusal that it exists and has
         * projects.
         */
        @Test
        @DisplayName("a client out of scope is 404 before the guard is asked anything")
        void outOfScopeIs404() {
            when(details.findDetail(any(), anyLong())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.delete(SALES, 5L))
                    .isInstanceOf(ObClientNotFoundException.class);
            verifyNoInteractions(deletionGuard);
        }

        @Test
        @DisplayName("a Viewer who can see the client is refused with 403, not 404")
        void viewerIsForbiddenNotHidden() {
            assertThatThrownBy(() -> service.delete(VIEWER, 5L))
                    .isInstanceOf(ObClientReadOnlyException.class);
            verify(clients, never()).deleteById(anyLong());
        }
    }

    // ── the portal login the add dialog can issue ───────────────────────────

    @Nested
    @DisplayName("creating the portal login")
    class PortalLogin {

        @Test
        @DisplayName("the box unticked issues nothing, and asks for no contact")
        void untickedIssuesNothing() {
            service.create(ADMIN, CALLER, request());

            verifyNoInteractions(portalLogins);
            verifyNoInteractions(contacts);
        }

        /**
         * The SPOC before the login, which is the ordering the whole feature
         * rests on: {@code ClientAccountAdminService} reads the active primary
         * to build the username and to address the credential mail, and refuses
         * outright when there is none.
         */
        @Test
        @DisplayName("the box ticked adds the primary SPOC first, then issues the login")
        void tickedAddsTheSpocThenTheLogin() {
            when(portalLogins.issueFor(any(), anyLong(), any()))
                    .thenReturn(new ObClientPortalLoginIssuer.IssuedLogin("ACME.arjun", "Demo-Passw0rd!"));

            ObClientWriteService.Created created = service.create(ADMIN, CALLER, withLogin());

            org.mockito.InOrder order = org.mockito.Mockito.inOrder(contacts, portalLogins);
            order.verify(contacts).add(eq(ADMIN), eq(CALLER), eq(42L), any());
            order.verify(portalLogins).issueFor(ADMIN, 42L, CALLER);

            assertThat(created.login().username()).isEqualTo("ACME.arjun");
            assertThat(created.login().password()).isEqualTo("Demo-Passw0rd!");
        }

        /**
         * Primary and active are the service's to decide — see
         * {@code addPrimaryContactFor}. A SPOC created any other way is a
         * combination that fails one line later.
         */
        @Test
        @DisplayName("the SPOC is created primary and active, with consent withheld")
        void theSpocIsPrimaryActiveAndUnconsented() {
            service.create(ADMIN, CALLER, withLogin());

            org.mockito.ArgumentCaptor<ObContactDtos.ObContactUpsertRequest> captor =
                    org.mockito.ArgumentCaptor.forClass(ObContactDtos.ObContactUpsertRequest.class);
            verify(contacts).add(any(), anyLong(), anyLong(), captor.capture());

            ObContactDtos.ObContactUpsertRequest spoc = captor.getValue();
            assertThat(spoc.name()).isEqualTo("Arjun Singh");
            assertThat(spoc.email()).isEqualTo("arjun@acme.example");
            assertThat(spoc.primary()).isTrue();
            assertThat(spoc.activeOr(false)).isTrue();
            assertThat(spoc.optedIn()).isFalse();
        }

        @Test
        @DisplayName("the contact's name and email are trimmed on the way in")
        void contactIsTrimmed() {
            service.create(ADMIN, CALLER, new ObClientDtos.ObClientCreateRequest(
                    "Acme", "ACM-001", null, null, false,
                    true, "  Arjun Singh  ", "  arjun@acme.example  "));

            org.mockito.ArgumentCaptor<ObContactDtos.ObContactUpsertRequest> captor =
                    org.mockito.ArgumentCaptor.forClass(ObContactDtos.ObContactUpsertRequest.class);
            verify(contacts).add(any(), anyLong(), anyLong(), captor.capture());

            assertThat(captor.getValue().name()).isEqualTo("Arjun Singh");
            assertThat(captor.getValue().email()).isEqualTo("arjun@acme.example");
        }

        /**
         * Both fields at once, on {@link ObClientValidationException}'s own
         * rule: a form with two things wrong is returned once, not twice.
         */
        @Test
        @DisplayName("ticking the box without a contact names both missing fields, and writes nothing")
        void aLoginWithoutAContactIsRefused() {
            assertThatThrownBy(() -> service.create(ADMIN, CALLER,
                    new ObClientDtos.ObClientCreateRequest(
                            "Acme", "ACM-001", null, null, false, true, null, null)))
                    .isInstanceOf(ObClientValidationException.class)
                    .satisfies(e -> assertThat(((ObClientValidationException) e).errors())
                            .containsOnlyKeys("contactName", "contactEmail"));

            verify(clients, never()).saveAndFlush(any());
            verifyNoInteractions(portalLogins);
        }

        /**
         * The refusal the whole transaction exists for. B-102 refused
         * {@code createPortalLogin: true} outright rather than ignore it,
         * because "a boarder ticks the box, sees a 201, tells the client their
         * credentials are coming, and nothing was ever sent". Honouring the
         * flag brings that failure back unless the client goes with it.
         */
        @Test
        @DisplayName("a login that cannot be issued takes the client with it")
        void aFailedLoginDoesNotLeaveAClientBehind() {
            when(portalLogins.issueFor(any(), anyLong(), any()))
                    .thenThrow(new IllegalStateException("no free portal username"));

            assertThatThrownBy(() -> service.create(ADMIN, CALLER, withLogin()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static ObClientDtos.ObClientCreateRequest request() {
        return new ObClientDtos.ObClientCreateRequest("Acme", "ACM-001", null, null, false);
    }

    private static ObClientDtos.ObClientCreateRequest withLogin() {
        return new ObClientDtos.ObClientCreateRequest(
                "Acme", "ACM-001", null, null, false,
                true, "Arjun Singh", "arjun@acme.example");
    }

    private static ObClientDtos.ObClientCreateRequest named(String name) {
        return new ObClientDtos.ObClientCreateRequest(name, "ACM-001", null, null, false);
    }

    private static ObClient existing(String name, long createdBy) {
        return new ObClient(name, TODAY, createdBy);
    }

    /**
     * {@code id} is generated by the database, so a save has to hand one back.
     *
     * <p>Null-tolerant, because Mockito invokes a stubbed method once during
     * {@code when(...)} itself: re-stubbing {@code saveAndFlush} in a test calls
     * it with null, and the answer already registered runs on that call.
     */
    private static ObClient saved(ObClient client, long id) {
        if (client == null) {
            return null;
        }
        try {
            var field = ObClient.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(client, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return client;
    }

    private static ObClientDtos.ObClientDetail detailStub() {
        return new ObClientDtos.ObClientDetail(
                42L, "Acme", "ACM-001", "Pune", null, TODAY, "ONBOARDING", null, "LOCKED", 0, 0,
                null, List.of(), null, null, null, null, false,
                null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), null, Instant.EPOCH, null);
    }
}
