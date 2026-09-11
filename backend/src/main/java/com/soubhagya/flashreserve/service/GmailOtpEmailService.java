package com.soubhagya.flashreserve.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.soubhagya.flashreserve.exception.ServiceUnavailableException;

/**
 * Production email sender: Gmail SMTP (App Password) via Spring Mail.
 * Fails with a controlled 503 if SMTP is unreachable or misconfigured;
 * never exposes SMTP details to the caller.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GmailOtpEmailService implements EmailService {

	private final JavaMailSender mailSender;

	@Value("${spring.mail.username:}")
	private String fromAddress;

	@Value("${registration.otp.ttl:5m}")
	private String ttlDisplay;

	@Override
	public void sendOtpEmail(String toEmail, String otp) {
		send(toEmail, otp, "FlashReserve email verification code",
				"Use this code to verify your email and finish creating your account.",
				"Your verification code");
	}

	@Override
	public void sendPasswordResetEmail(String toEmail, String otp) {
		send(toEmail, otp, "FlashReserve password reset code",
				"Use this code to reset your password. If you did not request a password reset, you can safely ignore this email.",
				"Your password reset code");
	}

	private void send(String toEmail, String otp, String subject, String intro, String heading) {
		String expiryText = formatExpiry();
		String from = fromAddress != null && !fromAddress.isBlank() ? fromAddress : "no-reply@flashreserve.local";
		String html = buildHtml(otp, expiryText, intro, heading);
		String text = buildText(otp, expiryText, intro);
		try {
			MimeMessage message = mailSender.createMimeMessage();
			MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
			helper.setFrom(from);
			helper.setTo(toEmail);
			helper.setSubject(subject);
			helper.setText(text, html);
			mailSender.send(message);
		} catch (MailException | MessagingException ex) {
			log.warn("OTP email delivery failed for {}: {}", mask(toEmail), ex.getMessage());
			throw new ServiceUnavailableException(
					"Could not send verification email. Please try again shortly.");
		}
	}

	private String buildHtml(String otp, String expiryText, String intro, String heading) {
		return """
				<div style="font-family: system-ui,-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif; max-width:520px; margin:0 auto; padding:24px;">
				  <div style="border:1px solid #F3D1DE; border-radius:18px; padding:28px; background:#FFF7FB;">
				    <div style="display:flex; align-items:center; gap:10px; margin-bottom:16px;">
				      <span style="display:inline-grid; place-items:center; width:36px; height:36px; border-radius:10px; background:linear-gradient(135deg,#BE185D,#DB2777 55%%,#C026D3); color:white; font-family:Georgia,serif; font-weight:700;">F</span>
				      <span style="font-family:Georgia,serif; font-size:18px; font-weight:700; color:#241333;">FlashReserve</span>
				    </div>
				    <h2 style="margin:0 0 8px; font-size:18px; color:#241333;">%s</h2>
				    <p style="margin:0 0 16px; color:#6B587A; font-size:14px;">%s</p>
				    <div style="text-align:center; margin:18px 0;">
				      <span style="display:inline-block; letter-spacing:0.28em; font-size:28px; font-weight:800; color:#BE185D; background:white; border:1px solid #F3D1DE; border-radius:12px; padding:12px 20px;">%s</span>
				    </div>
				    <p style="margin:0 0 10px; color:#6B587A; font-size:13px;">This code expires in %s.</p>
				    <p style="margin:0; color:#8E7A9E; font-size:12px;">If you did not request this code, you can safely ignore this email.</p>
				  </div>
				  <p style="text-align:center; color:#8E7A9E; font-size:11px; margin-top:14px;">FlashReserve · Secure reservations</p>
				</div>
				""".formatted(escapeHtml(heading), escapeHtml(intro), escapeHtml(otp), escapeHtml(expiryText));
	}

	private String buildText(String otp, String expiryText, String intro) {
		return """
				FlashReserve

				%s

				%s

				This code expires in %s.

				If you did not request this code, you can safely ignore this email.
				""".formatted(intro, otp, expiryText);
	}

	private String formatExpiry() {
		try {
			String v = ttlDisplay.trim().toLowerCase();
			if (v.endsWith("m")) return v.replace("m", " minutes");
			if (v.endsWith("s")) return v.replace("s", " seconds");
			return ttlDisplay;
		} catch (Exception ex) {
			return "5 minutes";
		}
	}

	private static String escapeHtml(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static String mask(String email) {
		int at = email.indexOf('@');
		if (at <= 1) return "***";
		return email.charAt(0) + "***" + email.substring(at);
	}
}
