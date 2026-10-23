package com.enrola.anna.conversation;

import java.util.Optional;
import org.springframework.data.repository.CrudRepository;

/** Turn rows: one per finished inbound, unique by conversation and turn number. */
public interface TurnRepository extends CrudRepository<Turn, String> {
  Optional<Turn> findByConversationIdAndTurnNo(String conversationId, int turnNo);
}
