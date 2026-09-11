package com.soubhagya.flashreserve.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "registration.otp")
public record RegistrationOtpProperties(

		@DefaultValue("5m") Duration ttl,

		@DefaultValue("5") int maxAttempts,

		@DefaultValue("60s") Duration resendCooldown,

		@DefaultValue("5") int maxSendsPerHour) {
}
