package com.soubhagya.flashreserve.service;

import java.util.Optional;
import java.util.UUID;

import com.soubhagya.flashreserve.entity.User;
import com.soubhagya.flashreserve.entity.enums.UserRole;
import com.soubhagya.flashreserve.exception.ResourceNotFoundException;
import com.soubhagya.flashreserve.repository.UserRepository;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserService {

	private final UserRepository userRepository;

	public User getById(UUID id) {
		return userRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
	}

	public User getByEmail(String email) {
		return userRepository.findByEmail(email)
				.orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + email));
	}

	public boolean existsByEmail(String email) {
		return userRepository.existsByEmail(email);
	}

	public Optional<User> findByEmail(String email) {
		return userRepository.findByEmail(email);
	}

	public User createUser(String name, String email, String encodedPassword, UserRole role) {
		// Flush inside the repository call so a uk_users_email violation under
		// concurrent registration surfaces here as a translated
		// DataIntegrityViolationException (-> 409), not at commit time.
		return userRepository.saveAndFlush(new User(name, email, encodedPassword, role));
	}

}
