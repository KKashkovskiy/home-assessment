package com.enrola.anna.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The shipped list catches clear self-harm phrases and lets figurative insurance talk through;
 * loading refuses a file whose examples or patterns do not hold, so the app cannot boot on one.
 */
class SelfHarmPhrasesTest {

  static final SelfHarmPhrases BUNDLED = SelfHarmPhrases.bundled();

  /** One rule over two patterns, its examples right; each case below breaks one line. */
  static final String FIT =
      """
      rules:
        - id: die
          patterns: ['\\bwant to die\\b', '\\bwanna die\\b']
          match: ["I want to die", "i wanna die"]
          no_match: ["dying to know", "I'd die for it"]
      """;

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource(
      delimiter = '|',
      value = {
        "I'm going to kill myself                      | true",
        "gonna kil my self tonight                     | true",
        "honestly i just want to end my life           | true",
        "been thinking about ending it all             | true",
        "I feel suicidal                               | true",
        "Suicidal thoughts again                       | true",
        "is suicide covered                            | true",
        "I want to unalive myself                      | true",
        "I'm going to hang myself                      | true",
        "honestly I might kms                          | true",
        "I want to die                                 | true",
        "I WANT TO DIE                                 | true",
        "i wanna be dead                               | true",
        "i dont want to be alive anymore               | true",
        "i don’t want to be alive anymore              | true",
        "I don't wanna live any more                   | true",
        "everyone would be better off without me       | true",
        "life isn't worth living                       | true",
        "I keep wanting to hurt myself                 | true",
        "I've been self-harming again                  | true",
        "this premium is killing me                    | false",
        "I'm killing myself at work to pay this        | false",
        "I'd die for cheaper cover                     | false",
        "dying to know what the excess is              | false",
        "kill the policy, I'm done                     | false",
        "I want to end my life insurance policy        | false",
        "does the policy have a suicide clause?        | false",
        "whats the suicide exclusion period            | false",
        "I drive 20 kms to the hospital                | false",
        "is overdose cover included?                   | false",
        "I'm cutting myself a break this month         | false",
        "I hurt myself at the gym, is physio covered?  | false",
      })
  void shippedListMatchesIntentOnly(String text, boolean matches) {
    assertThat(BUNDLED.matches(text)).isEqualTo(matches);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "i wanna die      | true",
        "dying to know    | false",
      })
  void fitFileLoads(String text, boolean matches) {
    assertThat(SelfHarmPhrases.load(FIT).matches(text)).isEqualTo(matches);
  }

  @ParameterizedTest(name = "{2}")
  @CsvSource(
      delimiter = '|',
      value = {
        "'match: [\"I want to die\", \"i wanna die\"]'      | 'match: [\"I want to die\", \"i wanna die\", \"fine\"]' | die does not match its example: fine",
        "'no_match: [\"dying to know\", \"I''d die for it\"]' | 'no_match: [\"dying to know\", \"I want to die now\"]' | die no_match is matched: I want to die now",
        "'match: [\"I want to die\", \"i wanna die\"]'      | 'match: [\"I want to die\", \"I want to die now\"]'    | die pattern 2 has no match example",
        "'no_match: [\"dying to know\", \"I''d die for it\"]' | 'no_match: [\"dying to know\"]'                       | die needs at least two match and two no_match examples",
        "no_match:                                          | nomatch:                                               | nomatch",
        "\\bwant to die\\b                                    | (unclosed                                              | self-harm-phrases.yaml: die pattern 1 does not compile",
      })
  void loadRefusesABrokenFile(String line, String broken, String problem) {
    assertThatIllegalStateException()
        .isThrownBy(() -> SelfHarmPhrases.load(FIT.replace(line, broken)))
        .withMessageContaining(problem);
  }

  @Test
  void loadRefusesAFileWithoutRules() {
    assertThatIllegalStateException()
        .isThrownBy(() -> SelfHarmPhrases.load("rules: []"))
        .withMessageContaining("has no rules");
  }
}
