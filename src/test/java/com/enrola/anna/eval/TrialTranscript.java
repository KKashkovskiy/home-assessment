package com.enrola.anna.eval;

import com.enrola.anna.conversation.Direction;
import com.enrola.anna.conversation.Message;
import com.enrola.anna.lead.Lead;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * What a judge reads about one trial: its scenario, the lead its conversation was started for, and
 * every SMS of that conversation in order, whose texts the trial's page prints. Never the decision
 * JSON or the reasoning: a judge grades the SMS the lead read.
 */
record TrialTranscript(Scenario scenario, int trialNo, Lead lead, List<Message> messages) {

  static final String UNDER_JUDGEMENT = "[UNDER JUDGEMENT] ";

  TrialTranscript {
    messages = List.copyOf(messages);
  }

  /**
   * One line per SMS, {@code [turn 2] Lead: text} or {@code [turn 2] Anna: text}, with Anna's lines
   * of {@code turnNo} marked, or none when it is null: what the judge reads, labels and all.
   */
  String render(Integer turnNo) {
    return messages.stream()
        .map(message -> line(message, turnNo))
        .collect(Collectors.joining("\n"));
  }

  /** Whether one SMS text contains {@code quote} whole: labels and markers are no SMS. */
  boolean says(String quote) {
    return messages.stream().anyMatch(message -> message.text().contains(quote));
  }

  private static String line(Message message, Integer turnNo) {
    String prefix = "[turn " + message.turnNo() + "] ";
    if (message.direction() == Direction.IN) {
      return prefix + "Lead: " + message.text();
    }
    boolean marked = Objects.equals(turnNo, message.turnNo());
    return prefix + "Anna: " + (marked ? UNDER_JUDGEMENT : "") + message.text();
  }
}
