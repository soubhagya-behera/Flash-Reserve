package com.soubhagya.flashreserve;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DatasourceDefaultsTests {

	private Properties loadExample() throws IOException {
		Properties props = new Properties();
		try (InputStream is = getClass().getClassLoader().getResourceAsStream("application-example.properties")) {
			assertThat(is).isNotNull();
			props.load(is);
		}
		return props;
	}

	@Test
	void defaultUrlFallbackIsLocalhost() throws IOException {
		Properties p = loadExample();
		// fallback after colon is localhost
		assertThat(p.getProperty("spring.datasource.url")).contains("localhost:5432/flashreserve");
	}

	@Test
	void passwordPlaceholderHasEmptyDefault() throws IOException {
		Properties p = loadExample();
		assertThat(p.getProperty("spring.datasource.password")).isEqualTo("${DB_PASSWORD:}");
	}
}
