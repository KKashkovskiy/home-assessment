package com.enrola.anna.scheduling;

import java.util.Optional;
import org.springframework.data.repository.CrudRepository;

/** Bookings by conversation; a unique index keeps it to one per conversation. */
public interface BookingRepository extends CrudRepository<Booking, String> {

  Optional<Booking> findByConversationId(String conversationId);
}
