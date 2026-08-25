package com.soubhagya.flashreserve.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Single Redis client for the application (Redisson), used for distributed
 * reservation locking only. PostgreSQL remains the source of truth for all
 * seat/booking state.
 */
@Configuration
public class RedisConfig {

	@Bean(destroyMethod = "shutdown")
	RedissonClient redissonClient(@Value("${spring.data.redis.host:localhost}") String host,
			@Value("${spring.data.redis.port:6379}") int port) {
		Config config = new Config();
		config.useSingleServer()
				.setAddress("redis://" + host + ":" + port);
		return Redisson.create(config);
	}

}
