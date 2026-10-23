package com.enrola.anna.eval;

import java.math.BigDecimal;
import java.util.List;

/**
 * One scenario's run: its status, every check with its evidence, what it cost and where its rows
 * are. {@code judges} stays empty until judges exist. {@code tools} is each TOOL_CALL trace event
 * of the turn as its name and payload JSON, for the scenario's report page.
 */
record Trial(
    String scenarioId,
    String kind,
    TrialStatus status,
    List<Check> checks,
    List<String> judges,
    BigDecimal costUsd,
    int modelCalls,
    String conversationId,
    int turnNo,
    String reply,
    List<String> tools,
    long durationMs) {

  boolean passed() {
    return status == TrialStatus.PASS;
  }

  /** The names of the failed checks, for the report table and the JUnit failure message. */
  List<String> failedNames() {
    return checks.stream().filter(check -> !check.pass()).map(Check::name).toList();
  }

  /** A scenario that did not run, for the reason {@code status} names. */
  static Trial skipped(Scenario scenario, TrialStatus status) {
    return notRun(scenario, status, List.of());
  }

  /** A scenario the product refused before its turn; {@code refusal} says how. */
  static Trial refused(Scenario scenario, Check refusal) {
    return notRun(scenario, TrialStatus.FAIL, List.of(refusal));
  }

  /** A scenario the harness could not run, for {@code reason}. */
  static Trial invalid(Scenario scenario, String reason) {
    return notRun(
        scenario, TrialStatus.INVALID, List.of(Check.expectation("scenario", false, reason)));
  }

  private static Trial notRun(Scenario scenario, TrialStatus status, List<Check> checks) {
    return new Trial(
        scenario.id(),
        scenario.kind(),
        status,
        checks,
        List.of(),
        BigDecimal.ZERO,
        0,
        null,
        0,
        null,
        List.of(),
        0);
  }
}
