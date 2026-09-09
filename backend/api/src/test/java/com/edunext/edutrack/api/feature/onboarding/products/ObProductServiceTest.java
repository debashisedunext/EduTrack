package com.edunext.edutrack.api.feature.onboarding.products;

import com.edunext.edutrack.domain.onboarding.ObJourneyTemplateRepository;
import com.edunext.edutrack.domain.onboarding.ObProduct;
import com.edunext.edutrack.domain.onboarding.ObProductRepository;
import com.edunext.edutrack.domain.onboarding.ObProductRepository.Tally;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;

/**
 * A-124 · the catalogue's two rules, and the one query behind
 * {@code hasActiveTemplate}.
 *
 * <p>A hand-written fake rather than a mock: every test here is about what the
 * service does with what the repository returns, and stubbing five methods per
 * test would bury that under setup. The fake also lets the case-insensitive
 * lookup behave the way the column's collation does, which is the behaviour the
 * duplicate rule actually rests on.
 */
class ObProductServiceTest {

    /** Mirrors {@code utf8mb4_0900_ai_ci}: {@code lms} finds {@code LMS}. */
    private static class FakeRepository implements ObProductRepository {
        private final List<ObProduct> rows = new ArrayList<>();
        private final AtomicLong ids = new AtomicLong();
        private List<Long> withActiveTemplate = List.of();
        private final Map<Long, Long> tatDaysByProduct = new java.util.HashMap<>();
        private final Map<Long, Long> journeysByProduct = new java.util.HashMap<>();

        @Override
        public ObProduct save(ObProduct product) {
            if (product.getId() == null) {
                setId(product, ids.incrementAndGet());
                rows.add(product);
            }
            return product;
        }

        @Override
        public Optional<ObProduct> findById(Long id) {
            return rows.stream().filter(r -> id.equals(r.getId())).findFirst();
        }

