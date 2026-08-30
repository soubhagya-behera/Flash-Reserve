package com.soubhagya.flashreserve.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Single OpenAPI definition for FlashReserve. Public endpoints (auth, event
 * browsing) carry no security requirement; protected controllers declare
 * {@code @SecurityRequirement} themselves so Swagger reflects the real
 * security rules instead of showing locks everywhere.
 */
@Configuration
public class OpenApiConfig {

	private static final String BEARER_SCHEME = "bearerAuth";

	@Bean
	OpenAPI flashReserveOpenApi() {
		return new OpenAPI()
				.info(new Info()
						.title("FlashReserve API")
						.description("""
								High-throughput flash-sale and ticket reservation engine.

								Flow: register or log in to receive a JWT, browse published events,
								reserve an available seat (short hold), pay via Razorpay TEST MODE,
								then verify the payment to confirm the booking. Unpaid holds expire
								automatically and release the seat.

								Authentication: HTTP Bearer JWT. Send {@code Authorization: Bearer <JWT>}
								issued by the login or registration endpoint. Reservations, bookings,
								payments and administration require it.""")
						.version("v1"))
				.components(new Components().addSecuritySchemes(BEARER_SCHEME,
						new SecurityScheme()
								.name(BEARER_SCHEME)
								.type(SecurityScheme.Type.HTTP)
								.scheme("bearer")
								.bearerFormat("JWT")
								.description("Paste the JWT from /api/auth/login or /api/auth/register. "
										+ "Swagger sends it as: Authorization: Bearer <JWT>")));
	}

}
