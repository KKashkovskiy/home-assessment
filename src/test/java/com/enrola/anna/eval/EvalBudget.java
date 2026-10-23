package com.enrola.anna.eval;

import com.enrola.anna.agent.ModelResponse.Usage;
import com.enrola.anna.agent.PriceTable;
import java.math.BigDecimal;
import java.util.List;

/**
 * One run's spend against its cap, a ledger of its own: an estimate before the first call, a
 * running total after each trial. A typical call is 6,000 tokens in and 400 out at the model's
 * price; a turn is budgeted at two calls, its decision and a possible repair, and a story at none,
 * since no runner takes stories yet.
 */
final class EvalBudget {

  private static final Usage TYPICAL_CALL = new Usage(6_000, 0, 400, 0);

  private final BigDecimal cap;
  private final BigDecimal perCall;
  private BigDecimal spent = BigDecimal.ZERO;

  /** A model the price table does not know costs nothing, so nothing is refused. */
  EvalBudget(BigDecimal cap, PriceTable prices, String model) {
    this.cap = cap;
    this.perCall = prices.cost(model, TYPICAL_CALL);
  }

  BigDecimal cap() {
    return cap;
  }

  BigDecimal spent() {
    return spent;
  }

  /** What {@code selected} would cost at the typical call's price. */
  BigDecimal estimate(List<Scenario> selected) {
    long calls =
        selected.stream()
            .mapToLong(
                scenario ->
                    switch (scenario) {
                      case Scenario.Turn _ -> 2;
                      case Scenario.Story _ -> 0;
                    })
            .sum();
    return perCall.multiply(BigDecimal.valueOf(calls));
  }

  /** True while {@code usd} more stays within the cap. */
  boolean allows(BigDecimal usd) {
    return spent.add(usd).compareTo(cap) <= 0;
  }

  /** True while one more typical call stays within the cap; the next trial runs only then. */
  boolean allowsAnotherCall() {
    return allows(perCall);
  }

  void spend(BigDecimal usd) {
    spent = spent.add(usd);
  }
}
