package com.enrola.anna.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.agent.ModelResponse.Usage;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PriceTableTest {

  static final PriceTable PRICES =
      new PriceTable(
          Map.of(
              "gpt-5.1",
              new PriceTable.Price(
                  new BigDecimal("1.25"), new BigDecimal("0.125"), new BigDecimal("10.00"))));

  /** 6,000 uncached x 1.25 + 4,000 cached x 0.125 + 500 out x 10, per million: 0.013. */
  @Test
  void cachedTokensPricedSeparately() {
    BigDecimal cost = PRICES.cost("gpt-5.1", new Usage(10_000, 4_000, 500, 200));

    assertThat(cost).isEqualTo(new BigDecimal("0.013000"));
  }

  @Test
  void unknownModelCostsZero() {
    assertThat(PRICES.cost("gpt-9", new Usage(10_000, 0, 500, 0)))
        .isEqualTo(new BigDecimal("0.000000"));
  }
}
