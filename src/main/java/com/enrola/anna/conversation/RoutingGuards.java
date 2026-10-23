package com.enrola.anna.conversation;

import java.util.Set;

/**
 * The names of GUARD trace rows that record code choosing a route rather than a guard catching the
 * model: the moderation verdict, the pre-guards' decision, abuse, a reply code replaced, a goodbye
 * held back, and the summary of a failed repair whose check rows already say what failed. The
 * writers name their rows with these, and analytics leaves them out of guardrail violations.
 */
public final class RoutingGuards {

  public static final String MODERATION = "moderation";
  public static final String PRE_GUARDS = "pre-guards";
  public static final String ABUSE = "abuse";
  public static final String REPLY_DISCARDED = "reply-discarded";
  public static final String CLOSING_REPLY = "closing-reply";
  public static final String REPAIR_FAILED = "repair-failed";

  public static final Set<String> NAMES =
      Set.of(MODERATION, PRE_GUARDS, ABUSE, REPLY_DISCARDED, CLOSING_REPLY, REPAIR_FAILED);

  private RoutingGuards() {}
}
