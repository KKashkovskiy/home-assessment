package com.enrola.anna.eval;

import java.math.BigDecimal;

/**
 * One judge answer about one trial, by ids only, so it serialises to a {@code trials.jsonl} line
 * and joins with calibration labels later: the rubric, the scenario and trial, the turn judged
 * (null for the whole transcript), the verdict, a quote found verbatim in one SMS of the transcript
 * the judge saw (null unless PASS or FAIL), a note (the judge's reason, or why the verdict is
 * UNKNOWN or was never asked), and what the call cost and took.
 */
record JudgeResult(
    String rubricId,
    String scenarioId,
    int trialNo,
    Integer turnNo,
    Verdict verdict,
    String evidence,
    String note,
    BigDecimal costUsd,
    long latencyMs) {

  /**
   * PASS, FAIL, NOT_APPLICABLE (the judged turn or transcript does not do what the question is
   * about) or UNKNOWN as the judge answered, UNKNOWN also when its answer could not be used;
   * SKIPPED_BUDGET when the cap left no room to ask.
   */
  enum Verdict {
    PASS,
    FAIL,
    NOT_APPLICABLE,
    UNKNOWN,
    SKIPPED_BUDGET
  }

  /** {@code question} about {@code trial}, not asked because the cap had no room for it. */
  static JudgeResult skipped(Judging.Question question, Trial trial) {
    return unanswered(question, trial, Verdict.SKIPPED_BUDGET, "not asked: the cap had no room");
  }

  /**
   * {@code question} about {@code trial}, whose judge threw {@code crash}: no answer, no run end.
   */
  static JudgeResult crashed(Judging.Question question, Trial trial, RuntimeException crash) {
    return unanswered(
        question, trial, Verdict.UNKNOWN, "judge crashed: " + crash.getClass().getSimpleName());
  }

  private static JudgeResult unanswered(
      Judging.Question question, Trial trial, Verdict verdict, String note) {
    return new JudgeResult(
        question.rubric().id(),
        trial.scenarioId(),
        trial.trialNo(),
        question.turnNo(),
        verdict,
        null,
        note,
        BigDecimal.ZERO,
        0);
  }
}
