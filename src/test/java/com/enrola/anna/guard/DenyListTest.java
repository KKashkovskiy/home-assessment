package com.enrola.anna.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.enrola.anna.lead.Lead;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The YAML is the test data: the shipped file passes the self-test the Evals page runs before it
 * saves one, which decides every example of every rule and fails a rule with fewer than two
 * examples of each kind or a pattern no match example exercises.
 */
class DenyListTest {

  static final DenyList BUNDLED = DenyList.bundled();
  static final Lead LAUREN = lead("Bupa");

  /** One rule over two patterns, its examples right; each case below breaks one line. */
  static final String FIT =
      """
      negation: '\\bnot\\b'
      rules:
        - id: cheap
          patterns: ['\\bcheap', '\\bbargain']
          why: w
          match: ["it is cheap", "a bargain"]
          no_match: ["it is not cheap", "fair price"]
      """;

  @Test
  void shippedFilePassesItsSelfTest() {
    assertThat(DenyList.selfTest(DenyList.bundledText())).isEmpty();
  }

  @Test
  void fitFilePassesTheSelfTest() {
    assertThat(DenyList.selfTest(FIT)).isEmpty();
  }

  @ParameterizedTest(name = "{2}")
  @CsvSource(
      delimiter = '|',
      value = {
        "'match: [\"it is cheap\", \"a bargain\"]'      | 'match: [\"it is cheap\", \"a bargain\", \"fine\"]' | cheap does not match its example: fine",
        "'no_match: [\"it is not cheap\", \"fair price\"]' | 'no_match: [\"it is not cheap\", \"so cheap\"]'   | cheap matches its no_match: so cheap",
        "'match: [\"it is cheap\", \"a bargain\"]'      | 'match: [\"it is cheap\", \"so cheap\"]'          | cheap pattern 2 has no match example",
        "'no_match: [\"it is not cheap\", \"fair price\"]' | 'no_match: [\"fair price\"]'                      | cheap needs at least two match and two no_match examples",
        "'why: w'                                            | ''                                                 | does not load: cheap has no why",
      })
  void selfTestNamesWhatIsWrong(String line, String broken, String problem) {
    assertThat(DenyList.selfTest(FIT.replace(line, broken))).containsExactly(problem);
  }

  @Test
  void providerNoneRuleOnlyForNone() {
    assertThat(BUNDLED.violations("Bupa members often ask that.", LAUREN)).isEmpty();
  }

  @Test
  void negationIsSentenceScoped() {
    assertThat(BUNDLED.violations("I can't promise savings. You'll save hundreds.", LAUREN))
        .extracting(Violation::note)
        .singleElement()
        .asString()
        .startsWith("savings-promise: ");
  }

  /** A guard file that would load with a rule silently missing must fail boot instead. */
  @ParameterizedTest(name = "{1}")
  @CsvSource(
      delimiter = '|',
      value = {
        "{rules: [{id: x, patterns: [y], why: w}]}                             | deny-list has no negation",
        "{negation: never}                                                     | not a map with a list of rules",
        "{negation: never, rules: [{id: x, why: w}]}                           | x has no patterns",
        "{negation: never, rules: [{id: x, patterns: [y], why: w, only_when: z}]} | only provider_none is known"
      })
  void brokenFileFailsToLoad(String yaml, String message) {
    assertThatIllegalStateException()
        .isThrownBy(() -> DenyList.parse(yaml))
        .withMessageContaining(message);
  }

  private static Lead lead(String provider) {
    return new Lead(
        "lead", "Lauren", "+61400000001", null, "VIC", provider, null, null, null, Instant.EPOCH);
  }
}
