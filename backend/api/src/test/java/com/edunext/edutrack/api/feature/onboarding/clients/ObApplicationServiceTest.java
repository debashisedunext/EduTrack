package com.edunext.edutrack.api.feature.onboarding.clients;

import com.edunext.edutrack.api.feature.onboarding.instances.ObJourneyInstantiationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * B-104 · the purchase rules, without a database.
 *
 * <p>What earns a container is in {@code ObApplicationsIT}: {@code
 * uq_ob_client_applications}, the two CHECK constraints, and the one thing no
 * mock can assert — that adding a purchase really does leave a journey behind
 * it. Everything below is a decision made in Java before any of that is
 * reached, which is {@code ObContactServiceTest}'s own split.
 */
class ObApplicationServiceTest {

    private static final long CLIENT = 42L;
    private static final long APPLICATION = 7L;
    private static final long PRODUCT = 11L;
    private static final long OTHER_PRODUCT = 12L;
    private static final long CALLER = 3L;

    private static final ObClientScope ADMIN = new ObClientScope(ObClientScope.OB_ADMIN, CALLER);
    private static final ObClientScope VIEWER = new ObClientScope(ObClientScope.OB_VIEWER, CALLER);

    private ObClientService details;
    private ObClientReadRepository reads;
    private ObClientChildWriteRepository products;
    private ObApplicationWriteRepository applications;
    private ObJourneyInstantiationService journeys;
    private ObApplicationService service;

    @BeforeEach
    void setUp() {
        details = mock(ObClientService.class);
        reads = mock(ObClientReadRepository.class);
        products = mock(ObClientChildWriteRepository.class);
        applications = mock(ObApplicationWriteRepository.class);
        journeys = mock(ObJourneyInstantiationService.class);
        service = new ObApplicationService(details, reads, products, applications, journeys);

        when(details.findDetail(any(), anyLong())).thenReturn(Optional.of(detailStub()));
        when(reads.applicationByProduct(anyLong(), anyLong())).thenReturn(Optional.empty());
        when(reads.applicationOf(anyLong(), anyLong())).thenReturn(Optional.of(applicationRow()));
        when(products.sellableProductIds(any())).thenReturn(Set.of(PRODUCT, OTHER_PRODUCT));
        when(products.productIdsWithActiveTemplate(any())).thenReturn(Set.of(PRODUCT, OTHER_PRODUCT));
    }

    // ── who may write ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("standing")
    class Standing {

        /**
         * 404 before 403 and never the reverse. A client this caller cannot see
         * must be indistinguishable from one that does not exist, whatever their
         * role — otherwise the purchases routes become a way to test client ids
         * the client routes deny.
         */
        @Test
        void anOutOfScopeClientIs404EvenForAWriter() {
            when(details.findDetail(any(), eq(CLIENT))).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.add(ADMIN, CLIENT, request(PRODUCT)))
                    .isInstanceOf(ObClientNotFoundException.class);
            verifyNoInteractions(applications, journeys);
        }

        /** A Viewer has been shown this client by OB-05, so 403 concedes nothing. */
        @Test
        void aViewerIs403OnTheAdd() {
            assertThatThrownBy(() -> service.add(VIEWER, CLIENT, request(PRODUCT)))
                    .isInstanceOf(ObClientReadOnlyException.class);
            verifyNoInteractions(applications, journeys);
        }

        /**
         * The role is decided before the purchase id is resolved, so a Viewer
         * cannot use the 403/404 difference to learn which purchase ids exist
         * under a client they can read — which here would mean learning what
         * another organisation bought.
         */
        @Test
        void aViewerLearnsNothingAboutWhichPurchaseIdsExist() {
            assertThatThrownBy(() -> service.update(VIEWER, CLIENT, 999L, request(PRODUCT)))
                    .isInstanceOf(ObClientReadOnlyException.class);
            verifyNoInteractions(applications);
        }

