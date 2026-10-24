package com.enrola.anna.eval;

import com.enrola.anna.conversation.Direction;
import com.enrola.anna.conversation.Message;
import com.enrola.anna.conversation.MessageSource;
import com.enrola.anna.lead.Lead;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * What a judge reads about one trial: its scenario, the lead its conversation was started for, and
 * every SMS of that conversation in order, whose texts the trial's page prints. Never the decision
 * JSON or the reasoning: a judge grades the SMS the lead read, and only those Anna's model wrote in
 * the trial, since no prompt change can fix a template or a fixed reply and a turn scenario's
 * history is the scenario's own text.
 */
record TrialTranscript(Scenario scenario, int trialNo, Lead lead, List<Message> messages) {

  static final String UNDER_JUDGEMENT = "[UNDER JUDGEMENT] ";

  /** Who wrote an SMS, by its label; only the lead's and the model's words are evidence. */
  enum Author {
    LEAD("Lead: "),
    MODEL("Anna: "),
    /** The code: the opener, a fixed reply, a fixed closing, the fallback. */
    FIXED("Anna (fixed text): "),
    /** A turn scenario's seeded history, before the turn under test, whatever its stored source. */
    HISTORY("Anna (scenario history): ");

    final String label;

    Author(String label) {
      this.label = label;
    }

    boolean evidence() {
      return this == LEAD || this == MODEL;
    }
  }

  TrialTranscript {
    messages = List.copyOf(messages);
  }

  /**
   * One line per SMS, {@code [turn 2] Lead: text}, {@code [turn 2] Anna: text} or Anna's line with
   * the {@link Author} label that says the model did not write it, with Anna's lines of {@code
   * turnNo} marked, or none when it is null: what the judge reads, labels and all.
   */
  String render(Integer turnNo) {
    int liveFrom = liveFrom();
    return messages.stream()
        .map(message -> line(message, author(message, liveFrom), turnNo))
        .collect(Collectors.joining("\n"));
  }

  /**
   * Whether the text of one SMS the lead or the model wrote contains {@code quote} whole: labels,
   * markers, fixed text and scenario history are no evidence.
   */
  boolean says(String quote) {
    int liveFrom = liveFrom();
    return messages.stream()
        .filter(message -> author(message, liveFrom).evidence())
        .anyMatch(message -> message.text().contains(quote));
  }

  /** Whether the model wrote a reply of {@code turnNo} in this trial. */
  boolean modelReplied(int turnNo) {
    int liveFrom = liveFrom();
    return messages.stream()
        .anyMatch(
            message -> message.turnNo() == turnNo && author(message, liveFrom) == Author.MODEL);
  }

  /** The first turn the trial ran: a turn scenario seeds all before its last, a story none. */
  private int liveFrom() {
    return switch (scenario) {
      case Scenario.Turn _ -> messages.stream().mapToInt(Message::turnNo).max().orElse(0);
      case Scenario.Story _ -> 0;
    };
  }

  private static Author author(Message message, int liveFrom) {
    if (message.direction() == Direction.IN) {
      return Author.LEAD;
    }
    if (message.turnNo() < liveFrom) {
      return Author.HISTORY;
    }
    return message.source() == MessageSource.LLM ? Author.MODEL : Author.FIXED;
  }

  private static String line(Message message, Author author, Integer turnNo) {
    boolean marked = author != Author.LEAD && Objects.equals(turnNo, message.turnNo());
    return "[turn %d] %s%s%s"
        .formatted(message.turnNo(), author.label, marked ? UNDER_JUDGEMENT : "", message.text());
  }
}
