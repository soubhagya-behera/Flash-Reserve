package com.soubhagya.flashreserve;

import java.nio.charset.StandardCharsets;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.soubhagya.flashreserve.config.RazorpayProperties;
import com.soubhagya.flashreserve.payment.RazorpayPaymentProvider;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the real {@link RazorpayPaymentProvider} HMAC-SHA256 signature
 * check. This is the security-critical piece and is exercised with an isolated
 * local secret - no real credentials and no network involved.
 */
class RazorpayPaymentProviderTests {

	private static final String ORDER_ID = "order_test_0001";
	private static final String PAYMENT_ID = "pay_test_0001";
	private static final String SECRET = "test-mode-secret-abcdef";

	private final RazorpayPaymentProvider provider = new RazorpayPaymentProvider(
			new RazorpayProperties("rzp_test_key", SECRET, "INR"));

	@Test
	void authenticSignatureIsAccepted() {
		String payload = ORDER_ID + "|" + PAYMENT_ID;
		String signature = hmac(payload, SECRET);
		assertThat(provider.verifySignature(ORDER_ID, PAYMENT_ID, signature)).isTrue();
	}

	@Test
	void tamperedSignatureIsRejected() {
		String good = hmac(ORDER_ID + "|" + PAYMENT_ID, SECRET);
		String tampered = good.substring(0, good.length() - 4) + "dead";
		assertThat(provider.verifySignature(ORDER_ID, PAYMENT_ID, tampered)).isFalse();
	}

	@Test
	void signatureForDifferentOrderIsRejected() {
		String payload = ORDER_ID + "|" + PAYMENT_ID;
		String signature = hmac(payload, SECRET);
		// A payment id that is not part of the signing payload must not verify.
		assertThat(provider.verifySignature("order_test_9999", PAYMENT_ID, signature)).isFalse();
	}

	@Test
	void missingValuesAreRejected() {
		assertThat(provider.verifySignature(null, PAYMENT_ID, "x")).isFalse();
		assertThat(provider.verifySignature(ORDER_ID, null, "x")).isFalse();
		assertThat(provider.verifySignature(ORDER_ID, PAYMENT_ID, null)).isFalse();
		assertThat(provider.verifySignature("", PAYMENT_ID, "x")).isFalse();
	}

	@Test
	void clientKeyIdIsPublicAndNotTheSecret() {
		assertThat(provider.getClientKeyId()).isEqualTo("rzp_test_key");
	}

	private static String hmac(String data, String secret) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

}
