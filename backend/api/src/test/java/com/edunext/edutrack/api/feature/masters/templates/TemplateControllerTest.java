package com.edunext.edutrack.api.feature.masters.templates;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B-041 · what the controller decides before delegating — the three
 * {@code If-Match} preconditions, the two {@code ETag}s they are checked against,
 * and the 404 that comes before all of them.
 *
 * <p>Plain construction rather than {@code @WebMvcTest}, as
 * {@code StageControllerTest} and three masters before it do: everything asserted
 * here is method-level, and {@code MasterRoutesTest} covers the one thing plain
 * construction cannot see, which is where the class is mounted.
 */
class TemplateControllerTest {

    private TemplateService service;
    private TemplateResolver resolver;
    private TemplateController controller;

    @BeforeEach
    void setUp() {
        service = mock(TemplateService.class);
        resolver = mock(TemplateResolver.class);
        controller = new TemplateController(service, resolver);
    }

    private static TemplateDtos.WorkflowTemplateDetail view(
            long id, String name, long mappingCount, long ticketCount) {
        boolean unused = mappingCount == 0 && ticketCount == 0;
        return new TemplateDtos.WorkflowTemplateDetail(
                id, name, null, false, true, 8, mappingCount, ticketCount,
                unused, mappingCount == 0, null, null);
    }

    private static TemplateDtos.TemplateMapping mapping(long id, Long projectId, Integer taskTypeId) {
        return new TemplateDtos.TemplateMapping(id, projectId, null, null, taskTypeId, null, null,
                (projectId != null ? 1 : 0) + (taskTypeId != null ? 1 : 0));
    }

    /** The tag the controller would hand out for this state. */
    private String tagFor(TemplateDtos.WorkflowTemplateDetail v) {
        when(service.get(1L)).thenReturn(Optional.of(v));
        return controller.get(1L).getHeaders().getETag();
    }

    private String mappingTagFor(List<TemplateDtos.TemplateMapping> rows) {
        when(service.listMappings(1L)).thenReturn(Optional.of(rows));
        return controller.mappings(1L).getHeaders().getETag();
    }

    @Nested
    @DisplayName("404 comes before everything")
    class NotFound {

