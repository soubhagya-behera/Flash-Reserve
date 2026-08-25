package com.soubhagya.flashreserve.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "reservation")
public record ReservationProperties(

		@DefaultValue("5m") Duration holdDuration,

		@DefaultValue("30s") Duration expirationInterval,

		@DefaultValue("2s") Duration lockWaitDuration) {

}
