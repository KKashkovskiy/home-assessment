package com.enrola.anna.eval;

/**
 * One deterministic verdict on a trial: what was checked, under which gate, whether it held, the
 * value seen, and the turn it is about (null when it is about none).
 */
record Check(String name, Gate gate, boolean pass, String evidence, Integer turn) {

  /**
   * HARD checks hold on every reply of every trial whether a scenario asks or not; an EXPECTATION
   * is what one scenario asked for.
   */
  enum Gate {
    HARD,
    EXPECTATION
  }

  static Check hard(String name, boolean pass, String evidence) {
    return new Check(name, Gate.HARD, pass, evidence, null);
  }

  static Check expectation(String name, boolean pass, String evidence) {
    return new Check(name, Gate.EXPECTATION, pass, evidence, null);
  }

  /** This check, about {@code turnNo}. */
  Check at(int turnNo) {
    return new Check(name, gate, pass, evidence, turnNo);
  }
}
