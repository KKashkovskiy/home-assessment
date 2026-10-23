package com.enrola.anna.agent;

/**
 * Things worth a human's attention on a conversation, in one vocabulary for the flags column. The
 * first group judges what the lead wrote and is the decision schema's list; the second records what
 * code did or had to assume.
 */
public enum Flag {
  // What the lead wrote.
  AI_DISCLOSURE_ASKED,
  INJECTION_ATTEMPT,
  OFF_TOPIC,
  HEALTH_INFO,
  VULNERABLE,
  COMPLAINT,
  OBJECTION,
  // What code did or assumed. TZ_ASSUMED: the lead's state did not resolve to a zone.
  TZ_ASSUMED,
  LENGTH_REPAIRED,
  INPUT_TRUNCATED,
  LOOP_SUPPRESSED
}
