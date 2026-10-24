package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enrola.anna.agent.Decisions;
import com.enrola.anna.agent.HandoffReason;
import com.enrola.anna.agent.ModelResponse;
import com.enrola.anna.config.SettingsFixture;
import java.net.URI;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The page, the chart rows the script draws, the comparison fragment, and figures whose links list
 * exactly the conversations they counted. Pages are checked by their model and structure, not by
 * their wording.
 */
class AnalyticsControllerTest extends AnalyticsFixture {

  private static final Pattern LISTED =
      Pattern.compile("class=\"conv-row\" href=\"/conversations/([^\"?]+)");

  private static final Pattern EVAL_ROW =
      Pattern.compile("data-key=\"evalPass\">(.*?)</tr>", Pattern.DOTALL);

  @Test
  void emptyDatabaseRendersEveryPanelEmpty() throws Exception {
    jdbc.update("delete from booking");
    jdbc.update("delete from trace_event");
    jdbc.update("delete from turn");
    jdbc.update("delete from message");
    jdbc.update("delete from conversation");

    ResultActions result = mvc.perform(get("/analytics")).andExpect(status().isOk());

    assertThat(page(result).view().started()).isZero();
    assertThat(body(result).split("class=\"chart-empty", -1)).hasSize(8);
    assertThat(body(result)).contains("id=\"kpi-show\"");
    assertThat(page(result).compare().empty()).isTrue();
  }

  @Test
  void tilesLinkToTheirListsAndTheDefaultRangeIsTheLastSevenDays() throws Exception {
    ResultActions result = mvc.perform(get("/analytics")).andExpect(status().isOk());

    assertThat(page(result).filter().range()).isEqualTo(AnalyticsFilter.Range.LAST_7D);
    assertThat(page(result).view().started()).isEqualTo(5);
    assertThat(body(result))
        .contains(
            "href=\"/conversations?replied=true&amp;since=2026-07-19T00%3A00%3A00Z&amp;reset=false\"");
  }

