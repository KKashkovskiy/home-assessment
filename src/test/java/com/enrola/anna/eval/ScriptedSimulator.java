package com.enrola.anna.eval;

import com.enrola.anna.conversation.Direction;
import com.enrola.anna.conversation.Message;
import java.math.BigDecimal;
import java.util.List;

/**
 * The offline actor: the n-th lead message of a story is the n-th of its {@code
 * config.offline_lines}. It counts from the transcript, so k trials replay the same script. A story
 * that outruns its lines is broken, a {@link ScenarioException}, not a persona break.
 */
final class ScriptedSimulator implements Simulator {

  @Override
  public boolean plays(Persona persona) {
    return persona.offlineLines() != null;
  }

  @Override
  public Next next(Persona persona, List<Message> transcript, Moment moment) {
    List<String> lines = persona.offlineLines();
    int sent = (int) transcript.stream().filter(m -> m.direction() == Direction.IN).count();
    if (sent >= lines.size()) {
      throw new ScenarioException("offline_lines ran out after " + sent + " lead messages");
    }
    return new Line(lines.get(sent), BigDecimal.ZERO);
  }
}
