package com.soubhagya.flashreserve;

import java.util.Set;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.EntityType;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DomainModelTests {

	private static final Set<String> CORE_ENTITIES = Set.of("User", "Event", "Seat", "Booking", "Payment");

	private static final Set<String> CORE_TABLES = Set.of("users", "events", "seats", "bookings", "payments");

	@Autowired
	private EntityManager entityManager;

	@Test
	void persistenceUnitContainsAllCoreEntities() {
		Set<String> entityNames = entityManager.getMetamodel().getEntities().stream()
				.map(EntityType::getName)
				.collect(Collectors.toSet());
		assertThat(entityNames).containsAll(CORE_ENTITIES);
	}

	@Test
	@Transactional
	void coreTablesExistInDatabase() {
		for (String table : CORE_TABLES) {
			Boolean exists = (Boolean) entityManager
					.createNativeQuery("SELECT to_regclass(:tableName) IS NOT NULL")
					.setParameter("tableName", "public." + table)
					.getSingleResult();
			assertThat(exists).as("table public.%s must exist", table).isTrue();
		}
	}

}
