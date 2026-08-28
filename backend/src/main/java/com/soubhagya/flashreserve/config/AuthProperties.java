package com.soubhagya.flashreserve.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "auth")
public record AuthProperties(

		@DefaultValue RateLimit login,

		@DefaultValue RateLimit registration) {

	public record RateLimit(

			@DefaultValue("10") int capacity,

			@DefaultValue("1m") Duration refillPeriod) {

	}

}