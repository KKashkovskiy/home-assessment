package com.enrola.anna.conversation;

import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

/** Conversation rows. One open conversation per lead is the rule the service enforces. */
public interface ConversationRepository extends CrudRepository<Conversation, String> {

  /** The lead's newest conversation that has not ended, whether ACTIVE or awaiting a human. */
  @Query(
      "select * from conversation where lead_id = :leadId and not ended"
          + " order by created_at desc limit 1")
  Optional<Conversation> findOpenByLead(String leadId);

  /** The lead's newest conversation, open or ended. */
  @Query("select * from conversation where lead_id = :leadId order by created_at desc limit 1")
  Optional<Conversation> findLatestByLead(String leadId);

  /** The conversation, or {@link NotFoundException} naming the id. */
  default Conversation require(String id) {
    return findById(id).orElseThrow(() -> new NotFoundException("conversation", id));
  }
}
