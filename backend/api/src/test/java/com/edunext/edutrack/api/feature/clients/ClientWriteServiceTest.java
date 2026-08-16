package com.edunext.edutrack.api.feature.clients;

import com.edunext.edutrack.domain.clients.Client;
import com.edunext.edutrack.domain.clients.ClientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-026 · the S-33 form's write rules.
 *
 * <p>Mocked repositories, so what is asserted here is the <em>service's</em>
 * decisions — normalisation, the validation set and the order it collects in.
 * Claims about SQL, the collation and the constraints are
 * {@code ClientMasterIT}'s, against real MySQL, because a mocked
 * {@code findConflictingCode} answers whatever it was told and the claim is
 * about what the database does.
 */
class ClientWriteServiceTest {

    private ClientRepository clients;
    private ClientWriteRepository write;
    private ClientService reads;
    private ClientWriteService service;

    @BeforeEach
    void setUp() {
        clients = mock(ClientRepository.class);
        write = mock(ClientWriteRepository.class);
        reads = mock(ClientService.class);
        service = new ClientWriteService(clients, write, reads);

        when(write.findConflictingCode(any(), any())).thenReturn(Optional.empty());
        when(write.missingProjectIds(any())).thenReturn(List.of());
        when(write.slaPolicyExists(anyLong())).thenReturn(true);
        when(write.findManager(anyLong())).thenReturn(Optional.of(
                new ClientWriteRepository.ManagerCandidate(2, "Priya Sharma", true)));

        when(clients.saveAndFlush(any())).thenAnswer(i -> {
            Client saved = i.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(42L);
            }
            return saved;
        });
        when(reads.find(anyLong())).thenReturn(Optional.of(row()));
        when(reads.toDetail(any(), any())).thenAnswer(i -> detail());
    }

    // ------------------------------------------------------------------
    // Normalisation
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("what gets stored")
    class Normalisation {

        @Test
        @DisplayName("the client code is upper-cased, so acme and ACME cannot both exist")
        void codeIsUpperCased() {
            service.create(write(b -> b.clientCode("acme-retail")));

            assertThat(saved().getClientCode()).isEqualTo("ACME-RETAIL");
        }

        /**
         * D-039 looks up the bare host taken from a sender address. An admin
         * pastes what is in their address bar, and stored unmodified the two
         * never meet — inbound mail attribution stops working for that client
         * with nothing on any screen looking wrong.
         */
        @Test
        @DisplayName("the domain is reduced to the bare host D-039 can match a sender against")
        void domainIsNormalised() {
            service.create(write(b -> b.domain("https://www.Acme.Example/support")));

            assertThat(saved().getWebsiteDomain()).isEqualTo("acme.example");
        }

        @Test
        @DisplayName("an absent timezone is the column default, not null")
        void timezoneDefaults() {
            service.create(write(b -> b.timezone(null)));

            assertThat(saved().getTimezone()).isEqualTo("Asia/Kolkata");
        }

        @Test
        @DisplayName("a support plan is stored upper-cased, agreeing with the fixture")
        void supportPlanIsUpperCased() {
            service.create(write(b -> b.supportPlan("premium")));

            assertThat(saved().getSupportPlan()).isEqualTo("PREMIUM");
        }

        /**
         * NULL and [] both mean "no tags" and only one of them costs a JSON
         * document on every row of the grid's page query.
         */
        @Test
        @DisplayName("tags are trimmed, de-duplicated and null when nothing survives")
        void tagsAreNormalised() {
            service.create(write(b -> b.tags(List.of(" retail ", "retail", "vip"))));

            assertThat(saved().getTags()).containsExactly("retail", "vip");
        }

        @Test
        @DisplayName("blank text fields are stored as null, not as empty strings")
        void blanksBecomeNull() {
            service.create(write(b -> b.shortName("   ").industry("")));

            assertThat(saved().getShortName()).isNull();
            assertThat(saved().getIndustry()).isNull();
        }

        /**
         * The body is the whole representation — S-33 submits every input on
         * every save, so "the form did not send a short name" and "the admin
         * emptied the box" are the same event. A sparse reading would make
         * clearing a field impossible through the only screen that edits it.
         */
        @Test
        @DisplayName("an omitted field clears the stored value, because the form sends them all")
        void omittedFieldsAreCleared() {
            Client existing = existing();
            existing.setShortName("Acme");
            existing.setNotes("An old note.");
            when(clients.findById(1L)).thenReturn(Optional.of(existing));

            service.update(1, write(b -> b.shortName(null).notes(null)));

            assertThat(existing.getShortName()).isNull();
            assertThat(existing.getNotes()).isNull();
        }

        @Test
        @DisplayName("an absent status is ACTIVE, which is what every create means")
        void absentStatusIsActive() {
            service.create(write(b -> b.status(null)));

            assertThat(saved().getStatus()).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("a prospect can be created directly")
        void prospectIsCreatable() {
            service.create(write(b -> b.status("prospect")));

            assertThat(saved().getStatus()).isEqualTo("PROSPECT");
        }
    }

    // ------------------------------------------------------------------
    // The project mapping
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the project mapping")
    class Mapping {

        /**
         * B-035's import writes client rows and never touches project
         * associations. A null read as "unmap" would have every import silently
         * detach every client it updated from every project — discovered later
         * as a ticket form whose client dropdown has gone empty.
         */
        @Test
        @DisplayName("absent projectIds leaves the mapping alone")
        void absentLeavesTheMappingAlone() {
            when(clients.findById(1L)).thenReturn(Optional.of(existing()));

            service.update(1, write(b -> b.projectIds(null)));

            verify(write, never()).replaceProjects(anyLong(), any(), any());
        }

        @Test
        @DisplayName("an empty projectIds unmaps the client from everything")
        void emptyUnmapsEverything() {
            when(clients.findById(1L)).thenReturn(Optional.of(existing()));

            service.update(1, write(b -> b.projectIds(List.of())));

            verify(write).replaceProjects(eq(1L), eq(List.of()), eq(null));
        }

        @Test
        @DisplayName("duplicate project ids are collapsed rather than refused")
        void duplicatesAreCollapsed() {
            service.create(write(b -> b.projectIds(List.of(1L, 2L, 1L))));

            ArgumentCaptor<List<Long>> ids = captor();
            verify(write).replaceProjects(eq(42L), ids.capture(), any());
            assertThat(ids.getValue()).containsExactly(1L, 2L);
        }
    }

    // ------------------------------------------------------------------
    // Refusals
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        @DisplayName("a duplicate client code is a 409 keyed on the field")
        void duplicateCode() {
            when(write.findConflictingCode(eq("ACME"), any())).thenReturn(Optional.of("ACME"));

            assertThatThrownBy(() -> service.create(write(b -> b.clientCode("acme"))))
                    .isInstanceOf(ClientWriteService.ClientValidationException.class)
                    .satisfies(e -> {
                        var failure = (ClientWriteService.ClientValidationException) e;
                        assertThat(failure.errors()).containsOnlyKeys("clientCode");
                        assertThat(failure.isDuplicateCodeOnly()).isTrue();
                        assertThat(failure.errors().get("clientCode"))
                                .as("the message names the code as it is stored, or `acme` "
                                        + "refused for colliding with `ACME` reads as a string "
                                        + "rejected for matching itself")
                                .contains("ACME");
                    });
        }

        /**
         * S-33 submits the whole form on every save, so without the {@code id <>
         * ?} in the conflict query a rename of any other field would 409 on the
         * code the client already holds. B-013 had to document the same thing on
         * the resource form.
         */
        @Test
        @DisplayName("an edit passes its own code back to the check with its own id excluded")
        void ownCodeIsExcludedOnAnEdit() {
            when(clients.findById(1L)).thenReturn(Optional.of(existing()));

            service.update(1, write(b -> b.clientCode("acme")));

            verify(write).findConflictingCode("ACME", 1L);
        }

        @Test
        @DisplayName("an unknown time zone is refused rather than stored as a string")
        void unknownTimezone() {
            assertThatThrownBy(() -> service.create(write(b -> b.timezone("Mars/Olympus"))))
                    .isInstanceOf(ClientWriteService.ClientValidationException.class)
                    .satisfies(errorsOn("timezone"));
        }

        @Test
        @DisplayName("a contract that ends before it starts is refused")
        void backwardsContract() {
            assertThatThrownBy(() -> service.create(write(b -> b
                    .contractStart(LocalDate.of(2026, 6, 1))
                    .contractEnd(LocalDate.of(2026, 5, 1)))))
                    .isInstanceOf(ClientWriteService.ClientValidationException.class)
                    .satisfies(errorsOn("contractEnd"));
        }

        @Test
        @DisplayName("the same contract start and end is allowed — a one-day contract is a contract")
        void sameDayContractIsFine() {
            LocalDate day = LocalDate.of(2026, 6, 1);

            service.create(write(b -> b.contractStart(day).contractEnd(day)));

            assertThat(saved().getContractEnd()).isEqualTo(day);
        }

        @Test
        @DisplayName("an account manager who has left is refused, by name")
        void deactivatedAccountManager() {
            when(write.findManager(5L)).thenReturn(Optional.of(
                    new ClientWriteRepository.ManagerCandidate(5, "Rahul Verma", false)));

            assertThatThrownBy(() -> service.create(write(b -> b.accountManagerId(5L))))
                    .isInstanceOf(ClientWriteService.ClientValidationException.class)
                    .satisfies(e -> assertThat(
                            ((ClientWriteService.ClientValidationException) e)
                                    .errors().get("accountManagerId"))
                            .as("who it is matters — an id in a message sends the admin "
                                    + "to look it up")
                            .contains("Rahul Verma"));
        }

        /**
         * B-016 made the same call for a project manager: a support-led account
         * has a legitimate reason to name its Support lead, and a hardcoded role
         * set is what B-015 removed from {@code ResourceController}.
         */
        @Test
        @DisplayName("the account manager's role is deliberately not checked")
        void anyActiveResourceMayBeAccountManager() {
            when(write.findManager(9L)).thenReturn(Optional.of(
                    new ClientWriteRepository.ManagerCandidate(9, "Ibrahim Qureshi", true)));

            service.create(write(b -> b.accountManagerId(9L)));

            assertThat(saved().getAccountManagerId()).isEqualTo(9L);
        }

        @Test
        @DisplayName("every missing project is named, not the first")
        void everyMissingProjectIsNamed() {
            when(write.missingProjectIds(any())).thenReturn(List.of(7L, 9L));

            assertThatThrownBy(() -> service.create(write(b -> b.projectIds(List.of(1L, 7L, 9L)))))
                    .isInstanceOf(ClientWriteService.ClientValidationException.class)
                    .satisfies(e -> assertThat(
                            ((ClientWriteService.ClientValidationException) e)
                                    .errors().get("projectIds"))
                            .as("a stale multi-select is fixed in one round or in as many "
                                    + "rounds as it has stale entries")
                            .contains("7").contains("9"));
        }

        @Test
        @DisplayName("a default project the client is not mapped to is refused")
        void defaultProjectMustBeMapped() {
            assertThatThrownBy(() -> service.create(write(b -> b
                    .projectIds(List.of(1L, 2L))
                    .defaultProjectId(3L))))
                    .isInstanceOf(ClientWriteService.ClientValidationException.class)
                    .satisfies(errorsOn("defaultProjectId"));
        }

        @Test
        @DisplayName("an unknown SLA policy is refused rather than left to the foreign key")
        void unknownSlaPolicy() {
            when(write.slaPolicyExists(88L)).thenReturn(false);

            assertThatThrownBy(() -> service.create(write(b -> b.slaPolicyId(88L))))
                    .isInstanceOf(ClientWriteService.ClientValidationException.class)
                    .satisfies(errorsOn("slaPolicyId"));
        }

        @Test
        @DisplayName("an unknown status names the three that exist")
        void unknownStatus() {
            assertThatThrownBy(() -> service.create(write(b -> b.status("ARCHIVED"))))
                    .isInstanceOf(ClientWriteService.ClientValidationException.class)
                    .satisfies(e -> assertThat(
                            ((ClientWriteService.ClientValidationException) e)
                                    .errors().get("status"))
                            .contains("PROSPECT"));
        }

        @Test
        @DisplayName("an unknown support plan names the four that exist")
        void unknownSupportPlan() {
            assertThatThrownBy(() -> service.create(write(b -> b.supportPlan("Gold"))))
                    .isInstanceOf(ClientWriteService.ClientValidationException.class)
                    .satisfies(errorsOn("supportPlan"));
        }

        /**
         * S-33 is a four-tab form and its fields are spread across all four.
         * Refusing one at a time means an admin who mistyped a timezone on
         * Identity and picked a departed manager on Commercial saves twice to
         * learn twice.
         */
        @Test
        @DisplayName("every failure comes back at once, across tabs")
        void failuresAreCollected() {
            when(write.findManager(5L)).thenReturn(Optional.of(
                    new ClientWriteRepository.ManagerCandidate(5, "Rahul Verma", false)));

            assertThatThrownBy(() -> service.create(write(b -> b
                    .timezone("Mars/Olympus")
                    .accountManagerId(5L)
                    .supportPlan("Gold"))))
                    .isInstanceOf(ClientWriteService.ClientValidationException.class)
                    .satisfies(e -> {
                        Map<String, String> errors =
                                ((ClientWriteService.ClientValidationException) e).errors();
                        assertThat(errors).containsOnlyKeys(
                                "supportPlan", "timezone", "accountManagerId");
                        assertThat(((ClientWriteService.ClientValidationException) e)
                                .isDuplicateCodeOnly())
                                .as("a 409 carrying three unrelated failures would be handled "
                                        + "as a uniqueness conflict and the rest never shown")
                                .isFalse();
                    });
        }

        @Test
        @DisplayName("a duplicate code alongside another failure is a 400, not a 409")
        void duplicateCodeIsNotAloneIsA400() {
            when(write.findConflictingCode(any(), any())).thenReturn(Optional.of("ACME"));

            assertThatThrownBy(() -> service.create(write(b -> b
                    .clientCode("acme").timezone("Mars/Olympus"))))
                    .isInstanceOf(ClientWriteService.ClientValidationException.class)
                    .satisfies(e -> assertThat(
                            ((ClientWriteService.ClientValidationException) e)
                                    .isDuplicateCodeOnly())
                            .isFalse());
        }

        @Test
        @DisplayName("nothing is written when validation fails")
        void nothingIsWrittenOnFailure() {
            assertThatThrownBy(() -> service.create(write(b -> b.timezone("Mars/Olympus"))))
                    .isInstanceOf(ClientWriteService.ClientValidationException.class);

            verify(clients, never()).saveAndFlush(any());
            verify(write, never()).replaceProjects(anyLong(), any(), any());
        }

        @Test
        @DisplayName("an update to a client that is not there is empty, which the controller makes 404")
        void unknownClientIsEmpty() {
            when(clients.findById(9L)).thenReturn(Optional.empty());

            assertThat(service.update(9, write(b -> b))).isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private Client saved() {
        ArgumentCaptor<Client> client = ArgumentCaptor.forClass(Client.class);
        verify(clients).saveAndFlush(client.capture());
        return client.getValue();
    }

    private static Client existing() {
        Client client = new Client();
        client.setId(1L);
        client.setClientCode("ACME");
        client.setName("Acme Retail Ltd");
        client.setStatus("ACTIVE");
        return client;
    }

    private static java.util.function.Consumer<Throwable> errorsOn(String field) {
        return e -> assertThat(((ClientWriteService.ClientValidationException) e).errors())
                .containsKey(field);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<Long>> captor() {
        return ArgumentCaptor.forClass((Class<List<Long>>) (Class<?>) List.class);
    }

    private static ClientDtos.Client row() {
        return new ClientDtos.Client(42, "NEWCO", "Newco Ltd", null, null, null, null,
                "Asia/Kolkata", true, "ACTIVE", 0, null, List.of(), null);
    }

    private static ClientDtos.ClientDetail detail() {
        return new ClientDtos.ClientDetail(42, "NEWCO", "Newco Ltd", null, null, null, null,
                "Asia/Kolkata", true, "ACTIVE", 0, null, List.of(), null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, List.of(), null, 0, false);
    }

    /**
     * A minimal valid body plus whatever the test varies. Written as a builder
     * because the request has twenty-eight components and a positional literal
     * per test would make every one of them unreadable — and would have to be
     * edited in twenty places the next time S-33 grows a field.
     */
    private static ClientDtos.ClientWriteRequest write(
            java.util.function.UnaryOperator<Builder> customise) {
        return customise.apply(new Builder()).build();
    }

    private static final class Builder {
        private String clientCode = "NEWCO";
        private String name = "Newco Ltd";
        private String shortName;
        private String logoUrl;
        private String industry;
        private String status;
        private String domain;
        private String primaryEmail;
        private String supportEmail;
        private String phone;
        private String addressLine1;
        private String addressLine2;
        private String city;
        private String state;
        private String country;
        private String postalCode;
        private String timezone;
        private Long accountManagerId;
        private LocalDate contractStart;
        private LocalDate contractEnd;
        private String supportPlan;
        private String billingReference;
        private String billingEmail;
        private String notes;
        private List<String> tags;
        private List<Long> projectIds;
        private Long defaultProjectId;
        private Long slaPolicyId;

        Builder clientCode(String v) {
            this.clientCode = v;
            return this;
        }

        Builder shortName(String v) {
            this.shortName = v;
            return this;
        }

        Builder industry(String v) {
            this.industry = v;
            return this;
        }

        Builder status(String v) {
            this.status = v;
            return this;
        }

        Builder domain(String v) {
            this.domain = v;
            return this;
        }

        Builder timezone(String v) {
            this.timezone = v;
            return this;
        }

        Builder accountManagerId(Long v) {
            this.accountManagerId = v;
            return this;
        }

        Builder contractStart(LocalDate v) {
            this.contractStart = v;
            return this;
        }

        Builder contractEnd(LocalDate v) {
            this.contractEnd = v;
            return this;
        }

        Builder supportPlan(String v) {
            this.supportPlan = v;
            return this;
        }

        Builder notes(String v) {
            this.notes = v;
            return this;
        }

        Builder tags(List<String> v) {
            this.tags = v;
            return this;
        }

        Builder projectIds(List<Long> v) {
            this.projectIds = v;
            return this;
        }

        Builder defaultProjectId(Long v) {
            this.defaultProjectId = v;
            return this;
        }

        Builder slaPolicyId(Long v) {
            this.slaPolicyId = v;
            return this;
        }

        ClientDtos.ClientWriteRequest build() {
            return new ClientDtos.ClientWriteRequest(
                    clientCode, name, shortName, logoUrl, industry, status, domain,
                    primaryEmail, supportEmail, phone,
                    addressLine1, addressLine2, city, state, country, postalCode, timezone,
                    accountManagerId, contractStart, contractEnd, supportPlan,
                    billingReference, billingEmail, notes, tags,
                    projectIds, defaultProjectId, slaPolicyId);
        }
    }
}
