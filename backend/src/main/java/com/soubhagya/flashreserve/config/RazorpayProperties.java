package com.soubhagya.flashreserve.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Razorpay TEST MODE credentials and currency. The key secret is supplied only
 * through configuration (environment or the local, git-ignored
 * application.properties) and is never serialised, logged, or returned to any
 * client. Left blank, order creation fails fast with a controlled error.
 */
@ConfigurationProperties(prefix = "razorpay")
public record RazorpayProperties(

		String keyId,

		String keySecret,

		@DefaultValue("INR") String currency) {

}
