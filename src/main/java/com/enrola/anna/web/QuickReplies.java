package com.enrola.anna.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The composer's quick-reply chips from {@code quick-replies.txt}: one {@code label | text} per
 * line, {@code #} comments and blank lines ignored. A line without a bar is its own label and text.
 */
@Component
public class QuickReplies {

  /** One chip: what it shows and what it sends as the lead. */
  public record QuickReply(String label, String text) {}

  private static final String FILE = "quick-replies.txt";

  // ponytail: read per request, so an edit shows on the next page; cache when it shows in a profile
  public List<QuickReply> load() {
    try {
      return new ClassPathResource(FILE)
          .getContentAsString(StandardCharsets.UTF_8)
          .lines()
          .map(String::strip)
          .filter(line -> !line.isEmpty() && !line.startsWith("#"))
          .map(QuickReplies::parse)
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(FILE, e);
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
