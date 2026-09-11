package com.soubhagya.flashreserve.service;

/**
 * Thin email abstraction so tests mock delivery and production
 * uses Gmail SMTP via Spring Mail. No OTP value ever appears
 * in logs.
 */
public interface EmailService {

	void sendOtpEmail(String toEmail, String otp);

	void sendPasswordResetEmail(String toEmail, String otp);
}
