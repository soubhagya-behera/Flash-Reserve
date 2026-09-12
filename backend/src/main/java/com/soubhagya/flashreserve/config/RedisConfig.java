package com.soubhagya.flashreserve.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Single Redis client for the application (Redisson): distributed reservation
 * locking plus best-effort seat-update pub/sub. PostgreSQL remains the source
 * of truth for all seat/booking state; pub/sub failure never affects
 * correctness.
 */
@Configuration
public class RedisConfig {

	@Bean(destroyMethod = "shutdown")
	RedissonClient redissonClient(@Value("${spring.data.redis.host:localhost}") String host,
			@Value("${spring.data.redis.port:6379}") int port,
			@Value("${spring.data.redis.password:}") String password,
			@Value("${spring.data.redis.ssl.enabled:false}") boolean sslEnabled) {
		Config config = new Config();
		String scheme = sslEnabled ? "rediss://" : "redis://";
		var server = config.useSingleServer()
				.setAddress(scheme + host + ":" + port);
		if (password != null && !password.isBlank()) {
			server.setPassword(password);
		}
		return Redisson.create(config);
	}

	/**
	 * One shared seat-update subscription per instance (never per browser).
	 * Inbound envelopes go to the publisher, which drops this instance's own
	 * echo by origin id and fans out the rest locally by event id.
	 */
	@Bean(destroyMethod = "removeAllListeners")
	org.redisson.api.RTopic seatUpdatesTopic(RedissonClient redisson,
			com.soubhagya.flashreserve.service.SeatStatusPublisher publisher) {
		org.redisson.api.RTopic topic = redisson
				.getTopic(com.soubhagya.flashreserve.service.SeatStatusPublisher.TOPIC_NAME);
		topic.addListener(String.class, (channel, message) -> publisher.handleInbound(message));
		return topic;
	}

}
