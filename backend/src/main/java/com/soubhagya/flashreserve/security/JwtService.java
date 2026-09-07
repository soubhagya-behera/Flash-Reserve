package com.soubhagya.flashreserve.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.UUID;

import javax.crypto.SecretKey;

import com.soubhagya.flashreserve.entity.User;
import com.soubhagya.flashreserve.entity.enums.UserRole;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

@Service
public class JwtService {

	/**
	 * Secrets that are publicly known (they appear in tracked example files or
	 * documentation) and must never be usable in ANY environment, including
	 * local development. Startup fails if {@code jwt.secret} matches one of
	 * them, so no deployment can ever boot with a forgeable, publicly-known
	 * signing key. Never echo the configured value in the error message.
	 */
	private static final Set<String> FORBIDDEN_SECRETS = Set.of(
			"change-me-in-local-environment-only-min-32-chars-long");

	private final SecretKey secretKey;

	private final long expirationMs;

	public JwtService(@Value("${jwt.secret}") String secret, @Value("${jwt.expiration-ms}") long expirationMs) {
		byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
		if (keyBytes.length < 32) {
			throw new IllegalStateException(
					"jwt.secret must be at least 32 characters long. Set the JWT_SECRET environment variable.");
		}
		if (FORBIDDEN_SECRETS.contains(secret)) {
			throw new IllegalStateException(
					"jwt.secret is a known placeholder/default value. "
							+ "Set a strong, unique JWT_SECRET environment variable.");
		}
		this.secretKey = Keys.hmacShaKeyFor(keyBytes);
		this.expirationMs = expirationMs;
	}

	public String generateToken(User user) {
		Instant now = Instant.now();
		return Jwts.builder()
				.subject(user.getId().toString())
				.claim("email", user.getEmail())
				.claim("role", user.getRole().name())
				.issuedAt(Date.from(now))
				.expiration(Date.from(now.plusMillis(expirationMs)))
				.signWith(secretKey)
				.compact();
	}

	public UserPrincipal parseToken(String token) {
		Claims claims = Jwts.parser()
				.verifyWith(secretKey)
				.build()
				.parseSignedClaims(token)
				.getPayload();
		UUID userId = UUID.fromString(claims.getSubject());
		String email = claims.get("email", String.class);
		UserRole role = UserRole.valueOf(claims.get("role", String.class));
		return new UserPrincipal(userId, email, role);
	}

	public long getExpirationSeconds() {
		return expirationMs / 1000;
	}

}
