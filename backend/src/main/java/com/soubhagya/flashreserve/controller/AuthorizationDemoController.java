package com.soubhagya.flashreserve.controller;

import java.util.Map;

import com.soubhagya.flashreserve.security.UserPrincipal;

import org.springframework.security.core.annotation.AuthenticationPrincipal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// TEMPORARY authorization smoke-test endpoints, added to verify the security
// rules of the authentication milestone. Remove or replace with real
// event-management APIs in the next feature commit.
@RestController
@RequestMapping("/api")
public class AuthorizationDemoController {

	@GetMapping("/demo/me")
	UserPrincipal me(@AuthenticationPrincipal UserPrincipal principal) {
		return principal;
	}

	@GetMapping("/admin/ping")
	Map<String, String> adminPing() {
		return Map.of("status", "admin-ok");
	}

}
