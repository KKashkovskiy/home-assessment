package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.enrola.anna.agent.EndReason;
import com.enrola.anna.agent.Stage;
import com.enrola.anna.conversation.ConversationStatus;
import com.enrola.anna.conversation.Source;
import com.enrola.anna.lead.Suppression;
import com.enrola.anna.web.ConversationFilter.Param;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.ResolvableType;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.validation.DataBinder;

/**
 * Filters bound from query parameters as the list's controller binds them, written back into links,
 * chips and the form's hidden fields.
 */
class ConversationFilterTest {

  @Test
  void chipsOnePerSetFieldEachRemovingOnlyItself() {
    ConversationFilter filter =
        bound(Map.of("status", "ACTIVE", "flag", "HEALTH_INFO", "needsHuman", "true", "q", "jo"));

    assertThat(filter.chips())
        .extracting(ConversationFilter.Chip::param, ConversationFilter.Chip::removeUrl)
        .containsExactly(
            tuple(Param.STATUS, "/conversations?flag=HEALTH_INFO&needsHuman=true&q=jo"),
            tuple(Param.FLAG, "/conversations?status=ACTIVE&needsHuman=true&q=jo"),
            tuple(Param.NEEDS_HUMAN, "/conversations?status=ACTIVE&flag=HEALTH_INFO&q=jo"));
  }

  @Test
  void queryEncodesTheSearchAndTheStartLinkAddsItsFlag() {
    ConversationFilter filter = bound(Map.of("status", "ACTIVE", "q", " a&b "));

    assertThat(filter.toQuery()).isEqualTo("?status=ACTIVE&q=a%26b");
    assertThat(filter.startUrl()).isEqualTo("/conversations?status=ACTIVE&q=a%26b&start=true");
    assertThat(ConversationFilter.NONE.startUrl()).isEqualTo("/conversations?start=true");
  }

  @Test
  void blankSearchIsNoFilter() {
    ConversationFilter filter = bound(Map.of("q", "  "));

    assertThat(filter.isEmpty()).isTrue();
    assertThat(filter).isEqualTo(ConversationFilter.NONE);
    assertThat(filter.toQuery()).isEmpty();
  }

  @Test
  void drillDownFieldsAreChipsAndHiddenFieldsButNotFormControls() {
    ConversationFilter filter =
        bound(
            Map.of(
                "status", "ACTIVE",
                "replied", "true",
                "optOutReason", "KEYWORD",
                "reached", "CALL_ASK",
                "since", "2026-07-19T00:00:00Z",
                "guardCaught", "true"));

    assertThat(filter.chips())
        .extracting(ConversationFilter.Chip::param, ConversationFilter.Chip::value)
        .containsExactly(
            tuple(Param.STATUS, ConversationStatus.ACTIVE),
            tuple(Param.REPLIED, true),
            tuple(Param.OPT_OUT_REASON, Suppression.Reason.KEYWORD),
            tuple(Param.REACHED, Stage.CALL_ASK),
            tuple(Param.SINCE, Instant.parse("2026-07-19T00:00:00Z")),
            tuple(Param.GUARD_CAUGHT, true));
    assertThat(filter.hiddenParams())
        .containsExactly(
            Map.entry("replied", "true"),
            Map.entry("optOutReason", "KEYWORD"),
            Map.entry("reached", "CALL_ASK"),
            Map.entry("since", "2026-07-19T00:00:00Z"),
            Map.entry("guardCaught", "true"));
  }

  @Test
  void linksNarrowInParameterOrderAndBindBack() {
    ConversationFilter cohort =
        ConversationFilter.cohort(Source.SIM, "v2", null, Instant.parse("2026-07-19T00:00:00Z"));

    String url =
        cohort.url(
            "/conversations",
            Map.of(
                Param.OPT_OUT_REASON, Suppression.Reason.MODEL,
                Param.END_REASON, EndReason.UNSUBSCRIBED));

    assertThat(url)
        .isEqualTo(
            "/conversations?endReason=UNSUBSCRIBED&source=SIM&optOutReason=MODEL"
                + "&promptVersion=v2&since=2026-07-19T00%3A00%3A00Z&reset=false");
    assertThat(bound(Map.of("reset", "false", "source", "SIM")).reset()).isFalse();
  }

  @Test
  void stagesReachedAreThoseAtOrPastTheStageAndAllWithoutOne() {
    assertThat(bound(Map.of("reached", "OFFER_TIMES")).stagesReached())
        .containsExactly(Stage.OFFER_TIMES, Stage.BOOKED);
    assertThat(ConversationFilter.NONE.stagesReached()).containsExactly(Stage.values());
  }

  /** The filter Spring binds from these query parameters. */
  private static ConversationFilter bound(Map<String, String> query) {
    DataBinder binder = new DataBinder(null);
    binder.setTargetType(ResolvableType.forClass(ConversationFilter.class));
    binder.setConversionService(new DefaultFormattingConversionService());
    binder.construct(
        new DataBinder.ValueResolver() {
          @Override
          public Object resolveValue(String name, Class<?> type) {
            return query.get(name);
          }

          @Override
          public Set<String> getNames() {
            return query.keySet();
          }
        });
    assertThat(binder.getBindingResult().hasErrors()).isFalse();
    return (ConversationFilter) binder.getTarget();
  }
}
