package com.edunext.edutrack.api.feature.onboarding.products;

import com.edunext.edutrack.domain.onboarding.ObProduct;
import com.edunext.edutrack.domain.onboarding.ObProductRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

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
    private final ObProductService service = new ObProductService(repository);

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
    @DisplayName("the whole page costs one template query, not one per row")
    void templateFlagIsOneQueryForThePage() {
        // The difference between a catalogue screen and one that gets slower as
        // the catalogue grows. Asserted by counting the calls rather than by
        // reading the code, because the per-row version passes every other test
        // in this file.
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        ObProductRepository counting = new FakeRepository() {
            @Override
            public List<Long> findProductIdsWithAnActiveTemplate(List<Long> productIds) {
                calls.incrementAndGet();
                return List.of();
            }
        };
        ObProductService counted = new ObProductService(counting);
        for (int i = 0; i < 5; i++) {
            counted.create(write("P" + i, "Product " + i, null), null);
        }
        calls.set(0);

        counted.list(null);

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown id is empty, not an exception")
    void unknownIdIsEmpty() {
        assertThat(service.find(999L)).isEmpty();
        assertThat(service.update(999L, write("LMS", "Learning", null))).isEmpty();
    }
}
