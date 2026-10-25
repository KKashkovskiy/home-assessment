package com.enrola.anna.agent;

/**
 * What {@link AgentService}'s tool loop calls back into, implemented by the running turn. It hears
 * of each model round as the round ends, before the round's function calls are answered, so what
 * the turn records reads in the order it happened. It also grants tool rounds from the turn's
 * allowance and answers each function call, a bad one with an error the model can read, never by
 * throwing.
 */
public interface ToolLoopCallbacks {

  void roundEnded(ModelResponse response);

  /** Takes one tool round from the turn's allowance; false when none is left. */
  boolean grantToolRound();

  /** The JSON the model reads for its call of {@code name} with {@code argumentsJson}. */
  String answer(String name, String argumentsJson);
}
