package com.enrola.anna.eval;

import com.enrola.anna.conversation.Conversation;
import com.enrola.anna.eval.Scenario.EveryReply;
import com.enrola.anna.eval.Scenario.Expect;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A story's own checks, each only when the scenario asks: {@code every_reply} on each reply of each
 * turn, {@code transcript_matches} over every reply of the run, and the counters' limits at the
 * end. The suite-wide checks run beside them on every turn, from {@link Checks}; its one question
 * per reply already covers {@code every_reply.one_question_max}, so that one is not checked twice.
 */
final class StoryChecks {

  private StoryChecks() {}

  /** The {@code every_reply} rules on each reply the turn in {@code rows} sent. */
  static List<Check> everyReply(EveryReply rules, TurnRows rows) {
    if (rules == null) {
      return List.of();
    }
    return rows.outbound().stream()
        .flatMap(message -> replyRules(rules, message.text(), rows.conversation()))
        .map(check -> check.at(rows.turnNo()))
        .toList();
  }

  /** {@code transcript_matches}: each regex finds at least one of {@code replies}. */
  static List<Check> transcript(Expect expect, List<String> replies) {
    if (expect.transcriptMatches() == null) {
      return List.of();
    }
    return expect.transcriptMatches().stream()
        .map(regex -> transcriptMatch(regex, replies))
        .toList();
  }

  /** The limits on objection retries and on replies after the goal and after the end. */
  static List<Check> counters(Expect expect, StoryCounters counted) {
    return Stream.of(
            atMost("retries_max", expect.retriesMax(), counted.retries()),
            atMost("replies_after_goal_max", expect.repliesAfterGoalMax(), counted.afterGoal()),
            atMost("replies_after_end_max", expect.repliesAfterEndMax(), counted.afterEnd()))
        .flatMap(Optional::stream)
        .toList();
  }

  private static Stream<Check> replyRules(EveryReply rules, String reply, Conversation after) {
    return Stream.of(
            maxChars(rules.maxChars(), reply),
            notMatches(rules.replyNotMatches(), reply),
            endsWithQuestion(rules.endsWithQuestionUntilGoal(), reply, after))
        .flatMap(Function.identity());
  }

  private static Stream<Check> maxChars(Integer limit, String reply) {
    return limit == null
        ? Stream.empty()
        : Stream.of(
            Check.expectation(
                "every_reply.max_chars", reply.length() <= limit, reply.length() + " chars"));
  }

  private static Stream<Check> notMatches(List<String> regexes, String reply) {
    return regexes == null
        ? Stream.empty()
        : regexes.stream()
            .map(
                regex ->
                    Check.expectation(
                        "every_reply.reply_not_matches " + regex,
                        !Pattern.compile(regex).matcher(reply).find(),
                        Checks.quote(reply)));
  }

  /** Only while the conversation is open and short of its goal, as the reply guard asks. */
  private static Stream<Check> endsWithQuestion(Boolean asked, String reply, Conversation after) {
    return Boolean.TRUE.equals(asked) && !after.goalMet && !after.ended
        ? Stream.of(
            Check.expectation(
                "every_reply.ends_with_question", reply.strip().endsWith("?"), Checks.quote(reply)))
        : Stream.empty();
  }

  private static Check transcriptMatch(String regex, List<String> replies) {
    Pattern pattern = Pattern.compile(regex);
    Optional<String> hit =
        replies.stream().filter(reply -> pattern.matcher(reply).find()).findFirst();
    return Check.expectation(
        "transcript_matches " + regex,
        hit.isPresent(),
        hit.map(Checks::quote).orElse(replies.size() + " replies, none matched"));
  }

  private static Optional<Check> atMost(String name, Integer limit, int seen) {
    return Optional.ofNullable(limit)
        .map(max -> Check.expectation(name, seen <= max, "max=" + max + " seen=" + seen));
  }
}
