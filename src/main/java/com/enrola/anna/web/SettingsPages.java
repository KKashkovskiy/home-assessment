package com.enrola.anna.web;

import com.enrola.anna.config.Settings;
import com.enrola.anna.config.SettingsDefaults;
import com.enrola.anna.config.SettingsValidation;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;

/**
 * Assembles the {@link SettingsPage} from the settings it is handed and the quick replies, says why
 * a form cannot be saved, and words the section's notices. Keys and restart-only values are read
 * from the environment as each page renders.
 */
@Component
public class SettingsPages {

  static final Notice SAVED =
      new Notice(
          Notice.Level.PASS,
          "SETTINGS_SAVED",
          "Saved. Applies from the next turn; written to data/settings.json and reapplied at"
              + " boot.");

  private static final List<String> KEYS = List.of("OPENAI_API_KEY", "EVAL_OPENAI_API_KEY");
  private static final Pattern PRICE_CELL = Pattern.compile("(prices\\[\\d+])\\..+");

  private final QuickReplies quickReplies;
  private final SettingsDefaults defaults;
  private final Environment environment;

  public SettingsPages(
      QuickReplies quickReplies, SettingsDefaults defaults, Environment environment) {
    this.quickReplies = quickReplies;
    this.defaults = defaults;
    this.environment = environment;
  }

  /** The section on {@code current}, the settings that run now. */
  public SettingsPage stored(Settings current) {
    return page(SettingsForm.of(current, quickReplies.text()), Map.of(), Map.of(), null);
  }

  /**
   * Why {@code form} cannot be saved, one message per field: the values that did not bind first,
   * since the rules need them all; else the quick replies' problems and those of {@code rules},
   * every rule a save must pass. Empty when it can be saved.
   */
  Map<String, String> problems(
      SettingsForm form,
      BindingResult binding,
      Function<Settings, List<SettingsValidation.Problem>> rules) {
    Map<String, String> problems = new LinkedHashMap<>();
    binding.getFieldErrors().forEach(error -> problems.putIfAbsent(fieldOf(error), unbound(error)));
    if (!problems.isEmpty()) {
      return problems;
    }
    String quickRepliesProblem = QuickReplies.problem(form.quickReplies());
    if (quickRepliesProblem != null) {
      problems.put("quickReplies", quickRepliesProblem);
    }
    byField(form, rules.apply(form.toSettings())).forEach(problems::putIfAbsent);
    return problems;
  }

  /**
   * The first problem of each field, in the order the rules found them; a price row's under the row
   * of {@code form} it names.
   */
  static Map<String, String> byField(SettingsForm form, List<SettingsValidation.Problem> problems) {
    Map<String, String> byField = new LinkedHashMap<>();
    problems.forEach(problem -> byField.putIfAbsent(fieldOf(form, problem), problem.message()));
    return byField;
  }

  /**
   * The section on a refused {@code form}: each problem beside its field, and what was typed in the
   * fields that did not bind.
   */
  SettingsPage refused(SettingsForm form, BindingResult binding, Map<String, String> problems) {
    Map<String, String> typed = new LinkedHashMap<>();
    binding
        .getFieldErrors()
        .forEach(
            error -> typed.putIfAbsent(error.getField(), String.valueOf(error.getRejectedValue())));
    int count = problems.size();
    Notice notice =
        new Notice(
            Notice.Level.FAIL,
            "SETTINGS_NOT_SAVED",
            "Not saved: %d problem%s marked below. Nothing changed."
                .formatted(count, count == 1 ? "" : "s"));
    return page(form, problems, typed, notice);
  }

  private SettingsPage page(
      SettingsForm form, Map<String, String> problems, Map<String, String> typed, Notice notice) {
    return new SettingsPage(form, problems, typed, notice, restartOnly(), keys());
  }

  private List<SettingsPage.RestartOnly> restartOnly() {
    SettingsDefaults.Agent agent = defaults.agent();
    SettingsDefaults.Moderation moderation = defaults.moderation();
    return List.of(
        new SettingsPage.RestartOnly("Server port", environment.getProperty("server.port", "8080")),
        new SettingsPage.RestartOnly(
            "Database URL", environment.getProperty("spring.datasource.url", "")),
        new SettingsPage.RestartOnly(
            "Data directory", environment.getProperty("anna.data-dir", "")),
        new SettingsPage.RestartOnly("Model timeout", seconds(agent.timeout())),
        new SettingsPage.RestartOnly("Model retries", String.valueOf(agent.maxRetries())),
        new SettingsPage.RestartOnly("Strict model check", agent.strictModelCheck() ? "on" : "off"),
        new SettingsPage.RestartOnly("Moderation timeout", seconds(moderation.timeout())),
        new SettingsPage.RestartOnly(
            "Moderation retries", String.valueOf(moderation.maxRetries())));
  }

  /** Set or not, never the value. */
  private List<SettingsPage.Key> keys() {
    return KEYS.stream()
        .map(name -> new SettingsPage.Key(name, !environment.getProperty(name, "").isBlank()))
        .toList();
  }

  /** A price cell's error is its row's: {@code prices[2].input} is {@code prices[2]}. */
  private static String fieldOf(FieldError error) {
    Matcher cell = PRICE_CELL.matcher(error.getField());
    return cell.matches() ? cell.group(1) : error.getField();
  }

  /** The row a price problem names by its model, else the problem's own field. */
  private static String fieldOf(SettingsForm form, SettingsValidation.Problem problem) {
    List<SettingsForm.PriceRow> rows = form.priceRows();
    for (int row = 0; row < rows.size(); row++) {
      if (SettingsValidation.priceRowField(rows.get(row).model().strip()).equals(problem.field())) {
        return SettingsForm.rowField(row);
      }
    }
    return problem.field();
  }

  /** Words for a value that did not convert, by what the field holds. */
  private static String unbound(FieldError error) {
    List<String> codes = Arrays.asList(error.getCodes() == null ? new String[0] : error.getCodes());
    if (codes.contains("typeMismatch.int")) {
      return "Enter a whole number.";
    }
    if (codes.contains("typeMismatch.double")
        || codes.contains("typeMismatch.java.math.BigDecimal")) {
      return "Enter a number.";
    }
    return "Not a valid value.";
  }

  private static String seconds(Duration duration) {
    return duration.toMillis() % 1000 == 0
        ? duration.toSeconds() + " s"
        : duration.toMillis() + " ms";
  }
}
