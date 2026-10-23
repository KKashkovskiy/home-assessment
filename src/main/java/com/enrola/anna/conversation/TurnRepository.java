package com.enrola.anna.conversation;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

/** Turn rows: one per finished inbound, unique by conversation and turn number. */
public interface TurnRepository extends CrudRepository<Turn, String> {
  Optional<Turn> findByConversationIdAndTurnNo(String conversationId, int turnNo);

  List<Turn> findByConversationIdOrderByTurnNoAsc(String conversationId);

  /** USD spent on model calls across every conversation. */
  @Query("select coalesce(sum(cost_usd), 0) from turn")
  BigDecimal totalCost();

  /** USD spent on model calls in one conversation. */
  @Query("select coalesce(sum(cost_usd), 0) from turn where conversation_id = :conversationId")
  BigDecimal costOf(String conversationId);
}
