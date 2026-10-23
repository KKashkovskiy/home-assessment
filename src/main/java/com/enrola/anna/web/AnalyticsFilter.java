package com.enrola.anna.web;

import com.enrola.anna.conversation.Source;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * The analytics page's GET parameters: which conversations, by when they started, where from, on
 * which model and prompt version. A missing range is the last seven days; any other missing or
 * blank value means all.
 */
public record AnalyticsFilter(Range range, Source source, String model, String promptVersion) {

  /** How far back a conversation's start may be, from the app clock's now. */
  public enum Range {
    LAST_24H(Duration.ofHours(24)),
    LAST_7D(Duration.ofDays(7)),
    LAST_30D(Duration.ofDays(30)),
    ALL(null);

    private final Duration back;

    Range(Duration back) {
      this.back = back;
    }
  }

  public AnalyticsFilter {
    range = range == null ? Range.LAST_7D : range;
    model = blankToNull(model);
    promptVersion = blankToNull(promptVersion);
  }

  /**
   * The earliest start counted, or null for ALL. Cut to the minute, so a drill-down link reads
   * cleanly and stays the same for a minute.
   */
  public Instant since(Instant now) {
    return range.back == null ? null : now.minus(range.back).truncatedTo(ChronoUnit.MINUTES);
  }

  /** The same filter on one prompt version: a column of the face-to-face comparison. */
  public AnalyticsFilter withPromptVersion(String version) {
    return new AnalyticsFilter(range, source, model, version);
  }

  /** The list filter for the same conversations, which every drill-down link starts from. */
  public ConversationFilter toConversationFilter(Instant now) {
    return ConversationFilter.cohort(source, promptVersion, model, since(now));
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
