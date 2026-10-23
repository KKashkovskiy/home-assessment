package com.enrola.anna.eval;

import com.enrola.anna.agent.ModelResponse.Usage;
import com.enrola.anna.agent.PriceTable;
import com.enrola.anna.config.Settings;
import com.enrola.anna.config.SettingsStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;

/**
 * The eval's own models beside Anna's, the simulated lead's and the judge's: each is Anna's model
 * unless {@code eval.simulator.model} or {@code eval.judge.model} names another, priced by the
 * settings' price table, so a model it cannot price is known before any call. Their clients are
 * their own, over the eval-keyed connection, never Anna's model bean, and their instructions are
 * files under {@code evals/prompts/}.
 */
final class EvalModels {

  /** One eval model by name, with the prices its spend counts at. */
  record Model(String name, PriceTable prices) {

    /** Whether the price table knows the model; an unpriced one would spend past the cap unseen. */
    boolean priced() {
      return prices.prices().containsKey(name);
    }

    BigDecimal cost(Usage usage) {
      return prices.cost(name, usage);
    }
  }

  private static final Path PROMPTS = Path.of("evals/prompts");

  private final SettingsStore settings;
  private final String simulator;
  private final String judge;

  EvalModels(
      SettingsStore settings,
      @Value("${eval.simulator.model:}") String simulator,
      @Value("${eval.judge.model:}") String judge) {
    this.settings = settings;
    this.simulator = simulator;
    this.judge = judge;
  }

  Model simulator() {
    return model(simulator);
  }

  Model judge() {
    return model(judge);
  }

  /** {@code evals/prompts/<name>}, stripped. */
  static String prompt(String name) {
    Path file = PROMPTS.resolve(name);
    try {
      return Files.readString(file).strip();
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + file, e);
    }
  }

  private Model model(String named) {
    Settings current = settings.current();
    return new Model(
        named.isBlank() ? current.agent().model() : named, new PriceTable(current.priceTable()));
  }
}
