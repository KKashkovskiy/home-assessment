package com.enrola.anna.web;

import java.util.List;

/**
 * Everything the Analytics page renders: the filter as applied, the figures and charts for it, the
 * two prompt versions face to face, the versions and models the selects offer, and a notice or
 * null.
 */
public record AnalyticsPage(
    AnalyticsFilter filter,
    AnalyticsView view,
    CompareView compare,
    List<String> versions,
    List<String> models,
    Notice notice) {

  public AnalyticsPage {
    versions = List.copyOf(versions);
    models = List.copyOf(models);
  }

  AnalyticsPage withNotice(Notice shown) {
    return new AnalyticsPage(filter, view, compare, versions, models, shown);
  }
}