  @Test
  void dataJsonCarriesTheFiveChartsWithLinks() throws Exception {
    mvc.perform(get("/analytics/data.json?range=ALL"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.charts.funnel[0].value").value(6.0))
        .andExpect(
            jsonPath("$.charts.funnel[2].href")
                .value("/conversations?reached=CALL_ASK&reset=false"))
        .andExpect(jsonPath("$.charts.endReasons").isNotEmpty())
        .andExpect(jsonPath("$.charts.objections[0].segment").value(1.0))
        .andExpect(jsonPath("$.charts.cost[0].value").value(0.035))
        .andExpect(jsonPath("$.charts.latency[0].shown").value("2.4 s"));
  }

  @Test
  void dataJsonRefusesAnUnknownRange() throws Exception {
    mvc.perform(get("/analytics/data.json?range=FOREVER"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("VALIDATION"));
  }

  @Test
  void compareFragmentIsTheTableAlone() throws Exception {
    String fragment =
        body(mvc.perform(get("/analytics/compare?a=v1&b=v2&range=ALL")).andExpect(status().isOk()));

    assertThat(fragment).startsWith("<div").doesNotContain("<html");
    assertThat(fragment).contains("data-key=\"reply\"", "data-key=\"evalPass\"");
  }

  @Test
  void evalRowShowsEachVersionsPassKLinkedToItsReport() throws Exception {
    evalRun("2026-07-25T00:00:00Z", "v1", false, new EvalCounts(1, 8, 2, 0, 0, 10, 8, "PASS"));

    String page =
        body(mvc.perform(get("/analytics?range=ALL&a=v1&b=v2")).andExpect(status().isOk()));
    Matcher evalRow = EVAL_ROW.matcher(page);

    assertThat(evalRow.find()).isTrue();
    assertThat(evalRow.group(1))
        .contains(
            "href=\"/evals?run=2026-07-25T00-00-00Z#report-panel\"", "80.0%", "8 of 10", "no runs");
  }

  @Test
  void defaultPairIsTheActiveVersionAgainstTheOneBefore() throws Exception {
    prompts.activate("v2", SettingsFixture.DEFAULTS.persona());

    CompareView compare = page(mvc.perform(get("/analytics"))).compare();

    assertThat(List.of(compare.a(), compare.b())).containsExactly("v1", "v2");
    assertThat(page(mvc.perform(get("/analytics?a=v2&b=v9"))).compare())
        .extracting(CompareView::a, CompareView::b)
        .containsExactly("v2", "v2");
  }

  @Test
  void unknownFilterValueIs400OnTheDefaultsWithANotice() throws Exception {
    ResultActions result =
        mvc.perform(get("/analytics?range=FOREVER")).andExpect(status().isBadRequest());

    assertThat(page(result).notice()).isEqualTo(AnalyticsPages.BAD_REQUEST);
    assertThat(page(result).filter().range()).isEqualTo(AnalyticsFilter.Range.LAST_7D);
    assertThat(body(result)).contains("data-notice=\"BAD_REQUEST\"", "class=\"tabs\"");
    mvc.perform(get("/analytics/compare?a=v1&b=nope")).andExpect(status().isBadRequest());
  }

  /** Each figure's link, followed, lists the conversations it counted and no other. */
  @Nested
  class DrillDown {

    @Test
    void figuresListWhatTheyCounted() throws Exception {
      AnalyticsView view = analytics.view(allTime(), ADVISOR_ZONE);

      assertThat(listed(view.kpi("reply").href())).containsExactlyInAnyOrder(c1, c2, c3, c4, c5);
      assertThat(listed(view.kpi("bookedOfStarted").href())).containsExactlyInAnyOrder(c1, c2);
      assertThat(listed(view.kpi("optOutKeyword").href())).containsExactly(c4);
      assertThat(listed(view.kpi("optOutModel").href())).containsExactly(c5);
      assertThat(listed(view.kpi("violations").href())).containsExactly(c2);
      assertThat(listed(view.kpi("retries").href())).containsExactly(c2);
      assertThat(listed(view.series("funnel").rows().get(2).href()))
          .containsExactlyInAnyOrder(c1, c2, c3);
      assertThat(listed(view.series("objections").rows().getFirst().href())).containsExactly(c2);
      assertThat(listed(view.series("endReasons").rows().get(2).href())).containsExactly(c3);
    }

    @Test
    void listHeaderCountsEveryMatchAndResetsStayOut() throws Exception {
      conversations.reset(c6);
      String everyStarted =
          analytics.view(allTime(), ADVISOR_ZONE).kpi("costPerConversation").href();

      ResultActions list = mvc.perform(get(URI.create(everyStarted)));

      assertThat(listed(everyStarted)).hasSize(6).doesNotContain(c6);
      assertThat(((ConsolePage) list.andReturn().getModelAndView().getModel().get("page")).total())
          .isEqualTo(6);
    }

    @Test
    void linksKeepTheFiltersCohort() throws Exception {
      Kpi onV2 =
          analytics.view(filter(AnalyticsFilter.Range.ALL, null, "v2"), ADVISOR_ZONE).kpi("reply");
      Kpi lastMonth =
          analytics
              .view(filter(AnalyticsFilter.Range.LAST_30D, null, null), ADVISOR_ZONE)
              .kpi("costPerConversation");
      Kpi otherModel =
          analytics
              .view(filter(AnalyticsFilter.Range.ALL, "other", null), ADVISOR_ZONE)
              .kpi("costPerConversation");

      assertThat(listed(onV2.href())).containsExactly(c3);
      assertThat(listed(lastMonth.href())).containsExactlyInAnyOrder(c1, c2, c3, c4, c5);
      assertThat(listed(otherModel.href())).isEmpty();
      assertThat(body(mvc.perform(get(URI.create(lastMonth.href())))))
          .contains("type=\"hidden\" name=\"since\" value=\"2026-06-26T00:00:00Z\"");
    }

    @Test
    void handoffReasonLinksItsConversations() throws Exception {
      String failed = custom("Ana", "0400 000 107");
      model.enqueue(Decisions.failed(ModelResponse.Failure.TIMEOUT));
      inbound.handle(failed, "hello?", null);
      String requested = custom("Bo", "0400 000 108");
      model.decide(
          Decisions.engaged()
              .handsOff(HandoffReason.REQUESTED)
              .reply("Sure, an advisor will text you shortly.")
              .build());
      inbound.handle(requested, "can I talk to a person", null);

      AnalyticsView view = analytics.view(allTime(), ADVISOR_ZONE);

      assertThat(reason(view, "ERROR").numerator()).isEqualTo(1);
      assertThat(listed(reason(view, "ERROR").href())).containsExactly(failed);
      assertThat(listed(reason(view, "REQUESTED").href())).containsExactly(requested);
      assertThat(view.kpi("errors").numerator()).isEqualTo(1);
      assertThat(listed(view.kpi("errors").href())).containsExactly(failed);
      assertThat(view.ops().getFirst().errors()).isEqualTo(1);
    }

    private List<String> listed(String href) throws Exception {
      String page = body(mvc.perform(get(URI.create(href))).andExpect(status().isOk()));
      Matcher ids = LISTED.matcher(page);
      return ids.results().map(match -> match.group(1)).toList();
    }
  }

  private static AnalyticsFilter filter(
      AnalyticsFilter.Range range, String model, String promptVersion) {
    return new AnalyticsFilter(range, null, model, promptVersion);
  }

  private static Kpi reason(AnalyticsView view, String reason) {
    return view.handoffReasons().stream()
        .filter(kpi -> kpi.key().equals(reason))
        .findFirst()
        .orElseThrow();
  }

  private static AnalyticsPage page(ResultActions result) {
    return (AnalyticsPage) result.andReturn().getModelAndView().getModel().get("page");
  }
}
