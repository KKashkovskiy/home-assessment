package com.enrola.anna.conversation;

import com.enrola.anna.config.BudgetsProperties;
import org.springframework.stereotype.Component;

/**
 * The spend caps, summed from {@code turn.cost_usd}: the turn rows are the ledger, so there is no
 * second one to drift from them.
 */
@Component
class SpendCap {

  private final TurnRepository turns;
  private final BudgetsProperties budgets;

  SpendCap(TurnRepository turns, BudgetsProperties budgets) {
    this.turns = turns;
    this.budgets = budgets;
  }

  boolean appCapReached() {
    return turns.totalCost().compareTo(budgets.appCapUsd()) >= 0;
  }

  boolean conversationCapReached(String conversationId) {
    return turns.costOf(conversationId).compareTo(budgets.conversationCapUsd()) >= 0;
  }
}
