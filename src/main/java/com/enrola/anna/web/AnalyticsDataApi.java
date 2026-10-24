package com.enrola.anna.web;

import com.enrola.anna.conversation.Source;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Analytics page's chart rows for the filter the page shows, which its script draws. JSON only,
 * errors included, since the script is its one caller.
 */
@RestController
public class AnalyticsDataApi {

  /** Each chart's rows by its {@code data-chart} key, in the page's order. */
  public record ChartData(Map<String, List<Series.Row>> charts) {}

  /** A request that cannot be answered; the message is fixed and never repeats the request. */
  public record DataError(String error, String message) {}

  private final AnalyticsService analytics;
  private final ShellModelAdvice shells;

  public AnalyticsDataApi(AnalyticsService analytics, ShellModelAdvice shells) {
    this.analytics = analytics;
    this.shells = shells;
  }

  @GetMapping(
      path = AnalyticsController.PATH + "/data.json",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ChartData data(@ModelAttribute AnalyticsFilter filter) {
    Map<String, List<Series.Row>> charts = new LinkedHashMap<>();
    analytics
        .charts(filter, shells.settings().scheduling().advisorZone())
        .forEach(series -> charts.put(series.key(), series.rows()));
    return new ChartData(charts);
  }

  @ExceptionHandler(BindException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  DataError invalid() {
    return new DataError(
        "VALIDATION",
        "range must be one of "
            + names(AnalyticsFilter.Range.values())
            + ", and source one of "
            + names(Source.values()));
  }

  private static String names(Enum<?>[] values) {
    return Arrays.stream(values).map(Enum::name).collect(Collectors.joining(", "));
  }
}
