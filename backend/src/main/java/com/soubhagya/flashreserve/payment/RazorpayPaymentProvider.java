package com.soubhagya.flashreserve.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.soubhagya.flashreserve.config.RazorpayProperties;
import com.soubhagya.flashreserve.exception.ServiceUnavailableException;

import org.json.JSONObject;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Razorpay-backed {@link PaymentProvider} using TEST MODE credentials from
 * configuration. Raises a {@link ServiceUnavailableException} when the
 * credentials are not configured so the rest of the application can start and
 * be tested without them.
 *
 * All provider-specific knowledge is isolated here: the SDK client, the
 * smallest-currency-unit conversion (paise), and HMAC-SHA256 signature
 * verification. It never touches bookings, seats, or repositories.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RazorpayPaymentProvider implements PaymentProvider {

	private static final String HMAC_ALGORITHM = "HmacSHA256";

	private final RazorpayProperties properties;

	@Override
	public String createOrder(String paymentReference, BigDecimal amount) {
		ensureConfigured();
		long paise = amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();

		JSONObject options = new JSONObject();
		options.put("amount", paise);
		options.put("currency", properties.currency());
		options.put("receipt", paymentReference);
		options.put("payment_capture", 1);

		try {
			RazorpayClient client = client();
			Order order = client.orders.create(options);
			return order.get("id");
		}
		catch (RazorpayException ex) {
			log.error("Failed to create Razorpay order for receipt {}", paymentReference);
			throw new ServiceUnavailableException("Payment provider unavailable. Please retry.");
		}
	}

	@Override
	public boolean verifySignature(String orderId, String paymentId, String signature) {
		if (orderId == null || paymentId == null || signature == null || orderId.isBlank()
				|| paymentId.isBlank() || signature.isBlank()) {
			return false;
		}
		String payload = orderId + "|" + paymentId;
		String expected = hmacSha256(payload, properties.keySecret());
		return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
				signature.getBytes(StandardCharsets.UTF_8));
	}

	@Override
	public String getClientKeyId() {
		return properties.keyId();
	}

	@Override
	public String getCurrency() {
		return properties.currency();
	}

	private RazorpayClient client() throws RazorpayException {
		return new RazorpayClient(properties.keyId(), properties.keySecret());
	}

	private void ensureConfigured() {
		if (blank(properties.keyId()) || blank(properties.keySecret())) {
			throw new ServiceUnavailableException(
					"Razorpay is not configured. Set razorpay.key-id and razorpay.key-secret.");
		}
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}

	private static String hmacSha256(String data, String secret) {
		try {
			Mac mac = Mac.getInstance(HMAC_ALGORITHM);
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
			return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to compute payment signature hash", ex);
		}
	}

}
