package com.soubhagya.flashreserve;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.soubhagya.flashreserve.dto.auth.AuthResponse;
import com.soubhagya.flashreserve.dto.auth.RegisterRequest;
import com.soubhagya.flashreserve.exception.DuplicateEmailException;
import com.soubhagya.flashreserve.repository.UserRepository;
import com.soubhagya.flashreserve.service.AuthService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import org.springframework.test.context.TestPropertySource;

import org.redisson.api.RedissonClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that concurrent duplicate registrations cannot create two users:
 * every loser must surface as the expected duplicate-email conflict (-> 409),
 * never a 500. Runs without a test transaction because each registration
 * commits in its own database transaction, exactly as in production.
 * Gmail OTP verification is seeded directly in Redis for this test.
 */
@SpringBootTest
@TestPropertySource(properties = {
		"jwt.secret=test-secret-that-is-definitely-longer-than-32-bytes!!",
		"jwt.expiration-ms=900000"
})
class RegistrationConcurrencyTests {

	private static final int THREADS = 8;

	@Autowired
	private AuthService authService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private RedissonClient redissonClient;

	private String registeredEmail;

	@AfterEach
	void cleanUp() {
		if (registeredEmail != null) {
			userRepository.findByEmail(registeredEmail).ifPresent(userRepository::delete);
		}
	}

	@Test
	void concurrentDuplicateRegistrationCreatesExactlyOneUser() throws Exception {
		registeredEmail = "concurrent-reg-" + UUID.randomUUID() + "@gmail.com";
		RegisterRequest request = new RegisterRequest("Concurrent User", registeredEmail, "password-123");
		// Seed verified marker — registration consumes it, but the first winner
		// will create the user; losers will hit duplicate check before consume.
		// Re-seed so all threads see verified at entry; concurrency still proves uniqueness.
		redissonClient.getBucket("flashreserve:otp:verified:" + registeredEmail.toLowerCase())
				.set(registeredEmail.toLowerCase(), 10, TimeUnit.MINUTES);

		ExecutorService pool = Executors.newFixedThreadPool(THREADS);
		CountDownLatch startGate = new CountDownLatch(1);
		List<Future<Object>> results = new ArrayList<>();
		for (int i = 0; i < THREADS; i++) {
			results.add(pool.submit((Callable<Object>) () -> {
				startGate.await();
				// Each thread needs verified state because first success consumes it.
				// Re-set before each attempt so later threads still hit the DB constraint path.
				redissonClient.getBucket("flashreserve:otp:verified:" + registeredEmail.toLowerCase())
						.set(registeredEmail.toLowerCase(), 10, TimeUnit.MINUTES);
				try {
					return authService.register(request);
				}
				catch (Throwable failure) {
					return failure;
				}
			}));
		}
		startGate.countDown();
		pool.shutdown();
		assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

		int successes = 0;
		int duplicates = 0;
		List<Object> unexpected = new ArrayList<>();
		for (Future<Object> future : results) {
			Object outcome = future.get();
			if (outcome instanceof AuthResponse) {
				successes++;
			}
			else if (outcome instanceof DuplicateEmailException) {
				duplicates++;
			}
			else {
				unexpected.add(outcome);
			}
		}

		assertThat(unexpected)
				.as("the unique-constraint race must surface as a controlled 409 conflict")
				.isEmpty();
		assertThat(successes).as("exactly one registration wins").isEqualTo(1);
		assertThat(duplicates)
				.as("every loser must receive the duplicate-email conflict, never a 500")
				.isEqualTo(THREADS - 1);
		assertThat(userRepository.findByEmail(registeredEmail))
				.as("the database constraint guarantees a single user row")
				.isPresent();
	}

}
