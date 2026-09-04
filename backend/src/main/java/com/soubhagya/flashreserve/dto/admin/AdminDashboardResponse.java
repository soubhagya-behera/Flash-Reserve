package com.soubhagya.flashreserve.dto.admin;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.soubhagya.flashreserve.entity.Booking;
import com.soubhagya.flashreserve.entity.Event;
import com.soubhagya.flashreserve.entity.enums.BookingStatus;

/**
 * Read-only ADMIN dashboard snapshot. Plain JSON (not a PagedModel) because
 * it is a metrics snapshot, not a catalog. Revenue only ever reflects
 * SUCCESS payments on CONFIRMED bookings — PENDING, FAILED and REFUNDED
 * payments and non-CONFIRMED bookings are never counted. Deliberately
 * excludes user identity and all payment/provider identifiers.
 */
public record AdminDashboardResponse(EventMetrics events, BookingMetrics bookings,
		RevenueMetrics revenue, SeatMetrics seats, List<RecentBooking> recentBookings) {

	public record EventMetrics(long total, long published, long draft,
			long cancelled, long completed, long upcomingPublished) { }

	public record BookingMetrics(long total, long pending, long confirmed,
			long expired, long cancelled) { }

	public record RevenueMetrics(long confirmedPaymentCount, BigDecimal confirmedRevenue) { }

	public record SeatMetrics(long total, long available, long held, long booked) { }

	/**
	 * Minimal newest-booking summary: event and seat context only. The
	 * booker identity, payment reference and Razorpay ids are never part
	 * of the dashboard payload.
	 */
	public record RecentBooking(UUID bookingId, BookingStatus status, Instant createdAt,
			UUID eventId, String eventName, String venue, Instant eventDate,
			String seatNumber) {

		public static RecentBooking from(Booking booking) {
			Event event = booking.getEvent();
			return new RecentBooking(booking.getId(), booking.getStatus(),
					booking.getCreatedAt(), event.getId(), event.getName(),
					event.getVenue(), event.getEventDate(),
					booking.getSeat().getSeatNumber());
		}
	}

}