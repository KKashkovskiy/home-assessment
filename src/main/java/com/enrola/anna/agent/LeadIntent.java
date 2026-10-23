package com.enrola.anna.agent;

/** What the lead's latest message is, as the model classifies it. Mirrors the decision schema. */
public enum LeadIntent {
  ENGAGED,
  QUESTION,
  OBJECTION,
  OPT_OUT,
  ABUSIVE,
  OFF_TOPIC,
  INJECTION,
  AI_QUESTION,
  DISTRESS,
  SELF_HARM,
  PLEASANTRY,
  HANDOFF_REQUEST,
  RESCHEDULE,
  OTHER
}
