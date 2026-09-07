package com.soubhagya.flashreserve.config;

import java.util.Map;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.Profiles;
import org.springframework.core.env.StandardEnvironment;

/**
 * Production-profile hardening initializer (security findings WEB-01, AUTH-01).
 *
 * There is no committed application-production.properties; instead, this
 * initializer applies the production-only configuration whenever the
 * {@code production} profile is active, and does nothing otherwise:
 *
 * <ul>
 *   <li><b>WEB-01:</b> Springdoc is disabled, so {@code /v3/api-docs} and
 *       {@code /swagger-ui/**} answer 404 and the API contract cannot be
 *       discovered by anonymous users. Development (no profile) is
 *       unaffected.</li>
 *   <li><b>AUTH-01:</b> {@code jwt.secret} is bound to the JWT_SECRET
 *       environment variable with NO fallback - startup fails when the
 *       variable is missing/empty or matches a known placeholder
 *       (denied by JwtService).</li>
 * </ul>
 *
 * The injected property source is placed directly below the JVM system
 * properties so tests can still inject jwt.secret via system property,
 * while environment variables and configuration files can never re-enable
 * the documentation endpoints in production. A deliberate JVM flag
 * (-Dspringdoc.api-docs.enabled=true) remains the only override.
 */
public class ProductionProfileInitializer
		implements ApplicationContextInitializer<ConfigurableApplicationContext>, Ordered {

	/** Name of the injected property source. */
	public static final String PROPERTY_SOURCE_NAME = "flashreserveProductionHardening";

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE;
	}

	@Override
	public void initialize(ConfigurableApplicationContext context) {
		ConfigurableEnvironment environment = context.getEnvironment();
		if (!environment.acceptsProfiles(Profiles.of("production"))) {
			return;
		}

		Map<String, Object> hardening = Map.of(
				// WEB-01: no public API contract in production.
				"springdoc.api-docs.enabled", "false",
				"springdoc.swagger-ui.enabled", "false",
				// AUTH-01: production signing key must come from the environment.
				"jwt.secret", "${JWT_SECRET:}");

		MutablePropertySources sources = environment.getPropertySources();
		MapPropertySource propertySource = new MapPropertySource(PROPERTY_SOURCE_NAME, hardening);
		if (sources.contains(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)) {
			sources.addAfter(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, propertySource);
		}
		else {
			sources.addLast(propertySource);
		}
	}

}
