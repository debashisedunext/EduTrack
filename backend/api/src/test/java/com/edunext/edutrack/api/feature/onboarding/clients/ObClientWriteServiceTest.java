package com.edunext.edutrack.api.feature.onboarding.clients;

import com.edunext.edutrack.api.feature.onboarding.instances.ObJourneyInstantiationService;
import com.edunext.edutrack.api.feature.onboarding.prereqs.ObClientPrereqService;
import com.edunext.edutrack.api.security.pan.PanService;
import com.edunext.edutrack.api.text.RichTextSanitizer;
import com.edunext.edutrack.domain.onboarding.ObClient;
import com.edunext.edutrack.domain.onboarding.ObClientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
 * B-102 · the rules that decide whether a client is boarded, without a
 * database.
 *
 * <p>What is worth a container is in {@code ObClientsIT} — the UNIQUE index
 * behind the PAN guard, the scope predicate in SQL, and the agreement between
 * the SQL and Java RAG formulas. Everything below is a decision made in Java
 * before any of that is reached, and a decision is cheaper to pin here than to
 * seed a schema for.
 */
class ObClientWriteServiceTest {

    private static final LocalDate BOARDED = LocalDate.of(2026, 9, 7);
    private static final long CALLER = 7L;

    private static final ObClientScope ADMIN = new ObClientScope(ObClientScope.OB_ADMIN, CALLER);
    private static final ObClientScope SALES = new ObClientScope(ObClientScope.OB_SALES, CALLER);
    private static final ObClientScope VIEWER = new ObClientScope(ObClientScope.OB_VIEWER, CALLER);
    private static final ObClientScope OUTSIDER = new ObClientScope("TICKETING_MEMBER", CALLER);

    private ObClientRepository clients;
    private ObClientReadRepository reads;
    private ObClientChildWriteRepository children;
    private ObRequirementWriteRepository requirements;
    private ObClientService details;
    private ObJourneyInstantiationService journeys;
    private ObClientPrereqService prereqs;
    private PanService pan;
    private com.edunext.edutrack.api.feature.portal.ClientAccountAdminService portalAccounts;
    private ObClientWriteService service;

