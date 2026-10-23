package com.enrola.anna.conversation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

/** Message rows. The transcript is every row of a conversation in turn order. */
public interface MessageRepository extends CrudRepository<Message, String> {
  List<Message> findByConversationIdOrderByTurnNoAscCreatedAtAsc(String conversationId);

  default List<Message> transcript(String conversationId) {
    return findByConversationIdOrderByTurnNoAscCreatedAtAsc(conversationId);
  }

  Optional<Message> findByConversationIdAndProviderMessageId(
      String conversationId, String providerMessageId);

  /**
   * The highest turn number an inbound row holds, finished or not; 0 before the first. A claimed
   * number is never reused, so the next turn is this plus one.
   */
  @Query(
      "select coalesce(max(turn_no), 0) from message"
          + " where conversation_id = :conversationId and direction = 'IN'")
  int lastClaimedTurn(String conversationId);
}
