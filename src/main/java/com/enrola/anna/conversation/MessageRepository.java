package com.enrola.anna.conversation;

import java.util.List;
import org.springframework.data.repository.CrudRepository;

/** Message rows. The transcript is every row of a conversation in turn order. */
public interface MessageRepository extends CrudRepository<Message, String> {
  List<Message> findByConversationIdOrderByTurnNoAscCreatedAtAsc(String conversationId);
}
