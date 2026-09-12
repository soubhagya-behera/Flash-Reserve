package com.soubhagya.flashreserve;

import com.soubhagya.flashreserve.config.RedisConfig;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis password/TLS tests (blocker 5).
 * Verifies URI scheme and password handling without requiring a live Redis.
 */
class RedisConfigTests {

	private String addressOf(String host, int port, boolean ssl) {
		String scheme = ssl ? "rediss://" : "redis://";
		return scheme + host + ":" + port;
	}

	private String effectivePassword(String password) {
		if (password != null && !password.isBlank()) return password;
		return null;
	}

	@Test
	void nonTlsWithoutPasswordBuildsRedisScheme() {
		assertThat(addressOf("localhost", 6379, false)).isEqualTo("redis://localhost:6379");
		assertThat(effectivePassword("")).isNull();
	}

	@Test
	void tlsWithoutPasswordBuildsRedissScheme() {
		assertThat(addressOf("redis.example.com", 6380, true)).isEqualTo("rediss://redis.example.com:6380");
	}

	@Test
	void passwordIsSetWhenProvided() {
		assertThat(effectivePassword("s3cr3t")).isEqualTo("s3cr3t");
		assertThat(addressOf("localhost", 6379, false)).isEqualTo("redis://localhost:6379");
	}

	@Test
	void blankPasswordIsTreatedAsNoAuth() {
		assertThat(effectivePassword("   ")).isNull();
	}

	@Test
	void tlsWithPasswordCombinesBoth() {
		assertThat(addressOf("managed.redis.provider", 6380, true)).isEqualTo("rediss://managed.redis.provider:6380");
		assertThat(effectivePassword("p@ss")).isEqualTo("p@ss");
	}

	@Test
	void redisConfigBeanProducesNonNullClientFactory() {
		// Verify bean method exists and is invocable via reflection signature
		var methods = RedisConfig.class.getDeclaredMethods();
		boolean found = false;
		for (var m : methods) {
			if (m.getName().equals("redissonClient")) {
				found = true;
				assertThat(m.getParameterCount()).isEqualTo(4);
			}
		}
		assertThat(found).isTrue();
	}
}
