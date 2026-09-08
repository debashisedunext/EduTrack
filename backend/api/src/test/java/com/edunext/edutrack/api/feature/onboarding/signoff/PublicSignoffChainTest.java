package com.edunext.edutrack.api.feature.onboarding.signoff;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A-120 · the public tree is reachable without a session, and nothing else is.
 *
 * <h2>Why this needs asserting separately</h2>
 *
 * <p>Every other route in the module is {@code .authenticated()} by
 * {@code SecurityConfig}'s catch-all, which is the right default and the reason
 * new endpoints are protected without anybody remembering. The sign-off surface
 * is the one exception the contract declares — {@code security: []} — because
 * the caller is a customer following a link from an email who holds a token and
 * no session.
 *
 * <p>An exception carved into a catch-all is exactly the kind of thing that
 * gets carved too wide. So this asserts both halves: the public prefix is open,
 * and the tree beside it is not.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
        "spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
})
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
@AutoConfigureMockMvc
class PublicSignoffChainTest {

    private static final String PUBLIC_OTP = "/api/v1/public/onboarding/signoff/otp";
    private static final String STAFF_SIGNOFFS = "/api/v1/onboarding/signoffs";

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("the public sign-off tree is not refused by the chain")
    void publicTreeIsOpen() throws Exception {
        // Not asserting 200: A-121 builds the handler and there is none yet, so
        // this answers 404 or 405 today. What must NOT happen is 401 — that is
        // the chain refusing the caller the surface exists for, and it is what
        // an un-permitted prefix produces.
        mvc.perform(post(PUBLIC_OTP).contentType("application/json").content("{\"token\":\"x\"}"))
                .andExpect(status().is(org.hamcrest.Matchers.not(401)));
    }

    @Test
    @DisplayName("the staff sign-off routes beside it still require a session")
    void theStaffTreeStaysClosed() throws Exception {
        // The counterweight, and the failure it catches is a prefix written one
        // segment too short — "/api/v1/public/**" would be fine, but a slip to
        // "/api/v1/**" opens the product. Asserted as 401 specifically, because
        // that is what the chain does to an unauthenticated caller and a 404
        // here would mean the route simply does not exist yet.
        mvc.perform(post(STAFF_SIGNOFFS).contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an unauthenticated caller is refused everywhere else too")
    void everythingElseStaysClosed() throws Exception {
        mvc.perform(post("/api/v1/tickets").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
