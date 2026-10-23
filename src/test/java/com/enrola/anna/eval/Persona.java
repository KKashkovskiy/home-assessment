package com.enrola.anna.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The lead's side of a story, built once per trial: the hidden instructions, the lines they forbid,
 * and the scripted lines the offline actor sends instead. A persona that never writes STOP forbids
 * a lone STOP, one that never swears at the agent forbids profanity, and {@code config.forbidden}
 * adds regexes of its own. A forbidden line is a persona break, which makes the trial INVALID
 * rather than blaming Anna.
 */
record Persona(String text, List<Pattern> forbidden, List<String> offlineLines) {

  private static final Pattern NEVER_STOP = Pattern.compile("(?i)never (write|say|send) stop");
  private static final Pattern NEVER_SWEAR = Pattern.compile("(?i)never swear");
  private static final Pattern LONE_STOP = Pattern.compile("(?i)^\\W*stop\\W*$");

  /**
   * The product leaves abuse to the moderation endpoint and keeps no word list, so this short one
   * stands in for it.
   */
  private static final Pattern PROFANITY =
      Pattern.compile("(?i)\\b(fuck\\w*|shit\\w*|cunt\\w*|dickhead|arsehole|bitch\\w*)");

  /** {@code offlineLines} is null for a story with none. */
  Persona {
    forbidden = List.copyOf(forbidden);
  }

  static Persona of(Scenario.Story story) {
    List<Pattern> forbidden = new ArrayList<>();
    if (NEVER_STOP.matcher(story.persona()).find()) {
      forbidden.add(LONE_STOP);
    }
    if (NEVER_SWEAR.matcher(story.persona()).find()) {
      forbidden.add(PROFANITY);
    }
    if (story.config().forbidden() != null) {
      story.config().forbidden().stream().map(Pattern::compile).forEach(forbidden::add);
    }
    return new Persona(story.persona().strip(), forbidden, story.config().offlineLines());
  }
}
