package com.enrola.anna.eval;

import com.enrola.anna.conversation.Message;
import java.math.BigDecimal;
import java.util.List;

/**
 * The lead's side of a story: its next SMS, given everything said so far. {@link LeadSimulator}
 * plays the hidden persona on a model; {@link ScriptedSimulator} replays the story's offline lines
 * at $0.
 */
interface Simulator {

  /** What the actor produced, and what producing it cost. */
  sealed interface Next permits Line, PersonaBreak {
    BigDecimal costUsd();
  }

  /** The lead's next SMS. */
  record Line(String text, BigDecimal costUsd) implements Next {}

  /** The actor broke character or failed, so the trial tells nothing about Anna. */
  record PersonaBreak(String reason, BigDecimal costUsd) implements Next {}

  /** Where the story is: still talking, or past a terminal state, where only probes are sent. */
  enum Moment {
    TALKING,
    AFTER_END
  }

  /** Whether this actor can play {@code persona} at all. */
  boolean plays(Persona persona);

  /**
   * The lead's next message after {@code transcript}. After the end the actor sends what a person
   * still sends after a goodbye.
   */
  Next next(Persona persona, List<Message> transcript, Moment moment);
}
