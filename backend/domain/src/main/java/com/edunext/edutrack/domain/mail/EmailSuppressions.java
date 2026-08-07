package com.edunext.edutrack.domain.mail;

import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * D-034 · addresses the provider has told us are undeliverable.
 *
 * <p>In {@code domain} because both sides need it: {@code api} writes from the
 * bounce webhook, {@code worker} reads before every send, and neither module
 * depends on the other.
 */
@Component
@Lazy
public class EmailSuppressions {

    /**
     * Upsert: a second bounce for an address updates the reason and detail
     * rather than adding a row, so the pre-send check stays one indexed lookup.
     */
    private static final String SUPPRESS = """
            INSERT INTO email_suppressions (email, reason, detail, provider_msg_id)
            VALUES (:email, :reason, :detail, :providerMsgId)
            ON DUPLICATE KEY UPDATE
                reason          = VALUES(reason),
                detail          = VALUES(detail),
                provider_msg_id = VALUES(provider_msg_id),
                suppressed_at   = CURRENT_TIMESTAMP(6)
            """;

    private static final String IS_SUPPRESSED = """
            SELECT COUNT(*) FROM email_suppressions WHERE email = :email
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public EmailSuppressions(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if this is the first time we have suppressed this address */
    public boolean suppress(String email, SuppressionReason reason, String detail,
                            String providerMsgId) {
        boolean alreadyKnown = isSuppressed(email);
        jdbc.update(SUPPRESS, new MapSqlParameterSource()
                .addValue("email", email)
                .addValue("reason", reason.name())
                .addValue("detail", detail)
                .addValue("providerMsgId", providerMsgId));
        return !alreadyKnown;
    }

    public boolean isSuppressed(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        Integer count = jdbc.queryForObject(IS_SUPPRESSED,
                new MapSqlParameterSource("email", email), Integer.class);
        return count != null && count > 0;
    }

    /** Why an address is suppressed. Both stop delivery; only the cause differs. */
    public enum SuppressionReason {
        /** The provider could not deliver — mailbox gone, domain dead. */
        BOUNCE,
        /** The recipient reported the mail as spam. Continuing gets us blocklisted. */
        COMPLAINT
    }
}
