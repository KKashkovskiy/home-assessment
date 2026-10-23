package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The quick-replies file: a line is a label and the text it sends; edits are bounded and kept. */
class QuickRepliesTest {

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = ';',
      value = {
        "STOP | STOP; STOP; STOP",
        "not now | not now, maybe next month; not now; not now, maybe next month",
        "are you a bot?; are you a bot?; are you a bot?",
        "a |b | c; a; b | c"
      })
  void lineIsALabelAndTheTextAfterTheFirstBar(String line, String label, String text) {
    assertThat(QuickReplies.parse(line)).isEqualTo(new QuickReplies.QuickReply(label, text));
  }

  @ParameterizedTest(name = "{0} lines of {1} chars: {2}")
  @CsvSource({"50, 160, true", "51, 1, false", "1, 161, false"})
  void chipsAreForPlayNotStorage(int lines, int chars, boolean saves) {
    String text = ("x".repeat(chars) + "\n").repeat(lines);

    assertThat(QuickReplies.problem(text) == null).isEqualTo(saves);
  }

  /** Untouched chips write nothing, so the shipped file keeps applying; a change is written. */
  @Test
  void onlyAChangeIsWritten(@TempDir Path dataDir) {
    QuickReplies quickReplies = new QuickReplies(dataDir);

    quickReplies.save(quickReplies.text().replace("\n", "\r\n"));
    assertThat(dataDir.resolve("quick-replies.txt")).doesNotExist();

    quickReplies.save("STOP\r\nnot now | not now\r\n");
    assertThat(dataDir.resolve("quick-replies.txt")).hasContent("STOP\nnot now | not now\n");
    assertThat(quickReplies.load())
        .extracting(QuickReplies.QuickReply::label)
        .containsExactly("STOP", "not now");
  }
}
