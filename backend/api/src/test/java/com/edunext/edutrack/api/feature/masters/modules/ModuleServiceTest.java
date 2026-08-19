package com.edunext.edutrack.api.feature.masters.modules;

import com.edunext.edutrack.domain.masters.ProductModule;
import com.edunext.edutrack.domain.masters.ProductModuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * B-064 · the two decisions this master makes, proved without Docker.
 *
 * <p>{@code ModuleMasterIT} proves them against real MySQL, where C-065's seed
 * and {@code uq_product_modules_code} have opinions of their own. This proves
 * that the service asks the right question and hands back what it was given —
 * which is the half a container cannot isolate, because a passing IT cannot
 * tell "the service returns retired rows" from "the seed contains none".
 */
class ModuleServiceTest {

    private ProductModuleRepository modules;
    private ModuleService service;

    @BeforeEach
    void setUp() {
        modules = mock(ProductModuleRepository.class);
        service = new ModuleService(modules);
    }

    /**
     * The rule the whole route exists for.
     *
     * <p>A retired module reaches the caller carrying {@code isActive: false},
     * rather than being dropped. Stated with a fixture whose retired row is
     * <em>not</em> last in {@code seq} order, so an implementation that
     * filtered and an implementation that merely reordered would both fail
     * here rather than one of them passing by accident.
     */
    @Test
    @DisplayName("a deactivated module is returned, carrying isActive false")
    void retiredRowsAreReturned() {
        when(modules.findAllByOrderBySeqAscIdAsc()).thenReturn(List.of(
                module(1, "STUDENT", "Student", 10, true),
                module(9, "TRANSPORT", "Transport", 20, false),
                module(3, "FEES", "Fees", 30, true)));

        assertThat(service.list())
                .extracting(ModuleDtos.ModuleView::code, ModuleDtos.ModuleView::isActive)
                .containsExactly(
                        tuple("STUDENT", true),
                        tuple("TRANSPORT", false),
                        tuple("FEES", true));
    }

    /**
     * <b>The ordering is the repository's, and the service does not re-sort.</b>
     *
     * <p>Asserted by handing back a list the mock has deliberately <em>not</em>
     * ordered: if {@code list()} ever grew a {@code sorted()} of its own, the
     * two sources of ordering would disagree the first time {@code seq} was
     * edited and only one of them would be the one the picker renders. The
     * query name carries the contract — {@code seq} ascending, {@code id}
     * breaking the tie — and this test fails if the service starts having a
     * second opinion.
     */
    @Test
    @DisplayName("the order is whatever the query returned — the service does not re-sort")
    void orderIsTheRepositorys() {
        when(modules.findAllByOrderBySeqAscIdAsc()).thenReturn(List.of(
                module(8, "PARENT_APP", "Parent App", 80, true),
                module(1, "STUDENT", "Student", 10, true)));

        assertThat(service.list())
                .extracting(ModuleDtos.ModuleView::code)
                .containsExactly("PARENT_APP", "STUDENT");
    }

    /** Every contract property survives the mapping — including {@code seq}. */
    @Test
    @DisplayName("all five contract fields are mapped")
    void mappingIsComplete() {
        when(modules.findAllByOrderBySeqAscIdAsc())
                .thenReturn(List.of(module(4, "EXAMINATION", "Examination", 40, true)));

        assertThat(service.list().getFirst())
                .isEqualTo(new ModuleDtos.ModuleView(4L, "EXAMINATION", "Examination", (short) 40, true));
    }

    @Test
    @DisplayName("an empty master is an empty list, not a failure")
    void emptyMasterIsNotAFailure() {
        when(modules.findAllByOrderBySeqAscIdAsc()).thenReturn(List.of());

        assertThat(service.list()).isEmpty();
    }

    /**
     * B-064 · <b>the narrower question must stay unaskable.</b>
     *
     * <p>The contract hands every caller every row, and the reason is that its
     * two consumers need opposite things from one response — a picker filters,
     * a grid must not. The failure mode this guards is not the service growing
     * a filter (the tests above catch that) but a later task adding a
     * {@code findByIsActiveTrue…} derived query to the repository "for
     * convenience", after which serving an active-only list is one autocomplete
     * away and nothing says it was ever a decision.
     *
     * <p>Asserted over the interface rather than left to review, because a
     * derived query needs no body — there is no code to review.
     */
    @Test
    @DisplayName("the repository exposes no active-only query to reach for")
    void noActiveOnlyQueryExists() {
        assertThat(ProductModuleRepository.class.getDeclaredMethods())
                .extracting(Method::getName)
                .containsExactlyInAnyOrder("findAllByOrderBySeqAscIdAsc", "findByCode");
    }

    /** Nothing but the one query — no count, no second read behind the scenes. */
    @Test
    @DisplayName("one read, one query")
    void listIssuesOneQuery() {
        when(modules.findAllByOrderBySeqAscIdAsc()).thenReturn(List.of());

        service.list();

        verify(modules).findAllByOrderBySeqAscIdAsc();
        verifyNoMoreInteractions(modules);
    }

    private static ProductModule module(int id, String code, String name, int seq, boolean active) {
        ProductModule module = new ProductModule();
        module.setId(id);
        module.setCode(code);
        module.setName(name);
        module.setSeq((short) seq);
        module.setActive(active);
        return module;
    }
}
