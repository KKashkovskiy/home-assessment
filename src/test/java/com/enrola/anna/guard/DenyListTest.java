package com.enrola.anna.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.enrola.anna.lead.Lead;
import java.time.Instant;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The YAML is the test data: every example of every rule runs as its own test, a rule with fewer
 * than two examples of each kind fails by name, and so does a pattern no match example exercises.
 */
class DenyListTest {

  static final DenyList BUNDLED = DenyList.bundled();
  static final Lead LAUREN = lead("Bupa");
  static final Lead JANE = lead(null);

  @TestFactory
  Stream<DynamicTest> everyExampleBehaves() {
    return BUNDLED.rules().stream().flatMap(DenyListTest::examplesOf);
  }

  @TestFactory
  Stream<DynamicTest> everyPatternHasAMatchExample() {
    return BUNDLED.rules().stream()
        .flatMap(
            rule ->
                IntStream.range(0, rule.patterns().size())
                    .mapToObj(
                        index ->
                            DynamicTest.dynamicTest(
                                rule.id() + " pattern " + (index + 1) + " has a match example",
                                () -> assertThat(rule.match()).anyMatch(found(rule, index)))));
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

  private static Stream<DynamicTest> examplesOf(DenyList.Rule rule) {
    Lead lead = rule.noProviderOnly() ? JANE : LAUREN;
    DynamicTest counted =
        DynamicTest.dynamicTest(
            rule.id() + " has two examples of each kind",
            () -> {
              assertThat(rule.match()).hasSizeGreaterThanOrEqualTo(2);
              assertThat(rule.noMatch()).hasSizeGreaterThanOrEqualTo(2);
            });
    Stream<DynamicTest> matches =
        rule.match().stream()
            .map(
                sentence ->
                    DynamicTest.dynamicTest(
                        rule.id() + " matches: " + sentence,
                        () -> assertThat(brokenRules(sentence, lead)).contains(rule.id())));
    Stream<DynamicTest> misses =
        rule.noMatch().stream()
            .map(
                sentence ->
                    DynamicTest.dynamicTest(
                        rule.id() + " passes: " + sentence,
                        () -> assertThat(brokenRules(sentence, lead)).doesNotContain(rule.id())));
    return Stream.concat(Stream.of(counted), Stream.concat(matches, misses));
  }

  private static Predicate<String> found(DenyList.Rule rule, int index) {
    Pattern pattern = rule.patterns().get(index);
    return sentence -> pattern.matcher(sentence).find();
  }

  private static List<String> brokenRules(String sentence, Lead lead) {
    return BUNDLED.brokenRules(sentence, lead).stream().map(DenyList.Rule::id).toList();
  }

  private static Lead lead(String provider) {
    return new Lead(
        "lead", "Lauren", "+61400000001", null, "VIC", provider, null, null, null, Instant.EPOCH);
  }
}