    @BeforeEach
    void setUp() {
        clients = mock(ObClientRepository.class);
        reads = mock(ObClientReadRepository.class);
        children = mock(ObClientChildWriteRepository.class);
        requirements = mock(ObRequirementWriteRepository.class);
        details = mock(ObClientService.class);
        journeys = mock(ObJourneyInstantiationService.class);
        prereqs = mock(ObClientPrereqService.class);
        pan = mock(PanService.class);
        // B-106 · ObRequirementBody is real rather than mocked. It is the
        // §3.9 allow-list, and a mock of it would make every assertion in this
        // class about what the wizard stores an assertion about a stub —
        // including the one that says a body reducing to nothing is refused.
        portalAccounts = mock(com.edunext.edutrack.api.feature.portal.ClientAccountAdminService.class);
        service = new ObClientWriteService(clients, reads, children, requirements,
                new ObRequirementBody(new RichTextSanitizer()), details, journeys, prereqs, pan,
                portalAccounts);
                new ObRequirementBody(new RichTextSanitizer()), details, journeys, pan, portalAccounts);
                new ObRequirementBody(new RichTextSanitizer()), details, journeys, prereqs, pan);

        // The happy defaults: one product, on sale, with a published template,
        // and a published prerequisites master (B-109).
        when(children.sellableProductIds(any())).thenReturn(Set.of(1L));
        when(children.productIdsWithActiveTemplate(any())).thenReturn(Set.of(1L));
        when(children.isActiveUser(anyLong())).thenReturn(true);
        when(prereqs.hasActivePrereqMaster()).thenReturn(true);
        when(reads.namesContaining(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());
        // The id is the database's, so a save has to hand one back — everything
        // after it (the child rows, the journeys) is keyed by it.
        when(clients.saveAndFlush(any())).thenAnswer(invocation -> saved(invocation.getArgument(0), 42L));
        when(details.findDetail(any(), anyLong())).thenReturn(Optional.of(detailStub()));
    }

    // ── who may board a client ──────────────────────────────────────────────

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
        @DisplayName("a caller with no onboarding standing cannot board a client, and is told nothing")
        void noStanding() {
            assertThatThrownBy(() -> service.create(OUTSIDER, CALLER, request()))
                    .isInstanceOf(NotAnOnboardingClientWriterException.class);
            verifyNoInteractions(clients);
        }

        @Test
        @DisplayName("a Viewer may read every client and board none")
        void viewerCannotBoard() {
            assertThatThrownBy(() -> service.create(VIEWER, CALLER, request()))
                    .isInstanceOf(NotAnOnboardingClientWriterException.class);
        }

        @Test
        void salesMayBoard() {
            service.create(SALES, CALLER, request());
            verify(clients).saveAndFlush(any());
        }
    }

    // ── the portal-login flag ───────────────────────────────────────────────

    /**
     * B-126 · the flag is honoured now. It was refused rather than ignored
     * until this task existed, and B-102's argument for refusing is the same
     * one that makes this assertion worth keeping: a boarder who ticks the box
     * and is told 201 believes their client has credentials coming.
     */
    @Test
    @DisplayName("asking for a portal login creates one")
    void portalLoginIsCreated() {
        ObClientDtos.ObClientCreateRequest wantsLogin = new ObClientDtos.ObClientCreateRequest(
                "Acme", null, BOARDED, null, null, null, null,
                List.of(contact("spoc@example.com", true)),
                List.of(new ObClientDtos.ObApplicationWriteRequest(1L, null, null, null, null)),
                null, true, false);

        service.create(ADMIN, CALLER, wantsLogin);

        verify(portalAccounts).create(eq(ADMIN), anyLong(), eq(CALLER));
    }

    @Test
    @DisplayName("not asking for one creates nothing — the checkbox is the whole trigger")
    void noPortalLoginWhenNotAsked() {
        ObClientDtos.ObClientCreateRequest noLogin = new ObClientDtos.ObClientCreateRequest(
                "Acme", null, BOARDED, null, null, null, null,
                List.of(contact("spoc@example.com", true)),
                List.of(new ObClientDtos.ObApplicationWriteRequest(1L, null, null, null, null)),
                null, false, false);

        service.create(ADMIN, CALLER, noLogin);

        verify(portalAccounts, never()).create(any(), anyLong(), any());
    }

    // ── the create's own validation set ─────────────────────────────────────

    @Nested
    @DisplayName("validation, collected rather than thrown at the first failure")
    class Validation {

        @Test
        @DisplayName("no primary SPOC — a client nothing can be sent to")
        void noPrimary() {
            assertThatThrownBy(() -> service.create(ADMIN, CALLER,
                    request(List.of(contact("a@example.com", false)))))
                    .isInstanceOf(ObClientValidationException.class)
                    .satisfies(e -> assertThat(((ObClientValidationException) e).errors())
                            .containsKey("contacts"));
        }

        @Test
        @DisplayName("two primaries — the portal credential would go to both")
        void twoPrimaries() {
            assertThatThrownBy(() -> service.create(ADMIN, CALLER, request(List.of(
                    contact("a@example.com", true), contact("b@example.com", true)))))
                    .isInstanceOf(ObClientValidationException.class);
        }

        @Test
        @DisplayName("the same email twice at one client — one person, one row")
        void duplicateEmail() {
            assertThatThrownBy(() -> service.create(ADMIN, CALLER, request(List.of(
                    contact("a@example.com", true), contact("A@Example.com", false)))))
                    .isInstanceOf(ObClientValidationException.class)
                    .satisfies(e -> assertThat(((ObClientValidationException) e).errors())
                            .containsKey("contacts"));
        }

        @Test
        @DisplayName("one product selected twice would mean two journeys for one product")
        void duplicateProduct() {
            ObClientDtos.ObClientCreateRequest twice = new ObClientDtos.ObClientCreateRequest(
                    "Acme", null, BOARDED, null, null, null, null,
                    List.of(contact("spoc@example.com", true)),
                    List.of(new ObClientDtos.ObApplicationWriteRequest(1L, null, null, null, null),
                            new ObClientDtos.ObApplicationWriteRequest(1L, null, null, null, null)),
                    null, false, false);

            assertThatThrownBy(() -> service.create(ADMIN, CALLER, twice))
                    .isInstanceOf(ObClientValidationException.class)
                    .satisfies(e -> assertThat(((ObClientValidationException) e).errors())
                            .containsKey("applications"));
        }

        @Test
        @DisplayName("a licence that ends before it starts")
        void backwardsLicence() {
            ObClientDtos.ObClientCreateRequest backwards = new ObClientDtos.ObClientCreateRequest(
                    "Acme", null, BOARDED, null, null, null, null,
                    List.of(contact("spoc@example.com", true)),
                    List.of(new ObClientDtos.ObApplicationWriteRequest(
                            1L, null, null, BOARDED, BOARDED.minusDays(1))),
                    null, false, false);

            assertThatThrownBy(() -> service.create(ADMIN, CALLER, backwards))
                    .isInstanceOf(ObClientValidationException.class);
        }

        @Test
        @DisplayName("a retired product is out of the picker by definition")
        void retiredProduct() {
            when(children.sellableProductIds(any())).thenReturn(Set.of());

            assertThatThrownBy(() -> service.create(ADMIN, CALLER, request()))
                    .isInstanceOf(ObClientValidationException.class)
                    .satisfies(e -> assertThat(((ObClientValidationException) e).errors())
                            .containsKey("applications"));
        }

        @Test
        @DisplayName("a departed sales person points every report at a mailbox nobody reads")
        void inactiveSalesPerson() {
            when(children.isActiveUser(99L)).thenReturn(false);
            ObClientDtos.ObClientCreateRequest named = new ObClientDtos.ObClientCreateRequest(
                    "Acme", null, BOARDED, null, null, 99L, null,
                    List.of(contact("spoc@example.com", true)),
                    List.of(new ObClientDtos.ObApplicationWriteRequest(1L, null, null, null, null)),
                    null, false, false);

            assertThatThrownBy(() -> service.create(ADMIN, CALLER, named))
                    .isInstanceOf(ObClientValidationException.class)
                    .satisfies(e -> assertThat(((ObClientValidationException) e).errors())
                            .containsKey("salesPersonId"));
        }
    }

    // ── the two guards ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("the PAN guard is exact and final")
    class PanGuard {

        @Test
        @DisplayName("a second client with the same PAN is refused, and the existing one is named")
        void refusedAndNamed() {
            byte[] index = new byte[32];
            when(pan.seal("ABCDE1234F")).thenReturn(new PanService.SealedPan(new byte[]{1}, index));
            when(clients.findByPanBlindIndex(index)).thenReturn(Optional.of(existing("Horizon Academy", CALLER)));

            assertThatThrownBy(() -> service.create(ADMIN, CALLER, requestWithPan("ABCDE1234F")))
                    .isInstanceOf(DuplicateClientPanException.class)
                    .satisfies(e -> assertThat(((DuplicateClientPanException) e).existingName())
                            .isEqualTo("Horizon Academy"));
        }

        /**
         * The guard must still fire on a client the caller cannot see —
         * otherwise two Sales users each board the same company — and it must
         * not name it, or the guard becomes a way to read somebody else's
         * client list one PAN at a time.
         */
        @Test
        @DisplayName("a match outside the caller's scope still refuses, without naming the client")
        void refusedWithoutNaming() {
            byte[] index = new byte[32];
            when(pan.seal("ABCDE1234F")).thenReturn(new PanService.SealedPan(new byte[]{1}, index));
            when(clients.findByPanBlindIndex(index))
                    .thenReturn(Optional.of(existing("Somebody Else's Client", 999L)));

            assertThatThrownBy(() -> service.create(SALES, CALLER, requestWithPan("ABCDE1234F")))
                    .isInstanceOf(DuplicateClientPanException.class)
                    .satisfies(e -> {
                        assertThat(((DuplicateClientPanException) e).existingName()).isNull();
                        assertThat(e).hasMessageNotContaining("Somebody Else");
                    });
        }

        /** No PAN, no guard, and above all no blind index computed over a blank. */
        @Test
        @DisplayName("a client boarded without a PAN is boarded")
        void noPanNoGuard() {
            service.create(ADMIN, CALLER, request());
            verify(clients, never()).findByPanBlindIndex(any());
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

            ObClientDtos.ObClientCreateRequest acknowledged = new ObClientDtos.ObClientCreateRequest(
                    "Acme Pvt Ltd", null, BOARDED, null, null, null, null,
                    List.of(contact("spoc@example.com", true)),
                    List.of(new ObClientDtos.ObApplicationWriteRequest(1L, null, null, null, null)),
                    null, false, true);

            service.create(ADMIN, CALLER, acknowledged);
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
    }

    @Test
    @DisplayName("every product without a published template is named at once, not one per submission")
    void productsWithoutTemplates() {
        when(children.sellableProductIds(any())).thenReturn(Set.of(1L, 2L));
        when(children.productIdsWithActiveTemplate(any())).thenReturn(Set.of());

        ObClientDtos.ObClientCreateRequest two = new ObClientDtos.ObClientCreateRequest(
                "Acme", null, BOARDED, null, null, null, null,
                List.of(contact("spoc@example.com", true)),
                List.of(new ObClientDtos.ObApplicationWriteRequest(1L, null, null, null, null),
                        new ObClientDtos.ObApplicationWriteRequest(2L, null, null, null, null)),
                null, false, false);

        assertThatThrownBy(() -> service.create(ADMIN, CALLER, two))
                .isInstanceOf(ProductWithoutTemplateException.class)
                .satisfies(e -> assertThat(((ProductWithoutTemplateException) e).productIds())
                        .containsExactly(1L, 2L));
        verify(clients, never()).saveAndFlush(any());
    }

    /** One journey per purchased product, and the purchases written before them. */
    @Test
    @DisplayName("a locked journey is instantiated for every purchased product")
    void oneJourneyPerProduct() {
        when(children.sellableProductIds(any())).thenReturn(Set.of(1L, 2L));
        when(children.productIdsWithActiveTemplate(any())).thenReturn(Set.of(1L, 2L));

        ObClientDtos.ObClientCreateRequest two = new ObClientDtos.ObClientCreateRequest(
                "Acme", null, BOARDED, null, null, null, null,
                List.of(contact("spoc@example.com", true)),
                List.of(new ObClientDtos.ObApplicationWriteRequest(1L, null, null, null, null),
                        new ObClientDtos.ObApplicationWriteRequest(2L, null, null, null, null)),
                null, false, false);

        service.create(ADMIN, CALLER, two);

        verify(children).insertApplications(anyLong(), any());
        verify(journeys).instantiate(42L, 1L);
        verify(journeys).instantiate(42L, 2L);
    }

    // ── B-109 · the prerequisites instance ──────────────────────────────────

    @Nested
    @DisplayName("the prerequisites checklist")
    class Prerequisites {

        @Test
        @DisplayName("nothing published on OB-14 boards no client at all")
        void noActiveMasterBoardsNothing() {
            when(prereqs.hasActivePrereqMaster()).thenReturn(false);

            assertThatThrownBy(() -> service.create(ADMIN, CALLER, request()))
                    .isInstanceOf(NoPublishedPrerequisitesException.class);
            verify(clients, never()).saveAndFlush(any());
            verify(prereqs, never()).instantiate(anyLong());
        }

        @Test
        @DisplayName("the new client is snapshotted onto the active master, inside the same create")
        void snapshotsTheActiveMaster() {
            service.create(ADMIN, CALLER, request());

            verify(prereqs).instantiate(42L);
        }
    }

    // ── the edit ────────────────────────────────────────────────────────────

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
            when(clients.findById(5L)).thenReturn(Optional.of(client));

            ObClientUpdateRequest onlyName = new ObClientUpdateRequest();
            onlyName.setName("Acme Renamed");
            service.update(ADMIN, 5L, onlyName);

            assertThat(client.getAddress()).isEqualTo("12 Old Road");
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
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static ObClientDtos.ObContactWriteRequest contact(String email, boolean primary) {
        return new ObClientDtos.ObContactWriteRequest("SPOC", null, email, null, false, null, primary);
    }

    private static ObClientDtos.ObClientCreateRequest request() {
        return request(List.of(contact("spoc@example.com", true)));
    }

    private static ObClientDtos.ObClientCreateRequest request(
            List<ObClientDtos.ObContactWriteRequest> contacts) {
        return new ObClientDtos.ObClientCreateRequest(
                "Acme", null, BOARDED, null, null, null, null, contacts,
                List.of(new ObClientDtos.ObApplicationWriteRequest(1L, null, null, null, null)),
                null, false, false);
    }

    private static ObClientDtos.ObClientCreateRequest named(String name) {
        return new ObClientDtos.ObClientCreateRequest(
                name, null, BOARDED, null, null, null, null,
                List.of(contact("spoc@example.com", true)),
                List.of(new ObClientDtos.ObApplicationWriteRequest(1L, null, null, null, null)),
                null, false, false);
    }

    private static ObClientDtos.ObClientCreateRequest requestWithPan(String pan) {
        return new ObClientDtos.ObClientCreateRequest(
                "Acme", null, BOARDED, pan, null, null, null,
                List.of(contact("spoc@example.com", true)),
                List.of(new ObClientDtos.ObApplicationWriteRequest(1L, null, null, null, null)),
                null, false, false);
    }

    private static ObClient existing(String name, long createdBy) {
        return new ObClient(name, BOARDED, createdBy);
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
                42L, "Acme", BOARDED, "ONBOARDING", null, "LOCKED", 1, 0,
                List.of(), null, null, null, false,
                null, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), null, null);
    }
}
