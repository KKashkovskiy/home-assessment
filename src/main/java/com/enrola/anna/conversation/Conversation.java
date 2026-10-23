package com.enrola.anna.conversation;

import java.time.Instant;
import java.util.EnumSet;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;

/**
 * One SMS conversation with one lead: the aggregate every turn updates. A row with public fields,
 * not a bean with invariants; {@link ConversationService} is the one place that changes it.
 */
public class Conversation {

  /** The status of a conversation that takes turns. */
  public static final String ACTIVE = "ACTIVE";

  /** The source of a conversation the platform started through the API. */
  public static final String SOURCE_API = "API";

  @Id public String id;
  public String leadId;

  public String status;

  public Stage stage;
  public String flagsJson;
  public boolean goalMet;
  public boolean unsubscribed;
  public boolean ended;
  public boolean needsHuman;

  /**
   * Resolved from the lead's state at start and never recomputed, so the lead's clock stays put.
   */
  public String leadZoneId;

  public String promptVersion;
  public String source;
  public Instant createdAt;
  public Instant updatedAt;
  @Version public long version;

  public EnumSet<Flag> flags() {
    return Flags.parse(flagsJson);
  }
}
