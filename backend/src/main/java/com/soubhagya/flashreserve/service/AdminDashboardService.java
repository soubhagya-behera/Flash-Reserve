package com.soubhagya.flashreserve.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.soubhagya.flashreserve.dto.admin.AdminDashboardResponse;
import com.soubhagya.flashreserve.entity.enums.BookingStatus;
import com.soubhagya.flashreserve.entity.enums.EventStatus;
import com.soubhagya.flashreserve.entity.enums.PaymentStatus;
import com.soubhagya.flashreserve.entity.enums.SeatStatus;
import com.soubhagya.flashreserve.repository.BookingRepository;
import com.soubhagya.flashreserve.repository.EventRepository;
import com.soubhagya.flashreserve.repository.PaymentRepository;
import com.soubhagya.flashreserve.repository.SeatRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * Assembles the ADMIN dashboard snapshot from database-level aggregates.
 * Nothing is loaded in bulk and summed in Java: status counts come from
 * grouped queries, revenue from one join aggregate, and recent bookings
 * reuse the existing paged admin booking read (one limited statement).
 */
@Service
@RequiredArgsConstructor
public class AdminDashboardService {

	private static final int RECENT_BOOKINGS_LIMIT = 5;

	private final EventRepository eventRepository;

	private final BookingRepository bookingRepository;

	private final PaymentRepository paymentRepository;

	private final SeatRepository seatRepository;

	private final BookingService bookingService;

	@Transactional(readOnly = true)
	public AdminDashboardResponse getDashboard() {
		return new AdminDashboardResponse(
				eventMetrics(),
				bookingMetrics(),
				revenueMetrics(),
				seatMetrics(),
				recentBookings());
	}

	private AdminDashboardResponse.EventMetrics eventMetrics() {
		Map<EventStatus, Long> byStatus = toCountMap(
				eventRepository.countGroupedByStatus(),
				EventRepository.EventStatusCount::getStatus,
				EventRepository.EventStatusCount::getTotal);
		long upcoming = eventRepository.countByStatusAndEventDateAfter(
				EventStatus.PUBLISHED, Instant.now());
		return new AdminDashboardResponse.EventMetrics(
				eventRepository.count(),
				byStatus.getOrDefault(EventStatus.PUBLISHED, 0L),
				byStatus.getOrDefault(EventStatus.DRAFT, 0L),
				byStatus.getOrDefault(EventStatus.CANCELLED, 0L),
				byStatus.getOrDefault(EventStatus.COMPLETED, 0L),
				upcoming);
	}

	private AdminDashboardResponse.BookingMetrics bookingMetrics() {
		Map<BookingStatus, Long> byStatus = toCountMap(
				bookingRepository.countGroupedByStatus(),
				BookingRepository.BookingStatusCount::getStatus,
				BookingRepository.BookingStatusCount::getTotal);
		return new AdminDashboardResponse.BookingMetrics(
				bookingRepository.count(),
				byStatus.getOrDefault(BookingStatus.PENDING, 0L),
				byStatus.getOrDefault(BookingStatus.CONFIRMED, 0L),
				byStatus.getOrDefault(BookingStatus.EXPIRED, 0L),
				byStatus.getOrDefault(BookingStatus.CANCELLED, 0L));
	}

	private AdminDashboardResponse.RevenueMetrics revenueMetrics() {
		PaymentRepository.RevenueTotals totals = paymentRepository.sumRevenue(
				PaymentStatus.SUCCESS, BookingStatus.CONFIRMED);
		return new AdminDashboardResponse.RevenueMetrics(
				totals.getPaymentCount(), totals.getTotalAmount());
	}

	private AdminDashboardResponse.SeatMetrics seatMetrics() {
		Map<SeatStatus, Long> byStatus = toCountMap(
				seatRepository.countGroupedByStatus(),
				SeatRepository.SeatStatusCount::getStatus,
				SeatRepository.SeatStatusCount::getTotal);
		return new AdminDashboardResponse.SeatMetrics(
				seatRepository.count(),
				byStatus.getOrDefault(SeatStatus.AVAILABLE, 0L),
				byStatus.getOrDefault(SeatStatus.HELD, 0L),
				byStatus.getOrDefault(SeatStatus.BOOKED, 0L));
	}

	private List<AdminDashboardResponse.RecentBooking> recentBookings() {
		PageRequest newestFirst = PageRequest.of(0, RECENT_BOOKINGS_LIMIT,
				Sort.by(Sort.Direction.DESC, "createdAt"));
		return bookingService.getBookingsForAdmin(null, null, newestFirst).getContent()
				.stream()
				.map(AdminDashboardResponse.RecentBooking::from)
				.toList();
	}

	/** Folds grouped rows into a status -> count map; absent statuses stay absent. */
	private <S, P> Map<S, Long> toCountMap(List<P> rows, Function<P, S> status,
			Function<P, Long> total) {
		return rows.stream().collect(Collectors.toMap(status, total));
	}

}