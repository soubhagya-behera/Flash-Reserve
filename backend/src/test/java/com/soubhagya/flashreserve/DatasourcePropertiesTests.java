package com.soubhagya.flashreserve;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Datasource env-driven tests (blocker 4) - verifies placeholders without needing DB.
 */
class DatasourcePropertiesTests {

	private Properties loadExample() throws IOException {
		Properties props = new Properties();
		try (InputStream is = getClass().getClassLoader().getResourceAsStream("application-example.properties")) {
			assertThat(is).isNotNull();
			props.load(is);
		}
		return props;
	}

	@Test
	void datasourceUrlIsEnvDriven() throws IOException {
		Properties p = loadExample();
		String url = p.getProperty("spring.datasource.url");
		assertThat(url).contains("${DATABASE_URL:");
		assertThat(url).contains("jdbc:postgresql://localhost:5432/flashreserve");
	}

	@Test
	void datasourceUsernameIsEnvDriven() throws IOException {
		Properties p = loadExample();
		String v = p.getProperty("spring.datasource.username");
		assertThat(v).contains("${DB_USERNAME:");
	}

	@Test
	void datasourcePasswordIsEnvDriven() throws IOException {
		Properties p = loadExample();
		String v = p.getProperty("spring.datasource.password");
		assertThat(v).contains("${DB_PASSWORD:");
	}

	@Test
	void hibernateDdlAutoIsValidate() throws IOException {
		Properties p = loadExample();
		assertThat(p.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
	}

	@Test
	void flywayIsEnabled() throws IOException {
		Properties p = loadExample();
		assertThat(p.getProperty("spring.flyway.enabled")).isEqualTo("true");
	}
}
