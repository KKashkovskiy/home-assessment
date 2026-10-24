package com.enrola.anna.conversation;

import com.enrola.anna.guard.PreGuards;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The names of GUARD trace rows that record code choosing a route rather than a guard catching the
 * model: the moderation verdict, each pre-guard step, abuse, a reply code replaced, a goodbye held
 * back, and the summary of a failed repair whose check rows already say what failed. The writers
 * name their rows with these, and analytics leaves them out of guardrail violations.
 */
public final class RoutingGuards {

  public static final String MODERATION = "moderation";

  /**
   * The single row all pre-guards shared on turns traced before each step got its own; still
   * routing, so those turns count as they did.
   */
  public static final String PRE_GUARDS = "pre-guards";

  public static final String ABUSE = "abuse";
  public static final String REPLY_DISCARDED = "reply-discarded";
  public static final String CLOSING_REPLY = "closing-reply";
  public static final String REPAIR_FAILED = "repair-failed";

  public static final Set<String> NAMES =
      Stream.concat(
              Stream.of(
                  MODERATION, PRE_GUARDS, ABUSE, REPLY_DISCARDED, CLOSING_REPLY, REPAIR_FAILED),
              Arrays.stream(PreGuards.Step.values()).map(PreGuards.Step::traceName))
          .collect(Collectors.toUnmodifiableSet());

  private RoutingGuards() {}
}