        @Test
        void aPurchaseUnderAnotherClientIs404() {
            when(reads.applicationOf(CLIENT, APPLICATION)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(ADMIN, CLIENT, APPLICATION, request(PRODUCT)))
                    .isInstanceOf(ObApplicationNotFoundException.class);
            verifyNoInteractions(applications);
        }
    }

    // ── the add, and the journey it exists to produce ───────────────────────

    @Nested
    @DisplayName("adding a purchase")
    class Adding {

        /**
         * The whole point of the task. A purchase is what a journey is
         * instantiated from, so a purchase written without one leaves the client
         * holding a product they are not being onboarded through — one of the two
         * states {@code ObClientChildWriteRepository} names as having to be
         * noticed and repaired by hand.
         */
        @Test
        void instantiatesTheJourneyForTheProductJustBought() {
            service.add(ADMIN, CLIENT, request(PRODUCT));

            verify(applications).insert(eq(CLIENT), any());
            verify(journeys).instantiate(CLIENT, PRODUCT);
        }

        /**
         * The purchase first, then the journey — not an ordering preference.
         * {@code ObJourneyInstantiationService.instantiate} reads {@code
         * ob_client_applications} to check the product was bought and throws
         * {@code ProductNotPurchasedException} if it was not, so the reverse
         * order would fail every time.
         */
        @Test
        void writesThePurchaseBeforeInstantiatingFromIt() {
            service.add(ADMIN, CLIENT, request(PRODUCT));

            InOrder order = inOrder(applications, journeys);
            order.verify(applications).insert(eq(CLIENT), any());
            order.verify(journeys).instantiate(CLIENT, PRODUCT);
        }

        /**
         * More seats is an edit to the row already there, not a second row —
         * {@code uq_ob_client_applications} says so, and a second row would mean
         * a second journey for one product.
         */
        @Test
        void refusesAProductTheClientAlreadyBought() {
            when(reads.applicationByProduct(CLIENT, PRODUCT))
                    .thenReturn(Optional.of(applicationRow()));

            assertThatThrownBy(() -> service.add(ADMIN, CLIENT, request(PRODUCT)))
                    .isInstanceOf(DuplicateApplicationProductException.class);
            verifyNoInteractions(applications, journeys);
        }

        /** So the panel can open the purchase that is already there rather than only refusing. */
        @Test
        void namesThePurchaseThatAlreadyHoldsTheProduct() {
            when(reads.applicationByProduct(CLIENT, PRODUCT))
                    .thenReturn(Optional.of(applicationRow()));

            assertThatThrownBy(() -> service.add(ADMIN, CLIENT, request(PRODUCT)))
                    .isInstanceOfSatisfying(DuplicateApplicationProductException.class,
                            e -> assertThat(e.existingApplicationId()).isEqualTo(APPLICATION));
        }

        /**
         * A retired product is out of the picker by definition — buying one today
         * would instantiate a journey from a template nobody maintains.
         */
        @Test
        void refusesAProductThatIsNoLongerOnSale() {
            when(products.sellableProductIds(any())).thenReturn(Set.of());

            assertThatThrownBy(() -> service.add(ADMIN, CLIENT, request(PRODUCT)))
                    .isInstanceOfSatisfying(ObClientValidationException.class,
                            e -> assertThat(e.errors()).containsKey("productId"));
            verifyNoInteractions(applications, journeys);
        }

        /**
         * Checked here rather than caught out of C-103's service, whose own
         * exceptions are package-private to its package —
         * {@code ProductWithoutTemplateException}'s stated reason.
         */
        @Test
        void refusesAProductWithNoPublishedTemplate() {
            when(products.productIdsWithActiveTemplate(any())).thenReturn(Set.of());

            assertThatThrownBy(() -> service.add(ADMIN, CLIENT, request(PRODUCT)))
                    .isInstanceOf(ProductWithoutTemplateException.class);
            verifyNoInteractions(applications, journeys);
        }

        /**
         * The window is checked before the duplicate guard runs, so a caller who
         * got both wrong is told about the field they can fix rather than about a
         * conflict they cannot.
         */
        @Test
        void refusesAnInvertedLicenceWindowBeforeAnythingElseIsRead() {
            assertThatThrownBy(() -> service.add(ADMIN, CLIENT,
                    new ObClientDtos.ObApplicationWriteRequest(PRODUCT, "Subscription", 10,
                            LocalDate.of(2027, 1, 1), LocalDate.of(2026, 12, 31))))
                    .isInstanceOfSatisfying(ObClientValidationException.class,
                            e -> assertThat(e.errors()).containsKey("licenseEnd"));
            verifyNoInteractions(applications, journeys);
        }
    }

    // ── the edit, which is what a renewal is ────────────────────────────────

    @Nested
    @DisplayName("editing a purchase")
    class Editing {

        @Test
        void movesTheLicenceEndForward() {
            LocalDate renewed = LocalDate.of(2028, 7, 31);
            service.update(ADMIN, CLIENT, APPLICATION,
                    new ObClientDtos.ObApplicationWriteRequest(PRODUCT, "Subscription", 250,
                            LocalDate.of(2026, 8, 1), renewed));

            ArgumentCaptor<ObClientDtos.ObApplicationWriteRequest> saved =
                    ArgumentCaptor.forClass(ObClientDtos.ObApplicationWriteRequest.class);
            verify(applications).update(eq(APPLICATION), saved.capture());
            assertThat(saved.getValue().licenseEnd()).isEqualTo(renewed);
        }

        /** Echoing the product back is the normal case: the body is the whole representation. */
        @Test
        void acceptsTheSameProductEchoedBack() {
            service.update(ADMIN, CLIENT, APPLICATION, request(PRODUCT));

            verify(applications).update(eq(APPLICATION), any());
        }

        /**
         * Refused, not ignored. {@code ob_journeys} keys straight to
         * {@code (ob_client_id, product_id)} and the journey's template is pinned
         * to the product actually bought — accepting the request while silently
         * keeping the old product is how somebody concludes the change landed.
         */
        @Test
        void refusesAPatchNamingADifferentProduct() {
            assertThatThrownBy(() -> service.update(ADMIN, CLIENT, APPLICATION, request(OTHER_PRODUCT)))
                    .isInstanceOf(ApplicationProductImmutableException.class);
            verifyNoInteractions(applications);
        }

        /**
         * A product retired last quarter still has clients onboarding through it.
         * Refusing to renew their licence because it is no longer sold would make
         * a retirement retroactively strand everybody who already bought it — so
         * the edit asks neither question the add asks.
         */
        @Test
        void doesNotRecheckSaleOrTemplateOnAnEdit() {
            when(products.sellableProductIds(any())).thenReturn(Set.of());
            when(products.productIdsWithActiveTemplate(any())).thenReturn(Set.of());

            service.update(ADMIN, CLIENT, APPLICATION, request(PRODUCT));

            verify(applications).update(eq(APPLICATION), any());
            verifyNoInteractions(journeys);
        }

        /** An edit is an edit: no second journey, ever. */
        @Test
        void neverInstantiatesAJourney() {
            service.update(ADMIN, CLIENT, APPLICATION, request(PRODUCT));

            verifyNoInteractions(journeys);
        }

        @Test
        void refusesAnInvertedLicenceWindow() {
            assertThatThrownBy(() -> service.update(ADMIN, CLIENT, APPLICATION,
                    new ObClientDtos.ObApplicationWriteRequest(PRODUCT, null, null,
                            LocalDate.of(2027, 6, 1), LocalDate.of(2027, 5, 31))))
                    .isInstanceOfSatisfying(ObClientValidationException.class,
                            e -> assertThat(e.errors()).containsKey("licenseEnd"));
            verifyNoInteractions(applications);
        }

        /**
         * An open-ended perpetual licence has no end, and a start recorded before
         * the end has been negotiated is an ordinary state of a real purchase.
         * Only the pair, and only in the wrong order.
         */
        @Test
        void acceptsEitherDateAlone() {
            service.update(ADMIN, CLIENT, APPLICATION,
                    new ObClientDtos.ObApplicationWriteRequest(PRODUCT, "Perpetual", 120,
                            LocalDate.of(2026, 4, 15), null));
            service.update(ADMIN, CLIENT, APPLICATION,
                    new ObClientDtos.ObApplicationWriteRequest(PRODUCT, "Perpetual", 120,
                            null, LocalDate.of(2027, 4, 14)));

            verify(applications, org.mockito.Mockito.times(2)).update(eq(APPLICATION), any());
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static ObClientDtos.ObApplicationWriteRequest request(long productId) {
        return new ObClientDtos.ObApplicationWriteRequest(productId, "Subscription", 250,
                LocalDate.of(2026, 8, 1), LocalDate.of(2027, 7, 31));
    }

    private static ObClientReadRepository.ApplicationRow applicationRow() {
        return new ObClientReadRepository.ApplicationRow(APPLICATION, "Subscription", 250,
                LocalDate.of(2026, 8, 1), LocalDate.of(2027, 7, 31),
                PRODUCT, "ERP", "ERP Suite");
    }

    private static ObClientDtos.ObClientDetail detailStub() {
        return new ObClientDtos.ObClientDetail(
                CLIENT, "Acme", LocalDate.of(2026, 9, 7), "ONBOARDING", null, "LOCKED", 1, 0,
                null, List.of(), null, null, null, false,
                null, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), null, null, null);
    }
}
