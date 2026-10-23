package com.enrola.anna.scheduling;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.enrola.anna.conversation.ConversationService;
import com.enrola.anna.conversation.Source;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** One booking per conversation is a rule the database holds, whatever path writes the row. */
@SpringBootTest(properties = "OPENAI_API_KEY=")
class BookingRepositoryTest {

  static final Instant NOON_MONDAY = Instant.parse("2026-07-27T02:00:00Z");

  @TempDir static Path tempDir;

  @DynamicPropertySource
  static void databaseInTempDir(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:h2:file:" + tempDir.resolve("anna"));
  }

  @Autowired BookingRepository bookings;
  @Autowired ConversationService conversations;

  @Test
  void secondRowForSameConversationFailsInDatabase() {
    String conversationId = conversations.start("lauren", Source.API).conversationId();
    bookings.save(booking("cal-1", conversationId, NOON_MONDAY));

    assertThatExceptionOfType(DuplicateKeyException.class)
        .isThrownBy(
            () -> bookings.save(booking("cal-2", conversationId, NOON_MONDAY.plusSeconds(1800))));
  }

  private static Booking booking(String id, String conversationId, Instant start) {
    return new Booking(
        id, conversationId, Slot.idFor(start), start, "Mon 27 Jul", "Australia/Melbourne", start);
  }
}
