package com.soubhagya.flashreserve.config;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Defense-in-depth integrity guard for the reservation hot path (SEC-03).
 *
 * The reservation flow stays exactly as before: per-seat Redis locking first,
 * then a short PostgreSQL transaction guarded by Seat {@code @Version}
 * optimistic locking as the authoritative correctness mechanism. This guard
 * adds one PostgreSQL partial unique index underneath, so even a complete
 * bypass of both application layers (two nodes racing without the Redis lock
 * AND with a stale seat read) can never persist two PENDING bookings for the
 * same seat:
 *
 * <pre>
 * CREATE UNIQUE INDEX IF NOT EXISTS uk_bookings_one_pending_per_seat
 * ON bookings (seat_id)
 * WHERE status = 'PENDING';
 * </pre>
 *
 * The predicate keeps the guarantee narrow: a seat with a settled history
 * (CONFIRMED / EXPIRED / CANCELLED rows) can still be re-reserved, but two
 * live PENDING holds for one seat are rejected by PostgreSQL itself.
 *
 * No Flyway/Liquibase is introduced. The DDL runs once at startup through
 * plain {@link JdbcTemplate}; any failure propagates and fails startup so a
 * missing safety net is never silently ignored. In particular, pre-existing
 * duplicate PENDING rows abort startup with the PostgreSQL unique-violation
 * instead of being cleaned up automatically - the operator resolves the data
 * first, then restarts. Keep the SQL above as the documented manual migration
 * for operators who prefer to apply it themselves.
 */
@Configuration
public class BookingIntegrityConfig {

	private static final Logger log = LoggerFactory.getLogger(BookingIntegrityConfig.class);

	/**
	 * Exact DDL enforced at startup. Keep in sync with the documented manual
	 * migration SQL in the class javadoc.
	 */
	static final String PENDING_PER_SEAT_DDL = "CREATE UNIQUE INDEX IF NOT EXISTS "
			+ "uk_bookings_one_pending_per_seat ON bookings (seat_id) WHERE status = 'PENDING'";

	@Bean
	ApplicationRunner bookingIntegrityGuard(DataSource dataSource) {
		return args -> {
			new JdbcTemplate(dataSource).execute(PENDING_PER_SEAT_DDL);
			log.info("Booking integrity guard ready: uk_bookings_one_pending_per_seat enforced");
		};
	}

}
