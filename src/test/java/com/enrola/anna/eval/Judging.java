package com.enrola.anna.eval;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Which judge questions a trial gets: the rubrics sharing a tag with its scenario and the
 * scenario's own {@code judge} entries, a turn rubric once per turn that replied, a transcript
 * rubric once.
 */
final class Judging {

  /** One call to the judge: {@code rubric} about {@code turnNo}, or the transcript when null. */
  record Question(Rubric rubric, Integer turnNo) {}

  private Judging() {}

  /** Rubrics with a tag on the scenario, then the scenario's own questions, which always apply. */
  static List<Rubric> applicable(Scenario scenario, List<Rubric> rubrics) {
    Stream<Rubric> tagged =
        rubrics.stream()
            .filter(rubric -> !Collections.disjoint(rubric.appliesTo(), scenario.tags()));
    Stream<Rubric> own =
        IntStream.range(0, scenario.judge().size())
            .mapToObj(index -> adHoc(scenario, index, scenario.judge().get(index)));
    return Stream.concat(tagged, own).toList();
  }

  /**
   * A scenario's {@code judge} entry as a rubric with a synthetic id: about the turn under test in
   * a turn scenario, and about the whole conversation in a story, whose questions span it.
   */
  static Rubric adHoc(Scenario scenario, int index, String question) {
    return new Rubric(
        "Q-" + scenario.id() + "-" + index,
        question,
        switch (scenario) {
          case Scenario.Turn _ -> Rubric.Scope.TURN;
          case Scenario.Story _ -> Rubric.Scope.TRANSCRIPT;
        },
        Rubric.Severity.SOFT,
        Set.of(),
        null,
        Rubric.Examples.NONE);
  }

  /**
   * The questions about {@code trial}: none unless it reached PASS or FAIL on a conversation, since
   * an INVALID or cut-short trial says nothing about Anna.
   */
  static List<Question> questions(Scenario scenario, Trial trial, List<Rubric> rubrics) {
    if (!trial.status().judged() || trial.conversationId() == null) {
      return List.of();
    }
    return applicable(scenario, rubrics).stream()
        .flatMap(
            rubric ->
                switch (rubric.scope()) {
                  case TURN ->
                      trial.repliedTurns().stream().map(turn -> new Question(rubric, turn));
                  case TRANSCRIPT -> Stream.of(new Question(rubric, null));
                })
        .toList();
  }

  /**
   * At most how many questions one trial of {@code scenario} asks, for the preflight: a turn rubric
   * counts once per lead message a story's cap allows.
   */
  static long calls(Scenario scenario, List<Rubric> rubrics) {
    long turns =
        switch (scenario) {
          case Scenario.Turn _ -> 1;
          case Scenario.Story story -> StopWhen.cap(story.stopWhen());
        };
    return applicable(scenario, rubrics).stream()
        .mapToLong(
            rubric ->
                switch (rubric.scope()) {
                  case TURN -> turns;
                  case TRANSCRIPT -> 1;
                })
        .sum();
  }
}
