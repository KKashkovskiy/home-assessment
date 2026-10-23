package com.enrola.anna.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * A substitute, a drop and an expansion cover the table; dashes and spaces outside it keep words
 * apart. Invisible characters are built from their code points so the source shows them.
 */
class Gsm7Test {

  static final String NON_BREAKING_HYPHEN = Character.toString(0x2011);
  static final String NARROW_NO_BREAK_SPACE = Character.toString(0x202F);
  static final String EN_SPACE = Character.toString(0x2002);

  @Test
  void quotesAndDashesBecomePlain() {
    assertThat(Gsm7.normalise("“Fair enough – a lot of people want both”"))
        .isEqualTo("\"Fair enough - a lot of people want both\"");
  }

  @Test
  void emojiIsDroppedWithItsSpace() {
    assertThat(Gsm7.normalise("Booked 👍 see you Monday")).isEqualTo("Booked see you Monday");
  }

  @Test
  void ellipsisBecomesThreeDots() {
    assertThat(Gsm7.normalise("Sure… tomorrow?")).isEqualTo("Sure... tomorrow?");
    assertThat(Gsm7.isClean("Sure... tomorrow?")).isTrue();
    assertThat(Gsm7.isClean("Sure… tomorrow?")).isFalse();
  }

  @Test
  void otherDashesAndSpacesKeepWordsApart() {
    assertThat(
            Gsm7.normalise(
                "no" + NON_BREAKING_HYPHEN + "obligation 15" + NON_BREAKING_HYPHEN + "min"))
        .isEqualTo("no-obligation 15-min");
    assertThat(Gsm7.normalise("12:00" + NARROW_NO_BREAK_SPACE + "PM or 1:00" + EN_SPACE + "PM"))
        .isEqualTo("12:00 PM or 1:00 PM");
  }
}
