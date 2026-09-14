package com.soubhagya.flashreserve.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Canonical Spring Boot binding for the CORS allow-list environment variable.
 *
 * <p>{@code CORS_ALLOWED_ORIGINS} is the exact environment-variable form of the
 * property {@code cors.allowed-origins} under Spring Boot's documented naming
 * contract (dots become underscores, dashes are removed, the name is
 * upper-cased). Binding through {@code @ConfigurationProperties} therefore
 * resolves the variable through the standard {@code SystemEnvironmentPropertySource}
 * machinery - the same mechanism Boot uses for every container/deployment
 * variable - even when the deployed jar carries no {@code application.properties}
 * at all. This is the preferred consumption path over manual environment reads.
 *
 * <p>Kept as a raw string so {@link CorsConfig} can apply the shared
 * comma-splitting, whitespace-trimming and wildcard rejection rules.
 */
@ConfigurationProperties(prefix = "cors")
public record CorsProperties(String allowedOrigins) {
}