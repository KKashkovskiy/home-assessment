package com.enrola.anna.eval;

import com.enrola.anna.agent.ModelResponse.Usage;
import com.enrola.anna.agent.PriceTable;
import java.math.BigDecimal;
import java.util.List;

/**
 * One run's spend against its cap, a ledger of its own: an estimate before the first call, a
 * running total after each trial and each judge call. A typical call is 6,000 tokens in and 400 out
 * at the model's price; a turn is budgeted at two calls, its decision and a possible repair, and a
 * story at {@link #STORY_CALLS}. The simulated lead's calls are far smaller and ride in that
 * margin. A judge call is priced on the judge's model at {@link #TYPICAL_JUDGE_CALL}.
 */
final class EvalBudget {

  private static final Usage TYPICAL_CALL = new Usage(6_000, 0, 400, 0);

  /** Seven turns of a decision and a repair: a story that books with its probes. */
  static final int STORY_CALLS = 14;

  /**
   * A rubric, the scenario line and a short transcript in, the answer and low reasoning out; a
   * measured call took about 750 in and 70 out.
   */
  static final Usage TYPICAL_JUDGE_CALL = new Usage(1_000, 0, 100, 0);

  private final BigDecimal cap;
  private final PriceTable prices;
  private final BigDecimal perCall;
  private BigDecimal spent = BigDecimal.ZERO;

  /** A model the price table does not know costs nothing, so nothing is refused. */
  EvalBudget(BigDecimal cap, PriceTable prices, String model) {
    this.cap = cap;
    this.prices = prices;
    this.perCall = prices.cost(model, TYPICAL_CALL);
  }

  BigDecimal cap() {
    return cap;
  }

  BigDecimal spent() {
    return spent;
  }

  /** What {@code trials} of each of {@code selected} would cost at the typical call's price. */
  BigDecimal estimate(List<Scenario> selected, int trials) {
    long calls =
        selected.stream()
            .mapToLong(
                scenario ->
                    switch (scenario) {
                      case Scenario.Turn _ -> 2;
                      case Scenario.Story _ -> STORY_CALLS;
                    })
            .sum();
    return perCall.multiply(BigDecimal.valueOf(calls * trials));
  }

  /** What {@code questions} typical judge calls on {@code judgeModel} would cost. */
  BigDecimal judgeEstimate(long questions, String judgeModel) {
    return prices.cost(judgeModel, TYPICAL_JUDGE_CALL).multiply(BigDecimal.valueOf(questions));
  }

  /** True while one more typical judge call on {@code judgeModel} stays within the cap. */
  boolean allowsAnotherJudgeCall(String judgeModel) {
    return allows(judgeEstimate(1, judgeModel));
  }

  /** True while {@code usd} more stays within the cap. */
  boolean allows(BigDecimal usd) {
    return spent.add(usd).compareTo(cap) <= 0;
  }

  /** True while one more typical call stays within the cap; the next trial runs only then. */
  boolean allowsAnotherCall() {
    return allowsAnotherCall(BigDecimal.ZERO);
  }

  /**
   * Likewise on top of {@code pending}, what a running story has spent and the ledger has not yet
   * counted; its next line is sent only then.
   */
  boolean allowsAnotherCall(BigDecimal pending) {
    return allows(pending.add(perCall));
  }

  void spend(BigDecimal usd) {
    spent = spent.add(usd);
  }
}
