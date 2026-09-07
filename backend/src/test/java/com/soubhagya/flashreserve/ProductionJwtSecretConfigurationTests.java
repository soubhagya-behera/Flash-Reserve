package com.soubhagya.flashreserve;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.soubhagya.flashreserve.security.JwtService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Production JWT secret fail-fast behavior (security finding AUTH-01).
 *
 * The {@code production} profile binds jwt.secret to the JWT_SECRET
 * environment variable with NO usable fallback, and JwtService rejects known
 * placeholders. These tests prove:
 *   1. production startup fails when JWT_SECRET is missing/empty
 *   2. production startup fails when JWT_SECRET is a known placeholder
 *   3. production startup succeeds with a valid environment-provided secret
 *
 * The system property JWT_SECRET (which takes precedence over the process
 * environment in Spring's property-source order) is used to simulate the
 * environment variable deterministically and is restored after every test.
 * Surefire also defines jwt.secret as a JVM system property for the whole
 * test run; that override is cleared for the duration of each test so the
 * production profile's own resolution (like a real deployment) applies, and
 * is restored afterwards. No real secret value is ever printed; test secrets
 * are throwaway values defined inline.
 */
class ProductionJwtSecretConfigurationTests {

	private static final String KNOWN_PLACEHOLDER_SECRET =
			"change-me-in-local-environment-only-min-32-chars-long";

	private static final String VALID_TEST_SECRET =
			"production-profile-test-secret-0123456789abcdef0123456789abcdef";

	/** Value captured before the tests; restored so other test classes are unaffected. */
	private static String originalJwtSecretProperty;

	private ConfigurableApplicationContext startedContext;

	@BeforeAll
	static void captureInjectedJwtSecretProperty() {
		originalJwtSecretProperty = System.getProperty("jwt.secret");
	}

	@BeforeEach
	void removeTestJvmJwtSecretOverride() {
		// Surefire sets jwt.secret as a system property for the entire test
		// JVM; a real deployment has no such override, so remove it here.
		System.clearProperty("jwt.secret");
	}

	@AfterEach
	void restoreEnvironmentAndCloseContext() {
		if (startedContext != null) {
			startedContext.close();
			startedContext = null;
		}
		System.clearProperty("JWT_SECRET");
		if (originalJwtSecretProperty != null) {
			System.setProperty("jwt.secret", originalJwtSecretProperty);
		}
	}

	private void setJwtSecret(String value) {
		System.setProperty("JWT_SECRET", value);
	}

	/** Runs the app with the production profile; returns the root failure message or null. */
	private String startProductionAndCaptureRootFailure() {
		try {
			startedContext = new SpringApplicationBuilder(FlashreserveApplication.class)
					.properties("spring.profiles.active=production",
							"spring.main.web-application-type=none")
					.run();
			return null;
		} catch (Throwable failure) {
			Throwable t = failure;
			while (t.getCause() != null && t.getCause() != t) {
				t = t.getCause();
			}
			return t.getMessage();
		}
	}

	@Test
	void productionFailsWhenJwtSecretIsMissingOrEmpty() {
		setJwtSecret("");
		String rootMessage = startProductionAndCaptureRootFailure();
		assertNotNull(rootMessage, "production startup must fail without JWT_SECRET");
		assertTrue(rootMessage.contains("at least 32 characters"),
				"expected the signing-key length guard, got: " + rootMessage);
	}

	@Test
	void productionRejectsKnownPlaceholderSecret() {
		setJwtSecret(KNOWN_PLACEHOLDER_SECRET);
		String rootMessage = startProductionAndCaptureRootFailure();
		assertNotNull(rootMessage, "production startup must fail with a known placeholder secret");
		assertTrue(rootMessage.contains("known placeholder"),
				"expected the placeholder denylist guard, got: " + rootMessage);
	}

	@Test
	void productionStartsWithValidJwtSecret() {
		setJwtSecret(VALID_TEST_SECRET);
		String rootMessage = startProductionAndCaptureRootFailure();
		assertNotNull(startedContext, "production startup must succeed with a valid secret, got: " + rootMessage);
		assertNotNull(startedContext.getBean(JwtService.class),
				"JwtService must be available with the environment-provided secret");
	}

}
