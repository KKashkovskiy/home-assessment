package com.enrola.anna.conversation;

import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

/** Conversation rows. One active conversation per lead is the rule the service enforces. */
public interface ConversationRepository extends CrudRepository<Conversation, String> {

  @Query(
      "select * from conversation where lead_id = :leadId and status = '"
          + Conversation.ACTIVE
          + "' order by created_at desc limit 1")
  Optional<Conversation> findActiveByLead(String leadId);
}