        @Test
        @DisplayName("a missing template is 404 on every route that names one")
        void missingTemplateIs404() {
            when(service.get(anyLong())).thenReturn(Optional.empty());
            when(service.listMappings(anyLong())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> controller.get(9L))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);
            assertThatThrownBy(() -> controller.mappings(9L))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);
        }

        /**
         * The 404 is raised before the precondition is looked at, so a caller
         * pointing at a template that does not exist gets "no such template"
         * rather than "you did not send If-Match" — which would be the more
         * confusing of the two answers to a wrong id.
         */
        @Test
        @DisplayName("a missing template is 404 rather than 428, even with no If-Match")
        void missingBeatsPrecondition() {
            when(service.get(anyLong())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> controller.delete(9L, null))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND);
            verify(service, never()).delete(anyLong());
        }
    }

    @Nested
    @DisplayName("the preconditions")
    class Preconditions {

        /**
         * Required rather than opt-in on all three writes, copied from
         * {@code StageController} because the two controllers serve one screen
         * and an Admin should not find that two tabs of it disagree about whether
         * a precondition is needed.
         */
        @Test
        @DisplayName("428 when If-Match is absent, on all three writes")
        void absentIfMatchIs428() {
            when(service.get(1L)).thenReturn(Optional.of(view(1L, "Flow", 0, 0)));
            when(service.listMappings(1L)).thenReturn(Optional.of(List.of()));

            for (Runnable write : List.<Runnable>of(
                    () -> controller.update(1L, null,
                            new TemplateDtos.WorkflowTemplatePatchRequest("x", null, null, null)),
                    () -> controller.delete(1L, null),
                    () -> controller.replaceMappings(1L, null,
                            new TemplateDtos.TemplateMappingReplaceRequest(List.of())))) {
                assertThatThrownBy(write::run)
                        .isInstanceOf(ResponseStatusException.class)
                        .hasFieldOrPropertyWithValue("statusCode", HttpStatus.PRECONDITION_REQUIRED);
            }
            verify(service, never()).update(anyLong(), any());
            verify(service, never()).delete(anyLong());
            verify(service, never()).replaceMappings(anyLong(), any());
        }

        @Test
        @DisplayName("412 when the tag is stale")
        void staleIfMatchIs412() {
            when(service.get(1L)).thenReturn(Optional.of(view(1L, "Flow", 0, 0)));

            assertThatThrownBy(() -> controller.delete(1L, "\"deadbeef\""))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasFieldOrPropertyWithValue("statusCode", HttpStatus.PRECONDITION_FAILED);
            verify(service, never()).delete(anyLong());
        }

        @Test
        @DisplayName("passes the current tag through")
        void currentTagPasses() {
            TemplateDtos.WorkflowTemplateDetail v = view(1L, "Flow", 0, 0);
            String tag = tagFor(v);
            when(service.delete(1L)).thenReturn(Optional.of(true));

            assertThatCode(() -> controller.delete(1L, tag)).doesNotThrowAnyException();
            verify(service).delete(1L);
        }

        /** RFC 9110 §13.1.1 — a proxy may weaken a tag in transit. */
        @Test
        @DisplayName("accepts a weakened tag and a comma list")
        void weakAndListAccepted() {
            TemplateDtos.WorkflowTemplateDetail v = view(1L, "Flow", 0, 0);
            String tag = tagFor(v);
            when(service.delete(1L)).thenReturn(Optional.of(true));

            assertThatCode(() -> controller.delete(1L, "W/" + tag)).doesNotThrowAnyException();
            assertThatCode(() -> controller.delete(1L, "\"other\", " + tag))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("what the tags cover")
    class Tags {

        /**
         * 🔴 The assertion the whole precondition exists for.
         *
         * <p>The delete's entire guard is that two counts are zero, and both are
         * inside the tag — so a ticket created on the template, or a rule pointed
         * at it, while the confirmation dialog sits open moves the tag and the
         * request is refused rather than performed on evidence that stopped being
         * true. A tag over the fields alone would let it through.
         */
        @Test
        @DisplayName("the template tag moves when a count the caller cannot see changes")
        void countsAreInsideTheTag() {
            String clean = tagFor(view(1L, "Flow", 0, 0));
            String withTicket = tagFor(view(1L, "Flow", 0, 1));
            String withRule = tagFor(view(1L, "Flow", 1, 0));

            assertThat(withTicket).isNotEqualTo(clean);
            assertThat(withRule).isNotEqualTo(clean);
            assertThat(withRule).isNotEqualTo(withTicket);
        }

        @Test
        @DisplayName("the template tag moves on a rename")
        void nameIsInsideTheTag() {
            assertThat(tagFor(view(1L, "Renamed", 0, 0)))
                    .isNotEqualTo(tagFor(view(1L, "Flow", 0, 0)));
        }

        /**
         * The rule set is the unit of edit for the replace, and there is no
         * per-row verb — so without this tag the {@code PUT} would need a
         * {@code NO_IF_MATCH} exemption on the write where a lost update is least
         * visible: the loser's rules vanish with nothing to indicate they were
         * ever there.
         */
        @Test
        @DisplayName("the mapping tag moves when a rule is added, removed or repointed")
        void mappingTagCoversTheWholeSet() {
            String one = mappingTagFor(List.of(mapping(1L, null, 3)));
            String two = mappingTagFor(List.of(mapping(1L, null, 3), mapping(2L, 5L, null)));
            String repointed = mappingTagFor(List.of(mapping(1L, null, 9)));
            String none = mappingTagFor(List.of());

            assertThat(List.of(two, repointed, none)).doesNotContain(one);
        }

        /**
         * The two tags are separate on purpose. A stage added on tab 2 moves the
         * template's tag and does not touch the rules, so a mapping replace
         * preconditioned on the template's tag would be refused for an edit that
         * has nothing to do with routing.
         */
        @Test
        @DisplayName("the mapping replace is not preconditioned on the template tag")
        void mappingWriteUsesItsOwnTag() {
            when(service.get(1L)).thenReturn(Optional.of(view(1L, "Flow", 1, 0)));
            String mappingTag = mappingTagFor(List.of(mapping(1L, null, 3)));
            when(service.replaceMappings(anyLong(), any()))
                    .thenReturn(Optional.of(List.of(mapping(1L, null, 3))));

            assertThatCode(() -> controller.replaceMappings(1L, mappingTag,
                    new TemplateDtos.TemplateMappingReplaceRequest(
                            List.of(new TemplateDtos.TemplateMappingEntry(null, 3)))))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("the create and the resolution")
    class Other {

        /**
         * No {@code If-Match} — there is nothing to precondition on before a row
         * exists — but the tag is handed back, so a client that creates and
         * immediately edits needs no second read.
         */
        @Test
        @DisplayName("the create takes no precondition and still returns a tag")
        void createReturnsATag() {
            when(service.create(any())).thenReturn(view(4L, "New Flow", 0, 0));

            var response = controller.create(
                    new TemplateDtos.WorkflowTemplateWriteRequest("New Flow", null, null, null));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getHeaders().getETag()).isNotBlank();
        }

        /**
         * Both parameters optional, and omitting one is a question rather than an
         * error: "what does this task type resolve to on a project with no rule
         * of its own?"
         */
        @Test
        @DisplayName("the resolution accepts a null on either side")
        void resolutionAcceptsNulls() {
            when(resolver.explain(any(), any())).thenReturn(
                    new TemplateDtos.TemplateResolution(1L, "Standard Dev Flow", "DEFAULT", null));

            assertThat(controller.resolve(null, null).data().rung()).isEqualTo("DEFAULT");
            assertThat(controller.resolve(5L, null).data().rung()).isEqualTo("DEFAULT");
            assertThat(controller.resolve(null, 3).data().rung()).isEqualTo("DEFAULT");
        }

        /**
         * No {@code ETag}. There is no row here to precondition a write on — this
         * is a computed answer over three tables, and a tag would move whenever
         * any of them did while meaning nothing to any operation.
         */
        @Test
        @DisplayName("the resolution carries no ETag, because nothing preconditions on it")
        void resolutionHasNoTag() {
            when(resolver.explain(any(), any())).thenReturn(
                    new TemplateDtos.TemplateResolution(null, null, "NONE", null));

            // The method returns the body directly rather than a ResponseEntity,
            // which is how a route with no headers to set says so.
            assertThat(controller.resolve(1L, 1)).isInstanceOf(
                    TemplateDtos.TemplateResolutionResponse.class);
        }
    }
}
