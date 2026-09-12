package com.soubhagya.flashreserve.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * Verifies Razorpay webhook HMAC-SHA256 over the exact raw request body.
 * Never logs secret, raw body or signature.
 */
@Component
public class RazorpayWebhookSignatureVerifier {

	private static final String HMAC_ALGORITHM = "HmacSHA256";

	public boolean verify(byte[] rawBody, String signature, String webhookSecret) {
		if (rawBody == null || signature == null || webhookSecret == null
				|| signature.isBlank() || webhookSecret.isBlank()) {
			return false;
		}
		String expected = hmacSha256(rawBody, webhookSecret);
		return MessageDigest.isEqual(
				expected.getBytes(StandardCharsets.UTF_8),
				signature.getBytes(StandardCharsets.UTF_8));
	}

	private static String hmacSha256(byte[] data, String secret) {
		try {
			Mac mac = Mac.getInstance(HMAC_ALGORITHM);
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
			return HexFormat.of().formatHex(mac.doFinal(data));
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to compute webhook signature", ex);
		}
	}
}
