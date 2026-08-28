package com.soubhagya.flashreserve;

import java.time.Instant;
import java.util.UUID;

import com.soubhagya.flashreserve.entity.enums.BookingStatus;
import com.soubhagya.flashreserve.entity.enums.EventStatus;
import com.soubhagya.flashreserve.entity.enums.SeatStatus;
import com.soubhagya.flashreserve.exception.ResourceNotFoundException;
import com.soubhagya.flashreserve.repository.BookingRepository;
import com.soubhagya.flashreserve.repository.EventRepository;
import com.soubhagya.flashreserve.repository.PaymentRepository;
import com.soubhagya.flashreserve.repository.SeatRepository;
import com.soubhagya.flashreserve.repository.UserRepository;
import com.soubhagya.flashreserve.service.BookingService;
import com.soubhagya.flashreserve.service.EventService;
import com.soubhagya.flashreserve.service.PaymentService;
import com.soubhagya.flashreserve.service.SeatService;
import com.soubhagya.flashreserve.service.UserService;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class RepositoryServiceTests {

	private static final UUID UNKNOWN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private static final String UNKNOWN_EMAIL = "missing@example.test";

	private static final PageRequest LARGE_PAGE = PageRequest.of(0, 10_000);

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EventRepository eventRepository;

	@Autowired
	private SeatRepository seatRepository;

	@Autowired
	private BookingRepository bookingRepository;

	@Autowired
	private PaymentRepository paymentRepository;

	@Autowired
	private UserService userService;

	@Autowired
	private EventService eventService;

	@Autowired
	private SeatService seatService;

	@Autowired
	private BookingService bookingService;

	@Autowired
	private PaymentService paymentService;

	@Test
	void repositoriesAreDiscovered() {
		assertThat(userRepository).isNotNull();
		assertThat(eventRepository).isNotNull();
		assertThat(seatRepository).isNotNull();
		assertThat(bookingRepository).isNotNull();
		assertThat(paymentRepository).isNotNull();
	}

	@Test
	void allDerivedQueryMethodsExecuteSuccessfully() {
		long publishedBefore = eventRepository.findByStatus(EventStatus.PUBLISHED, LARGE_PAGE).getTotalElements();

		assertThat(userRepository.existsByEmail(UNKNOWN_EMAIL)).isFalse();

		assertThat(eventRepository.findByStatus(EventStatus.PUBLISHED, LARGE_PAGE).getTotalElements())
				.isEqualTo(publishedBefore);

		assertThat(seatRepository.findByEventId(UNKNOWN_ID)).isEmpty();
		assertThat(seatRepository.findByEventIdAndSeatNumber(UNKNOWN_ID, "A-1")).isEmpty();
		assertThat(seatRepository.findByEventIdAndStatus(UNKNOWN_ID, SeatStatus.AVAILABLE)).isEmpty();

		assertThat(bookingRepository.findByUserId(UNKNOWN_ID)).isEmpty();
		assertThat(bookingRepository.findByEventId(UNKNOWN_ID)).isEmpty();
		assertThat(bookingRepository.findBySeatId(UNKNOWN_ID)).isEmpty();
		assertThat(bookingRepository.findByStatus(BookingStatus.PENDING)).isEmpty();
		assertThat(bookingRepository.findByStatusAndExpiresAtBefore(BookingStatus.PENDING, Instant.now())).isEmpty();

		assertThat(paymentRepository.findByBookingId(UNKNOWN_ID)).isEmpty();
		assertThat(paymentRepository.findByPaymentReference("missing-reference")).isEmpty();
	}

	@Test
	void servicesAreWiredAndCommunicateMissingEntities() {
		long publishedBefore = eventService.getPublishedEvents(LARGE_PAGE).getTotalElements();
		assertThat(userService.existsByEmail(UNKNOWN_EMAIL)).isFalse();
		assertThat(eventService.getPublishedEvents(LARGE_PAGE).getTotalElements()).isEqualTo(publishedBefore);
		assertThat(seatService.getSeatsForEvent(UNKNOWN_ID)).isEmpty();
		assertThat(seatService.getSeatsForEvent(UNKNOWN_ID, SeatStatus.AVAILABLE)).isEmpty();
		assertThat(seatService.getAvailableSeats(UNKNOWN_ID)).isEmpty();
		assertThat(bookingService.getBookingsByUser(UNKNOWN_ID)).isEmpty();
		assertThat(bookingService.getBookingsByEvent(UNKNOWN_ID)).isEmpty();
		assertThat(paymentService.findByBookingId(UNKNOWN_ID)).isEmpty();

		assertThatThrownBy(() -> userService.getById(UNKNOWN_ID)).isInstanceOf(ResourceNotFoundException.class);
		assertThatThrownBy(() -> userService.getByEmail(UNKNOWN_EMAIL)).isInstanceOf(ResourceNotFoundException.class);
		assertThatThrownBy(() -> eventService.getById(UNKNOWN_ID)).isInstanceOf(ResourceNotFoundException.class);
		assertThatThrownBy(() -> seatService.getById(UNKNOWN_ID)).isInstanceOf(ResourceNotFoundException.class);
		assertThatThrownBy(() -> seatService.getSeatForEvent(UNKNOWN_ID, "A-1")).isInstanceOf(ResourceNotFoundException.class);
		assertThatThrownBy(() -> bookingService.getById(UNKNOWN_ID)).isInstanceOf(ResourceNotFoundException.class);
		assertThatThrownBy(() -> paymentService.getById(UNKNOWN_ID)).isInstanceOf(ResourceNotFoundException.class);
		assertThatThrownBy(() -> paymentService.getByPaymentReference("missing-reference")).isInstanceOf(ResourceNotFoundException.class);
	}

}