        @Override
        public List<ObProduct> findAllByOrderByNameAsc() {
            return rows.stream().sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName())).toList();
        }

        @Override
        public List<ObProduct> findAllByIsActiveOrderByNameAsc(boolean isActive) {
            return findAllByOrderByNameAsc().stream().filter(r -> r.isActive() == isActive).toList();
        }

        @Override
        public Optional<ObProduct> findByCode(String code) {
            return rows.stream().filter(r -> r.getCode().equalsIgnoreCase(code)).findFirst();
        }

        @Override
        public List<Long> findProductIdsWithAnActiveTemplate(List<Long> productIds) {
            return withActiveTemplate.stream().filter(productIds::contains).toList();
        }

        /**
         * Absent products produce no row, the way {@code group by} does — which
         * is the behaviour the null-versus-zero rule rests on.
         */
        @Override
        public List<Tally> sumActiveTemplateTatDays(List<Long> productIds) {
            return tallies(tatDaysByProduct, productIds);
        }

        @Override
        public List<Tally> countJourneysByProduct(List<Long> productIds) {
            return tallies(journeysByProduct, productIds);
        }

        private static List<Tally> tallies(Map<Long, Long> source, List<Long> productIds) {
            return productIds.stream()
                    .filter(source::containsKey)
                    .<Tally>map(id -> tally(id, source.get(id)))
                    .toList();
        }

        private static Tally tally(Long productId, long value) {
            return new Tally() {
                @Override
                public Long getProductId() {
                    return productId;
                }

                @Override
                public long getTally() {
                    return value;
                }
            };
        }

        private static void setId(ObProduct product, long id) {
            try {
                var field = ObProduct.class.getDeclaredField("id");
                field.setAccessible(true);
                field.set(product, id);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private final FakeRepository repository = new FakeRepository();
    // C-123 · unrelated to this class's own tests, which predate the
    // catalogue enrichment — a bare mock answers empty to the one batch
    // read this service makes of it, exactly like a product with no active
    // template.
    private final ObProductService service =
            new ObProductService(repository, mock(ObJourneyTemplateRepository.class));

    private static ObProductDtos.WriteRequest write(String code, String name, Boolean active) {
        return new ObProductDtos.WriteRequest(code, name, active);
    }

    @Test
    @DisplayName("creates a product, active by default")
    void createsActiveByDefault() {
        ObProductDtos.Product created = service.create(write("LMS", "Learning Management", null), null);

        assertThat(created.code()).isEqualTo("LMS");
        assertThat(created.name()).isEqualTo("Learning Management");
        assertThat(created.isActive()).isTrue();
    }

    @Test
    @DisplayName("upper-cases and trims the code, so case cannot fork the catalogue")
    void normalisesTheCode() {
        assertThat(service.create(write("lms", "Learning", null), null).code()).isEqualTo("LMS");
    }

    @Test
    @DisplayName("refuses a duplicate code case-insensitively, naming the field")
    void refusesDuplicateCode() {
        // The index would refuse this too — the collation is case-insensitive —
        // but with a message naming a MySQL constraint, which is the mistake
        // createClient was corrected for one master over.
        service.create(write("LMS", "Learning Management", null), null);

        assertThatExceptionOfType(DuplicateProductCodeException.class)
                .isThrownBy(() -> service.create(write("lms", "Duplicate", null), null))
                .satisfies(e -> assertThat(e.code()).isEqualTo("LMS"));
    }

    @Test
    @DisplayName("a resent code on a PATCH is fine — that is the ordinary round trip")
    void resendingTheSameCodeIsAllowed() {
        long id = service.create(write("LMS", "Learning", null), null).id();

        ObProductDtos.Product updated = service.update(id, write("LMS", "Renamed", false)).orElseThrow();

        assertThat(updated.name()).isEqualTo("Renamed");
        assertThat(updated.isActive()).isFalse();
    }

    @Test
    @DisplayName("a different code on a PATCH is refused rather than ignored")
    void refusesToChangeTheCode() {
        // Refused, not silently dropped: a caller who believes they renamed
        // something and did not is worse off than one who is told they cannot.
        long id = service.create(write("LMS", "Learning", null), null).id();

        assertThatExceptionOfType(ProductCodeImmutableException.class)
                .isThrownBy(() -> service.update(id, write("ERP", "Learning", null)));
    }

    @Test
    @DisplayName("retiring is not deleting — the row stays and the list still returns it")
    void retiredProductsAreReturnedAndMarked() {
        // A client boarded against a retired product still has journeys naming
        // it, so filtering it out of the catalogue leaves that name unresolvable.
        long id = service.create(write("LMS", "Learning", null), null).id();
        service.update(id, write("LMS", "Learning", false));

        assertThat(service.list(null)).singleElement()
                .satisfies(p -> assertThat(p.isActive()).isFalse());
    }

    @Test
    @DisplayName("isActive=true is the OB-04 wizard's call and filters the retired out")
    void filtersByActive() {
        long retired = service.create(write("OLD", "Retired", null), null).id();
        service.update(retired, write("OLD", "Retired", false));
        service.create(write("LMS", "Learning", null), null);

        assertThat(service.list(true)).singleElement()
                .satisfies(p -> assertThat(p.code()).isEqualTo("LMS"));
        assertThat(service.list(null)).hasSize(2);
    }

    @Test
    @DisplayName("hasActiveTemplate is false without one — the OB-04 picker's gate")
    void hasActiveTemplateIsFalseByDefault() {
        // "A product with no journey template cannot be bought", because a
        // purchase with no template to instantiate boards a client into nothing.
        ObProductDtos.Product created = service.create(write("LMS", "Learning", null), null);

        assertThat(created.hasActiveTemplate()).isFalse();
    }

    @Test
    @DisplayName("hasActiveTemplate is true when a template binds to it")
    void hasActiveTemplateIsTrueWhenBound() {
        long id = service.create(write("LMS", "Learning", null), null).id();
        repository.withActiveTemplate = List.of(id);

        assertThat(service.list(null)).singleElement()
                .satisfies(p -> assertThat(p.hasActiveTemplate()).isTrue());
    }

    @Test
    @DisplayName("totalTatDays is null without an active template — no answer, not a zero one")
    void totalTatDaysIsNullWithoutATemplate() {
        // The OB-07 card reads this as what a journey for the product costs. A
        // product nobody has drawn a template for has no such figure, and
        // rendering "0 days" would state a cost that was never decided.
        assertThat(service.create(write("LMS", "Learning", null), null).totalTatDays()).isNull();
    }

    @Test
    @DisplayName("totalTatDays is 0 for an active template with no steps — a different fact")
    void totalTatDaysIsZeroForAnEmptyTemplate() {
        // The grouped sum returns no row for this product either, exactly as it
        // does for the case above, so the two are told apart by hasActiveTemplate
        // and not by the sum. Collapsing them is the bug this asserts against.
        long id = service.create(write("LMS", "Learning", null), null).id();
        repository.withActiveTemplate = List.of(id);

        assertThat(service.list(null)).singleElement()
                .satisfies(p -> assertThat(p.totalTatDays()).isZero());
    }

    @Test
    @DisplayName("totalTatDays sums the active template's step TATs")
    void totalTatDaysSumsTheActiveTemplate() {
        long id = service.create(write("LMS", "Learning", null), null).id();
        repository.withActiveTemplate = List.of(id);
        repository.tatDaysByProduct.put(id, 12L);

        assertThat(service.list(null)).singleElement()
                .satisfies(p -> assertThat(p.totalTatDays()).isEqualTo(12));
    }

    @Test
    @DisplayName("journeyCount is 0 for a product nobody was boarded against")
    void journeyCountDefaultsToZero() {
        assertThat(service.create(write("LMS", "Learning", null), null).journeyCount()).isZero();
    }

    @Test
    @DisplayName("journeyCount counts every journey instantiated from the product")
    void journeyCountReflectsTheTally() {
        // Across all clients, and completed journeys included: the count is what
        // a retire decision is made against, and a finished journey is as much
        // evidence the product was sold as a running one.
        long id = service.create(write("LMS", "Learning", null), null).id();
        repository.journeysByProduct.put(id, 4L);

        assertThat(service.list(null)).singleElement()
                .satisfies(p -> assertThat(p.journeyCount()).isEqualTo(4));
    }

    @Test
    @DisplayName("the whole page costs three derived-field queries, not three per row")
    void derivedFieldsAreOneQueryEachForThePage() {
        // The difference between a catalogue screen and one that gets slower as
        // the catalogue grows. Asserted by counting the calls rather than by
        // reading the code, because the per-row version passes every other test
        // in this file — and it now covers all three, since totalTatDays and
        // journeyCount are exactly the shape an N+1 arrives in.
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        ObProductRepository counting = new FakeRepository() {
            @Override
            public List<Long> findProductIdsWithAnActiveTemplate(List<Long> productIds) {
                calls.incrementAndGet();
                return List.of();
            }

            @Override
            public List<Tally> sumActiveTemplateTatDays(List<Long> productIds) {
                calls.incrementAndGet();
                return List.of();
            }

            @Override
            public List<Tally> countJourneysByProduct(List<Long> productIds) {
                calls.incrementAndGet();
                return List.of();
            }
        };
        ObProductService counted = new ObProductService(counting, mock(ObJourneyTemplateRepository.class));
        for (int i = 0; i < 5; i++) {
            counted.create(write("P" + i, "Product " + i, null), null);
        }
        calls.set(0);

        counted.list(null);

        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("an unknown id is empty, not an exception")
    void unknownIdIsEmpty() {
        assertThat(service.find(999L)).isEmpty();
        assertThat(service.update(999L, write("LMS", "Learning", null))).isEmpty();
    }
}
