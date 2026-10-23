package com.enrola.anna.web;

import com.enrola.anna.prompt.PromptStore;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Assembles the {@link AnalyticsPage}: the figures for the filter, and the comparison of the two
 * versions asked for, or by default the active version against the one before it.
 */
@Component
public class AnalyticsPages {

  static final Notice BAD_REQUEST =
      new Notice(
          Notice.Level.FAIL,
          "BAD_REQUEST",
          "400 BAD_REQUEST: a filter had a value this page does not take, so here are the last"
              + " seven days.");

  private final AnalyticsService analytics;
  private final PromptStore prompts;

  public AnalyticsPages(AnalyticsService analytics, PromptStore prompts) {
    this.analytics = analytics;
    this.prompts = prompts;
  }

  /** {@code a} and {@code b} pick the compared versions; one that names no version is ignored. */
  public AnalyticsPage page(AnalyticsFilter filter, String a, String b) {
    List<String> versions = prompts.list().stream().map(PromptStore.VersionSummary::id).toList();
    String active = prompts.activeId();
    String before = versions.get(Math.max(0, versions.indexOf(active) - 1));
    return new AnalyticsPage(
        filter,
        analytics.view(filter),
        analytics.compare(
            versions.contains(a) ? a : before, versions.contains(b) ? b : active, filter),
        versions,
        analytics.models(),
        null);
  }
}
