package com.soubhagya.flashreserve.repository;

import java.util.Optional;
import java.util.UUID;

import com.soubhagya.flashreserve.entity.WebhookEvent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WebhookEventRepository extends JpaRepository<WebhookEvent, UUID> {

	Optional<WebhookEvent> findByRazorpayEventId(String razorpayEventId);

	boolean existsByRazorpayEventId(String razorpayEventId);

}
