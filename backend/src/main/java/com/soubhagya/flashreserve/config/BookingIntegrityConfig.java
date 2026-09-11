package com.soubhagya.flashreserve.config;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Defense-in-depth integrity validator for the reservation hot path (SEC-03).
 *
 * Flyway now owns all schema DDL via {@code V1__initial_schema.sql}, including
 * the PostgreSQL partial unique index:
 * <pre>
 * CREATE UNIQUE INDEX uk_bookings_one_pending_per_seat
 * ON bookings (seat_id) WHERE status = 'PENDING';
 * </pre>
 * This guard no longer creates schema — it only <em>validates</em> that the
 * index exists after Flyway has run. Any failure propagates and fails startup
 * so a missing safety net is never silently ignored. The original
 * {@code CREATE UNIQUE INDEX IF NOT EXISTS} approach would have masked a
 * missing Flyway migration, which is why this class now validates instead of
 * mutating schema. It is kept for one release as a defense-in-depth check
 * and will be removed once Flyway ownership is proven in production.
 *
 * The predicate keeps the guarantee narrow: a seat with a settled history
 * (CONFIRMED / EXPIRED / CANCELLED rows) can still be re-reserved, but two
 * live PENDING holds for one seat are rejected by PostgreSQL itself.
 */
@Configuration
public class BookingIntegrityConfig {

	private static final Logger log = LoggerFactory.getLogger(BookingIntegrityConfig.class);

	/**
	 * Exact predicate documented for operators. Keep in sync with
	 * V1__initial_schema.sql.
	 */
	static final String PENDING_PER_SEAT_DDL = "CREATE UNIQUE INDEX uk_bookings_one_pending_per_seat "
			+ "ON bookings (seat_id) WHERE status = 'PENDING'";

	static final String PENDING_PER_SEAT_INDEX = "uk_bookings_one_pending_per_seat";

	@Bean
	ApplicationRunner bookingIntegrityGuard(DataSource dataSource) {
		return args -> {
			Integer count = new JdbcTemplate(dataSource).queryForObject(
					"SELECT COUNT(*) FROM pg_indexes WHERE indexname = ?", Integer.class,
					PENDING_PER_SEAT_INDEX);
			if (count == null || count == 0) {
				throw new IllegalStateException(
						"Required index missing: " + PENDING_PER_SEAT_INDEX
								+ " — Flyway migration V1 must create it. Expected: " + PENDING_PER_SEAT_DDL);
			}
			log.info("Booking integrity guard ready: {} verified (Flyway-owned)", PENDING_PER_SEAT_INDEX);
		};
	}

}
