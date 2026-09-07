package com.soubhagya.flashreserve;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Development/default Swagger + OpenAPI exposure behavior (WEB-01 baseline).
 *
 * With no profile active, Springdoc must keep serving the API contract and
 * the interactive UI exactly as before the production hardening, and the
 * documented permitAll rules for these paths must keep working.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SwaggerDocumentationIntegrationTests {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void openApiJsonIsPubliclyAvailableInDevelopment() throws Exception {
		mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.openapi").isNotEmpty());
	}

	@Test
	void swaggerConfigIsAvailableInDevelopment() throws Exception {
		mockMvc.perform(get("/v3/api-docs/swagger-config"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
	}

	@Test
	void swaggerUiIsPubliclyAvailableInDevelopment() throws Exception {
		mockMvc.perform(get("/swagger-ui/index.html"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
	}

	@Test
	void swaggerUiAliasStillRedirectsInDevelopment() throws Exception {
		mockMvc.perform(get("/swagger-ui.html"))
				.andExpect(status().is3xxRedirection());
	}

	@Test
	void documentationPermitRulesDoNotWeakenApiSecurityInDevelopment() throws Exception {
		// Anonymous access must stay limited to the documentation endpoints.
		mockMvc.perform(get("/api/bookings")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/admin/events")).andExpect(status().isUnauthorized());
	}

}
