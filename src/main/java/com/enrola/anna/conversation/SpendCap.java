package com.enrola.anna.conversation;

import com.enrola.anna.config.Settings;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * The spend caps of a turn's settings against {@code turn.cost_usd}: the turn rows are the ledger,
 * so there is no second one to drift from them.
 */
@Component
public class SpendCap {

  private final TurnRepository turns;

  SpendCap(TurnRepository turns) {
    this.turns = turns;
  }

  /** USD spent on model calls across every conversation, what the app cap is measured against. */
  public BigDecimal appSpend() {
    return turns.totalCost();
  }

  boolean appCapReached(Settings.Budgets budgets) {
    return appSpend().compareTo(budgets.appCap()) >= 0;
  }

  boolean conversationCapReached(String conversationId, Settings.Budgets budgets) {
    return turns.costOf(conversationId).compareTo(budgets.convCap()) >= 0;
  }
}
