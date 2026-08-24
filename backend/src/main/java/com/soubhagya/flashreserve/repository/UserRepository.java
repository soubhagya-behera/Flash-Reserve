package com.soubhagya.flashreserve.repository;

import java.util.Optional;
import java.util.UUID;

import com.soubhagya.flashreserve.entity.User;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

	Optional<User> findByEmail(String email);

	boolean existsByEmail(String email);

}
