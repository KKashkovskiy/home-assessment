package com.enrola.anna.web;

import com.enrola.anna.config.AtomicFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The composer's quick-reply chips: one {@code label | text} per line, {@code #} comments and blank
 * lines ignored, a line without a bar its own label and text. {@code quick-replies.txt} on the
 * classpath is the default and the same file in the data directory, written from the settings form,
 * the override, as with the settings themselves.
 */
@Component
public class QuickReplies {

  /** One chip: what it shows and what it sends as the lead. */
  public record QuickReply(String label, String text) {}

  /** The chips are for play, not storage. */
  public static final int MAX_LINES = 50;

  public static final int MAX_LINE_CHARS = 160;

  private static final String FILE = "quick-replies.txt";

  private final Path override;

  public QuickReplies(@Value("${anna.data-dir}") Path dataDir) {
    this.override = dataDir.resolve(FILE);
  }

  // ponytail: read per request, so an edit shows on the next page; cache when it shows in a profile
  public List<QuickReply> load() {
    return text()
        .lines()
        .map(String::strip)
        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
        .map(QuickReplies::parse)
        .toList();
  }

  /** The file as written: the override when there is one, else the default. */
  public String text() {
    try {
      return Files.exists(override)
          ? Files.readString(override)
          : new ClassPathResource(FILE).getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(FILE, e);
    }
  }

  /** Why {@code text} cannot be saved, or null when it can. */
  static String problem(String text) {
    List<String> lines = text.lines().toList();
    if (lines.size() > MAX_LINES) {
      return "At most " + MAX_LINES + " lines.";
    }
    if (lines.stream().anyMatch(line -> line.length() > MAX_LINE_CHARS)) {
      return "Each line at most " + MAX_LINE_CHARS + " characters.";
    }
    return null;
  }

  /**
   * Writes {@code text} as the override, line endings made {@code \n}; text equal to what is read
   * now writes nothing, so saving untouched settings leaves the default in place.
   */
  public void save(String text) {
    String normalised = text.replace("\r\n", "\n");
    if (!normalised.equals(text())) {
      AtomicFiles.write(override, normalised);
    }
  }

  /** One line of the file, already stripped and neither blank nor a comment. */
  static QuickReply parse(String line) {
    int bar = line.indexOf('|');
    if (bar < 0) {
      return new QuickReply(line, line);
    }
    return new QuickReply(line.substring(0, bar).strip(), line.substring(bar + 1).strip());
  }
}
