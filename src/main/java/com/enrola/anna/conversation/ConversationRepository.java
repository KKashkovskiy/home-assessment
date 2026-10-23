package com.enrola.anna.conversation;

import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.Flag;
import com.enrola.anna.agent.Stage;
import java.util.List;
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

  /**
   * The console list: every filter optional, a null one matching all, the rest AND-combined; the
   * newest activity first, at most 200 rows. {@code q} matches the lead's name, the conversation id
   * or the summary, ignoring case.
   */
  // ponytail: LIKE on flags_json; a flag table when the list passes a few thousand rows
  @Query(
      """
      select c.id, l.given_name, l.state as lead_state, c.status, c.stage, c.updated_at,
             c.flags_json, c.summary, c.source, c.needs_human
      from conversation c join lead l on l.id = c.lead_id
      where (:status is null or c.status = :status)
        and (:endReason is null or c.end_reason = :endReason)
        and (:stage is null or c.stage = :stage)
        and (:flag is null or c.flags_json like '%"' || :flag || '"%')
        and (:needsHuman is null or c.needs_human = :needsHuman)
        and (:source is null or c.source = :source)
        and (:q is null or lower(l.given_name) like '%' || lower(:q) || '%'
             or lower(c.id) like '%' || lower(:q) || '%'
             or lower(coalesce(c.summary, '')) like '%' || lower(:q) || '%')
      order by c.updated_at desc
      limit 200
      """)
  List<ConversationRow> search(
      ConversationStatus status,
      EndReason endReason,
      Stage stage,
      Flag flag,
      Boolean needsHuman,
      Source source,
      String q);
}
