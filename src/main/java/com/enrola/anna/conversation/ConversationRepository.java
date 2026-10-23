package com.enrola.anna.conversation;

import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.Flag;
import com.enrola.anna.agent.HandoffReason;
import com.enrola.anna.agent.ObjectionType;
import com.enrola.anna.agent.Stage;
import com.enrola.anna.lead.Suppression;
import java.time.Instant;
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
   * What the console list asks for; every field optional, a null one matching all, the rest
   * AND-combined. {@code q} matches the lead's name, the conversation id or the summary, ignoring
   * case. The rest narrow to an analytics figure's cohort: {@code replied} is "has an inbound",
   * {@code optOutReason} reads the lead's suppression row, {@code objectionType} matches any turn's
   * decision, {@code stages} lists the stages a conversation may be at (every stage unless a figure
   * asks for those past one), {@code since} bounds the start, {@code guardCaught} is "a guard
   * caught the model on some turn" (routing rows aside, see {@link RoutingGuards}), {@code retried}
   * "a reply was repaired or fell back", {@code errored} "a turn failed", and {@code reset} picks
   * RESET conversations or leaves them out.
   */
  record Search(
      ConversationStatus status,
      EndReason endReason,
      Stage stage,
      Flag flag,
      Boolean needsHuman,
      Source source,
      String q,
      Boolean replied,
      Suppression.Reason optOutReason,
      HandoffReason handoffReason,
      ObjectionType objectionType,
      List<String> stages,
      String promptVersion,
      String model,
      Instant since,
      Boolean guardCaught,
      Boolean retried,
      Boolean errored,
      Boolean reset) {}

  /** The FROM and WHERE of a {@link Search}, shared by the list and its count. */
  // ponytail: LIKE on flags_json and decision_json; a flag table and an objection_type column when
  // the list passes a few thousand rows
  String SEARCH =
      """
      from conversation c join lead l on l.id = c.lead_id
      where (:#{#search.status} is null or c.status = :#{#search.status})
        and (:#{#search.endReason} is null or c.end_reason = :#{#search.endReason})
        and (:#{#search.stage} is null or c.stage = :#{#search.stage})
        and (:#{#search.flag} is null or c.flags_json like '%"' || :#{#search.flag} || '"%')
        and (:#{#search.needsHuman} is null or c.needs_human = :#{#search.needsHuman})
        and (:#{#search.source} is null or c.source = :#{#search.source})
        and (:#{#search.q} is null or lower(l.given_name) like '%' || lower(:#{#search.q}) || '%'
             or lower(c.id) like '%' || lower(:#{#search.q}) || '%'
             or lower(coalesce(c.summary, '')) like '%' || lower(:#{#search.q}) || '%')
        and (:#{#search.replied} is null or :#{#search.replied} = case when exists (
             select 1 from message m where m.conversation_id = c.id and m.direction = 'IN')
             then true else false end)
        and (:#{#search.optOutReason} is null or exists (select 1 from suppression s
             where s.lead_id = c.lead_id and s.reason = :#{#search.optOutReason}))
        and (:#{#search.handoffReason} is null or c.handoff_reason = :#{#search.handoffReason})
        and (:#{#search.objectionType} is null or exists (select 1 from turn t
             where t.conversation_id = c.id
               and t.decision_json like '%"objection_type":"' || :#{#search.objectionType} || '"%'))
        and c.stage in (:#{#search.stages})
        and (:#{#search.promptVersion} is null or c.prompt_version = :#{#search.promptVersion})
        and (:#{#search.model} is null or c.model = :#{#search.model})
        and (:#{#search.since} is null or c.created_at >= :#{#search.since})
        and (:#{#search.guardCaught} is null or :#{#search.guardCaught} = case when exists (
             select 1 from trace_event e where e.conversation_id = c.id and e.type = 'GUARD'
               and e.outcome in ('BLOCK', 'REPAIR', 'OVERRIDE')
               and e.name not in (:#{T(com.enrola.anna.conversation.RoutingGuards).NAMES}))
             then true else false end)
        and (:#{#search.retried} is null or :#{#search.retried} = case when exists (
             select 1 from turn t where t.conversation_id = c.id
               and t.status in ('REPAIRED', 'FALLBACK')) then true else false end)
        and (:#{#search.errored} is null or :#{#search.errored} = case when exists (
             select 1 from turn t where t.conversation_id = c.id and t.status = 'ERROR')
             then true else false end)
        and (:#{#search.reset} is null
             or :#{#search.reset} = case when c.status = 'RESET' then true else false end)
      """;

  /** The console list for {@code search}: the newest activity first, at most 200 rows. */
  @Query(
      "select c.id, l.given_name, l.state as lead_state, c.status, c.stage, c.updated_at,"
          + " c.flags_json, c.summary, c.source, c.needs_human "
          + SEARCH
          + " order by c.updated_at desc limit 200")
  List<ConversationRow> search(Search search);

  /** How many conversations {@code search} matches, past the list's 200 too. */
  @Query("select count(*) " + SEARCH)
  long countMatching(Search search);
}
