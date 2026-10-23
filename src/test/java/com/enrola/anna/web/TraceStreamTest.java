package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enrola.anna.conversation.TraceEventType;
import com.enrola.anna.conversation.TraceLog;
import com.enrola.anna.conversation.TraceOutcome;
import com.enrola.anna.conversation.TracePublisher;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The live log over MockMvc's async support: a stream per conversation carrying ids only, a
 * listener released when its stream completes, and streams ended when the application stops.
 */
class TraceStreamTest extends ConsoleFixture {

  @Autowired TraceLog trace;
  @Autowired TracePublisher publisher;
  @Autowired TraceStream streams;

  @Test
  void turnEventCarriesIdsOnlyAndOnlyForItsConversation() throws Exception {
    String lauren = start("lauren");
    String john = start("john");
    MvcResult stream = subscribe(lauren);

    trace.record(john, 1, modelCall("SECRET OF ANOTHER CONVERSATION"));
    trace.record(lauren, 1, modelCall("SECRET SUMMARY"));
    trace.record(
        lauren,
        1,
        new TraceLog.Entry(TraceEventType.STATE_DIFF, "state", TraceOutcome.INFO, Map.of()));

    assertThat(stream.getResponse().getContentAsString())
        .isEqualTo(
            """
            :live log

            event:event
            data:{"turn":1,"type":"MODEL_CALL","name":"decide"}

            event:turn
            data:{"turn":1,"type":"STATE_DIFF","name":"state"}

            """);
  }

  @Test
  void completedStreamReleasesItsListener() throws Exception {
    String lauren = start("lauren");
    int openBefore = streams.openStreams();
    MvcResult stream = subscribe(lauren);
    assertThat(publisher.listeners(lauren)).isOne();
    assertThat(streams.openStreams()).isEqualTo(openBefore + 1);

    stream.getRequest().getAsyncContext().complete();

    assertThat(publisher.listeners(lauren)).isZero();
    assertThat(streams.openStreams()).isEqualTo(openBefore);
  }

  /** Graceful shutdown would otherwise wait out every open tab's stream. */
  @Test
  void stoppingTheApplicationEndsOpenStreams() throws Exception {
    MvcResult stream = subscribe(start("lauren"));

    streams.stop();

    assertThat(stream.getAsyncResult(1000)).isNull();
  }

  @Test
  void unknownConversationOrBadIdIs404() throws Exception {
    mvc.perform(get("/conversations/c-nope/events")).andExpect(status().isNotFound());
    mvc.perform(get("/conversations/no%20id/events")).andExpect(status().isNotFound());
  }

  private MvcResult subscribe(String conversationId) throws Exception {
    return mvc.perform(get("/conversations/" + conversationId + "/events"))
        .andExpect(request().asyncStarted())
        .andReturn();
  }

  private static TraceLog.Entry modelCall(String summary) {
    return new TraceLog.Entry(
        TraceEventType.MODEL_CALL, "decide", TraceOutcome.INFO, Map.of("summary", summary));
  }
}
