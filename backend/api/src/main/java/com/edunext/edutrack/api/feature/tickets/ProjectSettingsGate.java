package com.edunext.edutrack.api.feature.tickets;

import com.edunext.edutrack.api.text.RichTextSanitizer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * C-071 · B-019's project settings, enforced where they can only be enforced.
 *
 * <p>The Settings tab has shipped and <b>nothing obeyed it</b>: a PM could
 * restrict a project to two task types, watch the screen confirm the save, and
 * then watch tickets be raised outside them. That is worse than not offering the
 * setting at all, because the screen says it took effect. B-019's own class note
 * and the contract both say the enforcement is Stream C's; this is it.
 *
 * <h2>Reads Stream B's tables directly, and that is the established shape</h2>
 *
 * <p>{@code projects.mandatory_fields} and {@code project_task_types} belong to
 * {@code feature/masters/projects}, whose repository is package-private there.
 * {@link ClientGate} reads {@code clients} and {@code client_contacts} the same
 * way and {@link ModuleGuard} reads {@code product_modules}; a write path cannot
 * take a rule from an HTTP call to another feature without buying a network hop,
 * a second failure mode and a race against the settings being changed
 * mid-request. Two {@code SELECT}s inside the creation transaction is the
 * cheaper and more truthful arrangement — the settings that are read are the
 * settings that are in force when the row lands.
 *
 * <h2>Both refusals are 400, keyed on the field</h2>
 *
 * <p>Neither is 404: the row-scope 404 exists so an out-of-scope id leaks
 * nothing, and this project, its allow-list and the ticket-field vocabulary are
 * all readable by every role through {@code getProjectSettings} — there is
 * nothing here a caller cannot already see. What is refused is the combination,
 * which is exactly {@link TicketWriteExceptionHandler}'s existing pair.
 *
 * <h2>What this deliberately does not do</h2>
 *
 * <p><b>It does not run on {@code PATCH}.</b> A newly-ticked mandatory field
 * would otherwise make every existing ticket on the project un-editable until
 * somebody filled it in, including tickets closed years ago — and
 * {@code PatchRequest} carries seven of the ten fields, so most of them could
 * not be filled in through that route anyway. The setting governs what may be
 * raised, not what was.
 *
 * <p><b>It is not waived by {@code saveAsDraft}.</b> The flag is accepted by
 * {@code TicketCreateRequest} and acted on nowhere — a "draft" is stored as an
 * ordinary ticket today — so waiving on it would hand every caller a one-field
 * opt-out of the project's rules while the ticket it produced was
 * indistinguishable from any other. A rule any client can switch off is not a
 * rule. When a draft becomes a real state server-side this is the paragraph to
 * revisit, and {@code CreateTicketPage} applies the same fields on all three of
 * its save actions so the two halves agree meanwhile.
 */
@Component
class ProjectSettingsGate {

    /**
     * The allow-list as ids. Empty means unrestricted — see
     * {@link ProjectTicketRules#taskTypeRefused}.
     */
    private static final String ALLOWED_TASK_TYPES =
            "SELECT task_type_id FROM project_task_types WHERE project_id = :projectId";

    private static final String MANDATORY_FIELDS =
            "SELECT mandatory_fields FROM projects WHERE id = :projectId";

    private static final TypeReference<List<String>> CODES = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final RichTextSanitizer sanitizer;

    ProjectSettingsGate(JdbcClient jdbc, ObjectMapper json, RichTextSanitizer sanitizer) {
        this.jdbc = jdbc;
        this.json = json;
        this.sanitizer = sanitizer;
    }

    /**
     * @throws TaskTypeNotAllowedException the project restricts its task types
     *                                     and this is not one of them
     */
    void requireTaskTypeAllowed(Long projectId, Integer taskTypeId) {
        if (projectId == null || taskTypeId == null) {
            // Both are @NotNull on the request, so this is unreachable through
            // the controller. Returning rather than throwing keeps Bean
            // Validation's message — which names the field — as the one the
            // caller gets, instead of this class answering for a request that
            // never got past the binder.
            return;
        }
        Set<Integer> allowed = new HashSet<>(
                jdbc.sql(ALLOWED_TASK_TYPES).param("projectId", projectId).query(Integer.class).list());

        if (ProjectTicketRules.taskTypeRefused(allowed, taskTypeId)) {
            throw new TaskTypeNotAllowedException(taskTypeId);
        }
    }

    /**
     * @param plannedCloseDate the resolved date — the caller's override, or what
     *                         the SLA ladder computed. Not
     *                         {@code request.plannedCloseDate()}
     * @throws MandatoryFieldsMissingException one or more configured fields are
     *                                         empty
     */
    void requireMandatoryFields(Long projectId, TicketCreateDtos.CreateRequest request, Instant plannedCloseDate) {
        if (projectId == null) {
            return;
        }
        List<String> codes = mandatoryFields(projectId);
        if (codes.isEmpty()) {
            return;
        }
        Map<String, String> missing =
                ProjectTicketRules.missingFields(codes, request, plannedCloseDate, sanitizer);
        if (!missing.isEmpty()) {
            throw new MandatoryFieldsMissingException(missing);
        }
    }

    /**
     * The stored codes, or none.
     *
     * <p>Three cases collapse to the same answer and all three are ordinary:
     * a project that predates B-019 holds {@code NULL}, a project whose last box
     * was unticked holds {@code NULL} too (the repository writes {@code null}
     * for an empty list rather than {@code []}), and a project that has never
     * been configured is unrestricted by definition. A missing project row is
     * the fourth — it is not this gate's to report, and
     * {@code TicketCodeGenerator} answers it with the 404 the contract asks for
     * a moment later.
     *
     * <p>A malformed document reads as no requirements rather than throwing, for
     * {@code ProjectSettingsRepository.decode}'s reason turned up one notch: a
     * 500 here would block every ticket on the project, and the value cannot get
     * into the column through the API in the first place.
     */
    private List<String> mandatoryFields(long projectId) {
        String raw = jdbc.sql(MANDATORY_FIELDS)
                .param("projectId", projectId)
                .query(String.class)
                .optional()
                .orElse(null);

        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            List<String> codes = json.readValue(raw, CODES);
            return codes == null ? List.of() : codes;
        } catch (Exception e) {
            return List.of();
        }
    }
}
