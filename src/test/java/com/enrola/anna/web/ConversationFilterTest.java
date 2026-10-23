package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.agent.Flag;
import com.enrola.anna.conversation.ConversationStatus;
import org.junit.jupiter.api.Test;

/** Filters round-trip through the query string and each chip removes exactly its own field. */
class ConversationFilterTest {

  @Test
  void chipsOnePerSetFieldEachRemovingOnlyItself() {
    ConversationFilter filter =
        new ConversationFilter(
            ConversationStatus.ACTIVE, null, null, Flag.HEALTH_INFO, true, null, "jo");

    assertThat(filter.chips())
        .containsExactly(
            new ConversationFilter.Chip(
                "state", "ACTIVE", "/conversations?flag=HEALTH_INFO&needsHuman=true&q=jo"),
            new ConversationFilter.Chip(
                "flag", "HEALTH INFO", "/conversations?status=ACTIVE&needsHuman=true&q=jo"),
            new ConversationFilter.Chip(
                "needs human", "yes", "/conversations?status=ACTIVE&flag=HEALTH_INFO&q=jo"));
  }

  @Test
  void queryEncodesTheSearchAndAppendsAParameter() {
    ConversationFilter filter =
        new ConversationFilter(ConversationStatus.ACTIVE, null, null, null, null, null, " a&b ");

    assertThat(filter.toQuery()).isEqualTo("?status=ACTIVE&q=a%26b");
    assertThat(filter.url("/conversations/c-1", "start", "true"))
        .isEqualTo("/conversations/c-1?status=ACTIVE&q=a%26b&start=true");
    assertThat(ConversationFilter.NONE.url("/conversations", "start", "true"))
        .isEqualTo("/conversations?start=true");
  }

  @Test
  void blankSearchIsNoFilter() {
    ConversationFilter filter = new ConversationFilter(null, null, null, null, null, null, "  ");

    assertThat(filter.isEmpty()).isTrue();
    assertThat(filter).isEqualTo(ConversationFilter.NONE);
    assertThat(filter.toQuery()).isEmpty();
  }
}
