package com.edunext.edutrack.api.feature.portal.tickets;

import com.edunext.edutrack.api.feature.portal.tickets.PortalTicketDtos.PortalAttachment;
import com.edunext.edutrack.api.feature.portal.tickets.PortalTicketDtos.PortalComment;
import com.edunext.edutrack.api.feature.portal.tickets.PortalTicketDtos.PortalTicket;
import com.edunext.edutrack.api.security.PrincipalType;
import com.edunext.edutrack.common.pagination.CursorPage;
import com.edunext.edutrack.common.pagination.PageMeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A-127 · what CP-06 and CP-07 actually receive over the wire.
 *
 * <h2>Why this is not covered by anything else</h2>
 *
 * <p>{@code PortalTicketServiceTest} mocks the repository and asserts the two
 * gates; {@code PortalTicketRoutesTest} asserts the routes exist;
 * {@code PermissionMatrixTest} and {@code RouteAuthorizationTest} ask only about
 * authorisation. <b>None of them serialises a response.</b>
 *
 * <p>That gap is the one this repository has been burned by before, and the burn
 * is written down twice: {@code TicketWireConformanceTest}'s header ("three
 * separate bugs on 21 Aug 2026 had one cause between them: the MSW mock follows
 * the contract and the server does not, and nothing compared them") and
 * {@code ObWireConformanceTest}'s. Neither covers these DTOs —
 * {@code ObWireConformanceTest} scans only {@code feature.onboarding}, and
 * {@code TicketWireConformanceTest} is written by hand for {@code TicketWire}.
 * Until a reflective check covers {@code feature.portal}, this is what compares
 * the two, which is why the assertions below are field-level rather than
 * "status is 200".
 *
 * <h2>The real chain, not a slice</h2>
 *
 * <p>A {@code @WebMvcTest} was the first draft and cannot work here: it
 * instantiates every {@code @RestControllerAdvice}, {@code AuthExceptionHandler}
 * takes a {@code RefreshTokenIssuer}, and that class is package-private in
 * {@code feature.auth} — so this package cannot supply the mock that would fix
 * it. {@code MeControllerTest} can only carry that line because it lives in the
 * same package.
 *
 * <p>Going through the whole chain is better anyway, and is
 * {@code PortalRouteFilterTest}'s arrangement: the request passes
 * {@code PortalRouteFilter} as a real {@code CLIENT} principal, so what these
 * assertions describe is the response a customer's browser gets. Only
 * {@link PortalTicketService} is mocked — which is what keeps a datasource out
 * of it, since the handler then never opens a transaction.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
})
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
@AutoConfigureMockMvc
class PortalTicketControllerTest {

    private static final String TICKET = "ACME-26-00042";
    private static final String BASE = "/api/v1/portal/tickets";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    PortalTicketService service;

