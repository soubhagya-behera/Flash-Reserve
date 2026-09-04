package com.soubhagya.flashreserve.controller;

import com.soubhagya.flashreserve.dto.admin.AdminDashboardResponse;
import com.soubhagya.flashreserve.dto.error.ApiError;
import com.soubhagya.flashreserve.service.AdminDashboardService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Dashboard (ADMIN)", description = """
		Read-only reservation and business metrics for administrators. \
		Every endpoint requires a JWT with the ADMIN role; a valid USER \
		token receives 403. The snapshot is fully read-only: it never \
		mutates events, bookings, seats or payments.""")
public class AdminDashboardController {

	private final AdminDashboardService adminDashboardService;

	@GetMapping
	@Operation(summary = "Get the dashboard metrics snapshot",
			description = """
					One read-only snapshot for the ADMIN dashboard: event status \
					counts (including upcoming published events), booking status \
					counts, confirmed revenue (SUCCESS payments on CONFIRMED \
					bookings only, using the actual payment amount), seat \
					occupancy counts and the five newest bookings without any \
					booker identity or payment details. Protected by the \
					existing /api/admin/** ADMIN rule.""")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Dashboard metrics snapshot"),
			@ApiResponse(responseCode = "401", description = "Missing, invalid or expired JWT", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiError.class))),
			@ApiResponse(responseCode = "403", description = "Authenticated but without the ADMIN role")
	})
	AdminDashboardResponse dashboard() {
		return adminDashboardService.getDashboard();
	}

}