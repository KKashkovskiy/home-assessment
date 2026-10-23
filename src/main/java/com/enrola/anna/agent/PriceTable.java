package com.enrola.anna.agent;

import com.enrola.anna.agent.ModelResponse.Usage;
import com.enrola.anna.config.Settings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * USD cost of one call from the settings' price table: per-million rates keyed by model id. A model
 * with no price costs zero and logs one WARN; adding its price is the fix.
 */
public record PriceTable(Map<String, Settings.Price> prices) {

  private static final Logger log = LoggerFactory.getLogger(PriceTable.class);
  private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
  private static final int SCALE = 6;
  private static final Set<String> UNPRICED_WARNED = ConcurrentHashMap.newKeySet();

  /** Uncached input, cached input and output at their own rates; reasoning is billed as output. */
  public BigDecimal cost(String model, Usage usage) {
    Settings.Price price = prices.get(model);
    if (price == null) {
      if (UNPRICED_WARNED.add(model)) {
        log.warn("no price for model {}; its calls cost 0", model);
      }
      return BigDecimal.ZERO.setScale(SCALE);
    }
    return price
        .input()
        .multiply(BigDecimal.valueOf(usage.in() - usage.cached()))
        .add(price.cached().multiply(BigDecimal.valueOf(usage.cached())))
        .add(price.output().multiply(BigDecimal.valueOf(usage.out())))
        .divide(MILLION, SCALE, RoundingMode.HALF_UP);
  }
}