    /** {@code PortalRouteFilterTest}'s client, verbatim — the portal's own principal. */
    private static JwtAuthenticationToken client() {
        return new JwtAuthenticationToken(
                Jwt.withTokenValue("client")
                        .header("alg", "HS256")
                        .subject("55")
                        .claim(PrincipalType.CLAIM, "CLIENT")
                        .claim("client_id", 7)
                        .claim("ob_client_id", 9)
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(300))
                        .build(),
                List.of());
    }

    private static PortalTicket ticket() {
        return new PortalTicket(1, TICKET, "Login fails after the 4.2 upgrade",
                "Steps to reproduce are attached.", "IN_PROGRESS", "Acme ERP", "Bug",
                Instant.parse("2026-09-01T10:00:00Z"),
                Instant.parse("2026-09-10T10:00:00Z"),
                null,
                Instant.parse("2026-09-02T10:00:00Z"));
    }

    @Nested
    @DisplayName("the list")
    class ListReads {

        @Test
        @DisplayName("is a { data, meta } envelope carrying every declared field")
        void servesTheDeclaredEnvelope() throws Exception {
            when(service.list(any(), any(), anyBoolean(), any(), any(), any()))
                    .thenReturn(new CursorPage<>(List.of(ticket()), PageMeta.last()));

            mvc.perform(get(BASE).with(authentication(client())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(1))
                    .andExpect(jsonPath("$.data[0].ticketId").value(TICKET))
                    .andExpect(jsonPath("$.data[0].title").value("Login fails after the 4.2 upgrade"))
                    .andExpect(jsonPath("$.data[0].status").value("IN_PROGRESS"))
                    .andExpect(jsonPath("$.data[0].projectName").value("Acme ERP"))
                    .andExpect(jsonPath("$.data[0].taskTypeName").value("Bug"))
                    .andExpect(jsonPath("$.data[0].dateReported").exists())
                    .andExpect(jsonPath("$.data[0].plannedCloseDate").exists())
                    .andExpect(jsonPath("$.data[0].lastUpdatedAt").exists())
                    .andExpect(jsonPath("$.meta.hasMore").value(false));
        }

        /**
         * The never-visible list, asserted as absence.
         *
         * <p>{@code PortalTicketDtos} says these fields are not on the record and
         * says why; this is the half that stays true when somebody adds one back
         * "because the query already has it". A serializer cannot omit a
         * component a record declares, so a field appearing here means the record
         * grew one.
         */
        @Test
        @DisplayName("carries no owner, no level, no stage and no effort")
        void omitsEverythingOnTheNeverVisibleList() throws Exception {
            when(service.list(any(), any(), anyBoolean(), any(), any(), any()))
                    .thenReturn(new CursorPage<>(List.of(ticket()), PageMeta.last()));

            mvc.perform(get(BASE).with(authentication(client())))
                    .andExpect(jsonPath("$.data[0].assignedTo").doesNotExist())
                    .andExpect(jsonPath("$.data[0].reportedBy").doesNotExist())
                    .andExpect(jsonPath("$.data[0].assignee").doesNotExist())
                    .andExpect(jsonPath("$.data[0].level").doesNotExist())
                    .andExpect(jsonPath("$.data[0].currentStage").doesNotExist())
                    .andExpect(jsonPath("$.data[0].isDelayed").doesNotExist())
                    .andExpect(jsonPath("$.data[0].totalEffortHrs").doesNotExist())
                    .andExpect(jsonPath("$.data[0].projectId").doesNotExist());
        }
    }

    @Nested
    @DisplayName("the detail read")
    class Detail {

        @Test
        @DisplayName("is a { data } envelope and carries an ETag")
        void servesTheEnvelopeWithAValidator() throws Exception {
            when(service.get(any(), anyString())).thenReturn(Optional.of(ticket()));

            mvc.perform(get(BASE + "/" + TICKET).with(authentication(client())))
                    .andExpect(status().isOk())
                    .andExpect(header().exists(HttpHeaders.ETAG))
                    .andExpect(jsonPath("$.data.ticketId").value(TICKET))
                    .andExpect(jsonPath("$.data.description").value("Steps to reproduce are attached."));
        }

        /**
         * The validator round-trips, which is the whole point of issuing one.
         *
         * <p>Taken from the first response rather than recomputed from the
         * record's hash: asserting the formula would pass even if the handler
         * never compared it, which is precisely the bug this replaces — the
         * contract declared {@code 304} and {@code If-None-Match} from the start
         * and the first draft of this controller read neither.
         */
        @Test
        @DisplayName("answers 304 to the validator it just issued")
        void answersNotModified() throws Exception {
            when(service.get(any(), anyString())).thenReturn(Optional.of(ticket()));

            MvcResult first = mvc.perform(get(BASE + "/" + TICKET).with(authentication(client())))
                    .andExpect(status().isOk())
                    .andReturn();
            String etag = first.getResponse().getHeader(HttpHeaders.ETAG);
            assertThat(etag).isNotBlank();

            mvc.perform(get(BASE + "/" + TICKET)
                            .with(authentication(client()))
                            .header(HttpHeaders.IF_NONE_MATCH, etag))
                    .andExpect(status().isNotModified());
        }

        @Test
        @DisplayName("a validator that does not match still returns the body")
        void staleValidatorGetsTheBody() throws Exception {
            when(service.get(any(), anyString())).thenReturn(Optional.of(ticket()));

            mvc.perform(get(BASE + "/" + TICKET)
                            .with(authentication(client()))
                            .header(HttpHeaders.IF_NONE_MATCH, "\"stale\""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.ticketId").value(TICKET));
        }

        /**
         * Empty is 404, never 403 — the no-existence-leak rule, which matters
         * more here than anywhere it is usually applied because the caller is
         * outside the organisation.
         */
        @Test
        @DisplayName("an empty result is 404")
        void emptyIsNotFound() throws Exception {
            when(service.get(any(), anyString())).thenReturn(Optional.empty());

            mvc.perform(get(BASE + "/" + TICKET).with(authentication(client())))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("the child listings")
    class Children {

        @Test
        @DisplayName("comments serve the declared fields and no author handle")
        void commentsEnvelope() throws Exception {
            when(service.comments(any(), anyString(), any(), any())).thenReturn(
                    Optional.of(new CursorPage<>(List.of(new PortalComment(
                            5, "Fixed in the 4.2.1 release.", "STAFF", "Priya N",
                            Instant.parse("2026-09-02T09:00:00Z"))), PageMeta.last())));

            mvc.perform(get(BASE + "/" + TICKET + "/comments").with(authentication(client())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(5))
                    .andExpect(jsonPath("$.data[0].body").value("Fixed in the 4.2.1 release."))
                    .andExpect(jsonPath("$.data[0].authorType").value("STAFF"))
                    .andExpect(jsonPath("$.data[0].authorName").value("Priya N"))
                    .andExpect(jsonPath("$.data[0].createdAt").exists())
                    // A display name, never a UserRef — the id and email are the
                    // staff handle the never-visible list is about.
                    .andExpect(jsonPath("$.data[0].author").doesNotExist())
                    .andExpect(jsonPath("$.data[0].authorId").doesNotExist())
                    .andExpect(jsonPath("$.data[0].isInternal").doesNotExist());
        }

        @Test
        @DisplayName("attachments serve the signed URL and no scan status or uploader")
        void attachmentsEnvelope() throws Exception {
            when(service.attachments(any(), anyString(), any(), any())).thenReturn(
                    Optional.of(new CursorPage<>(List.of(new PortalAttachment(
                            9, "steps.pdf", "application/pdf", 1024,
                            "https://bucket.example/signed", null,
                            Instant.parse("2026-09-02T09:00:00Z"))), PageMeta.last())));

            mvc.perform(get(BASE + "/" + TICKET + "/attachments").with(authentication(client())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].fileName").value("steps.pdf"))
                    .andExpect(jsonPath("$.data[0].contentType").value("application/pdf"))
                    .andExpect(jsonPath("$.data[0].sizeBytes").value(1024))
                    .andExpect(jsonPath("$.data[0].downloadUrl").value("https://bucket.example/signed"))
                    .andExpect(jsonPath("$.data[0].scanStatus").doesNotExist())
                    .andExpect(jsonPath("$.data[0].uploadedBy").doesNotExist())
                    .andExpect(jsonPath("$.data[0].storageKey").doesNotExist())
                    .andExpect(jsonPath("$.data[0].isClientVisible").doesNotExist());
        }

        /**
         * A ticket the caller cannot reach makes its children 404 too, and by the
         * same refusal — not an empty list, which would confirm the ticket code.
         */
        @Test
        @DisplayName("both are 404 when the ticket is out of scope")
        void outOfScopeChildrenAreNotFound() throws Exception {
            when(service.comments(any(), anyString(), any(), any())).thenReturn(Optional.empty());
            when(service.attachments(any(), anyString(), any(), any())).thenReturn(Optional.empty());

            mvc.perform(get(BASE + "/" + TICKET + "/comments").with(authentication(client())))
                    .andExpect(status().isNotFound());
            mvc.perform(get(BASE + "/" + TICKET + "/attachments").with(authentication(client())))
                    .andExpect(status().isNotFound());
        }
    }

    /**
     * The staff direction, on this surface rather than on the empty tree
     * {@code PortalRouteFilterTest} had to use.
     *
     * <p>That class's header says its portal assertions "discriminate on the
     * body" because nothing served {@code /api/v1/portal/**} and a 404 would
     * arrive with the filter deleted. These routes are served now, so the same
     * claim can finally be made the strong way: a staff token gets 404 from a
     * path that demonstrably answers 200 to a client.
     */
    @Nested
    @DisplayName("a staff principal")
    class Staff {

        private static JwtAuthenticationToken staff() {
            return new JwtAuthenticationToken(
                    Jwt.withTokenValue("staff")
                            .header("alg", "HS256")
                            .subject("1")
                            .claim("role", "ADMIN")
                            .claim("projects", List.of())
                            .claim("modules", List.of("TICKETING", "ONBOARDING"))
                            .issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(300))
                            .build(),
                    List.of());
        }

        @Test
        @DisplayName("is refused the portal's tickets, with 404 and not 403")
        void isRefusedThePortal() throws Exception {
            when(service.list(any(), any(), anyBoolean(), any(), any(), any()))
                    .thenReturn(new CursorPage<>(List.of(ticket()), PageMeta.last()));

            mvc.perform(get(BASE).with(authentication(staff())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }
}
