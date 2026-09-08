package com.edunext.edutrack.api.config;

import org.openapitools.jackson.nullable.JsonNullableModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * C-108 · registers {@link JsonNullableModule} explicitly rather than relying
 * on Jackson's {@code ObjectMapper.findModules()} service-loader discovery —
 * whether Spring Boot's autoconfigured {@code ObjectMapper} calls that
 * varies by version and is not worth re-deriving per upgrade. A {@code
 * com.fasterxml.jackson.databind.Module} bean is always picked up by
 * {@code JacksonAutoConfiguration}, so this is one line either way.
 */
@Configuration
public class JacksonNullableConfig {

    @Bean
    JsonNullableModule jsonNullableModule() {
        return new JsonNullableModule();
    }
}
